package dialer

import (
	"context"
	"errors"
	"github.com/database64128/tfo-go/v2"
	M "github.com/sagernet/sing/common/metadata"
	"net"
	"os"
	"syscall"
	"testing"
	"time"
)

func TestAnyBoxTFOCloseCancelsDial(t *testing.T) {
	entered := make(chan struct{})
	d := &tfo.Dialer{Dialer: net.Dialer{ControlContext: func(ctx context.Context, network, address string, c syscall.RawConn) error {
		close(entered)
		<-ctx.Done()
		return ctx.Err()
	}}}
	conn, err := DialSlowContext(d, context.Background(), "tcp", M.ParseSocksaddr("127.0.0.1:1"))
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() {
		n, e := conn.Write([]byte("test"))
		if n != 0 {
			done <- errors.New("failed write reported bytes")
			return
		}
		done <- e
	}()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("dial did not start")
	}
	closeDone := make(chan struct{})
	go func() { _ = conn.Close(); close(closeDone) }()
	select {
	case <-closeDone:
	case <-time.After(time.Second):
		t.Fatal("close blocked on dial")
	}
	select {
	case e := <-done:
		if !errors.Is(e, os.ErrClosed) {
			t.Fatal(e)
		}
	case <-time.After(time.Second):
		t.Fatal("write leaked")
	}
	if _, err = conn.Read(make([]byte, 1)); err == nil {
		t.Fatal("read after close")
	}
}
