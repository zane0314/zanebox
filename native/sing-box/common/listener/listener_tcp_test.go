package listener

import (
	"context"
	"errors"
	"github.com/sagernet/sing-box/log"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

type acceptTestListener struct {
	accept func() (net.Conn, error)
	calls  atomic.Int32
}

func (l *acceptTestListener) Accept() (net.Conn, error) {
	l.calls.Add(1)
	return l.accept()
}

func (l *acceptTestListener) Close() error {
	return nil
}

func (l *acceptTestListener) Addr() net.Addr {
	return acceptTestAddr{}
}

type acceptTestAddr struct{}

func (acceptTestAddr) Network() string {
	return "test"
}

func (acceptTestAddr) String() string {
	return "test"
}

type temporaryAcceptError struct{}

func (temporaryAcceptError) Error() string {
	return "temporary accept error"
}

//nolint:staticcheck
func (temporaryAcceptError) Temporary() bool {
	return true
}

func (temporaryAcceptError) Timeout() bool {
	return true
}

func TestAcceptTCPReturnsTerminalError(t *testing.T) {
	expectedErr := errors.New("terminal accept error")
	listener := &acceptTestListener{
		accept: func() (net.Conn, error) {
			return nil, expectedErr
		},
	}
	var temporaryErrors atomic.Int32

	_, err := acceptTCP(context.Background(), listener, func(error) {
		temporaryErrors.Add(1)
	})
	if !errors.Is(err, expectedErr) {
		t.Fatalf("acceptTCP error = %v, want %v", err, expectedErr)
	}
	if calls := listener.calls.Load(); calls != 1 {
		t.Fatalf("terminal error caused %d Accept calls, want 1", calls)
	}
	if temporaryErrors.Load() != 0 {
		t.Fatal("terminal error was reported as temporary")
	}
}

func TestAcceptTCPBacksOffTemporaryError(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	listener := &acceptTestListener{}
	listener.accept = func() (net.Conn, error) {
		if listener.calls.Load() == 1 {
			return nil, temporaryAcceptError{}
		}
		return serverConn, nil
	}
	var temporaryErrors atomic.Int32
	started := time.Now()
	conn, err := acceptTCP(context.Background(), listener, func(error) {
		temporaryErrors.Add(1)
	})
	if err != nil {
		t.Fatalf("acceptTCP returned error: %v", err)
	}
	if conn != serverConn {
		t.Fatal("acceptTCP returned an unexpected connection")
	}
	if elapsed := time.Since(started); elapsed < tcpAcceptTemporaryDelayMin {
		t.Fatalf("temporary error retry took %s, want at least %s", elapsed, tcpAcceptTemporaryDelayMin)
	}
	if calls := listener.calls.Load(); calls != 2 {
		t.Fatalf("temporary error caused %d Accept calls, want 2", calls)
	}
	if temporaryErrors.Load() != 1 {
		t.Fatalf("temporary error callback count = %d, want 1", temporaryErrors.Load())
	}
}

func TestLoopTCPInStopsAfterTerminalError(t *testing.T) {
	listener := &acceptTestListener{accept: func() (net.Conn, error) { return nil, errors.New("fatal") }}
	l := &Listener{ctx: context.Background(), tcpListener: listener, logger: log.NewNOPFactory().Logger()}
	done := make(chan struct{})
	go func() { l.loopTCPIn(); close(done) }()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("accept loop did not exit")
	}
	if listener.calls.Load() != 1 {
		t.Fatalf("accept called %d times", listener.calls.Load())
	}
}
func TestAcceptTCPBackoffCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	listener := &acceptTestListener{accept: func() (net.Conn, error) { return nil, temporaryAcceptError{} }}
	_, err := acceptTCP(ctx, listener, func(error) { cancel() })
	if !errors.Is(err, context.Canceled) || listener.calls.Load() != 1 {
		t.Fatalf("cancellation ignored: %v, calls %d", err, listener.calls.Load())
	}
}
