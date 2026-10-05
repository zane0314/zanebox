package ssh

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"golang.org/x/crypto/ssh"
)

type sshTestDialer struct {
	N.Dialer
	wrap func(net.Conn) net.Conn
}

func (d sshTestDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	conn, err := (&net.Dialer{}).DialContext(ctx, network, destination.String())
	if err == nil && d.wrap != nil {
		conn = d.wrap(conn)
	}
	return conn, err
}

// Real loopback SSH handshake and direct-tcpip channel; payload is echoed locally.
func newSSHTestServer(t *testing.T, onAuth func()) (M.Socksaddr, <-chan *ssh.ServerConn) {
	t.Helper()
	_, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.NewSignerFromKey(privateKey)
	if err != nil {
		t.Fatal(err)
	}
	config := &ssh.ServerConfig{NoClientAuth: true}
	if onAuth != nil {
		config.NoClientAuthCallback = func(ssh.ConnMetadata) (*ssh.Permissions, error) { onAuth(); return nil, nil }
	}
	config.AddHostKey(signer)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	accepted := make(chan *ssh.ServerConn, 64)
	var access sync.Mutex
	var connections []net.Conn
	var workers sync.WaitGroup
	workers.Add(1)
	go func() {
		defer workers.Done()
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			access.Lock()
			connections = append(connections, conn)
			access.Unlock()
			workers.Add(1)
			go func() {
				defer workers.Done()
				defer conn.Close()
				server, channels, requests, err := ssh.NewServerConn(conn, config)
				if err != nil {
					return
				}
				accepted <- server
				go ssh.DiscardRequests(requests)
				for request := range channels {
					if request.ChannelType() != "direct-tcpip" {
						_ = request.Reject(ssh.UnknownChannelType, "test only accepts forwarding")
						continue
					}
					channel, requests, err := request.Accept()
					if err != nil {
						continue
					}
					go ssh.DiscardRequests(requests)
					workers.Add(1)
					go func() { defer workers.Done(); defer channel.Close(); _, _ = io.Copy(channel, channel) }()
				}
			}()
		}
	}()
	t.Cleanup(func() {
		_ = listener.Close()
		access.Lock()
		for _, conn := range connections {
			_ = conn.Close()
		}
		access.Unlock()
		workers.Wait()
	})
	return M.ParseSocksaddr(listener.Addr().String()), accepted
}

type sshCloseGate struct {
	net.Conn
	entered chan struct{}
	release <-chan struct{}
}

func (c *sshCloseGate) Close() error {
	c.entered <- struct{}{}
	<-c.release
	return c.Conn.Close()
}

func TestCanceledHandshakeWaitIsolation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	entered := make(chan struct{}, 8)
	release := make(chan struct{})
	var releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	defer unblock()
	address, accepted := newSSHTestServer(t, func() {
		// The cancellation callback starts before SSH auth succeeds. Its socket
		// close is gated to model preemption before the actual close syscall.
		cancel()
		select {
		case <-entered:
		case <-time.After(5 * time.Second):
			t.Error("cancellation callback did not start")
		}
	})
	outbound := &Outbound{serverAddr: address, user: "test", dialer: sshTestDialer{wrap: func(conn net.Conn) net.Conn {
		return &sshCloseGate{Conn: conn, entered: entered, release: release}
	}}}
	client, err := outbound.connect(ctx)
	if client != nil || !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled handshake returned client=%v err=%v", client, err)
	}
	select {
	case <-accepted:
	case <-time.After(5 * time.Second):
		t.Fatal("real SSH handshake did not finish")
	}
	unblock()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("Wait cleanup did not close the connection")
	}
	outbound.clientAccess.Lock()
	if outbound.client != nil || outbound.clientConn != nil {
		t.Error("canceled handshake retained shared client")
	}
	outbound.clientAccess.Unlock()
}

func TestConnectionLifecycle(t *testing.T) {
	address, accepted := newSSHTestServer(t, nil)
	outbound := &Outbound{serverAddr: address, user: "test", dialer: sshTestDialer{}}
	t.Cleanup(func() { _ = outbound.Close() })
	first, err := outbound.connect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	server := <-accepted
	var workers sync.WaitGroup
	for i := 0; i < 16; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			client, err := outbound.connect(context.Background())
			if err != nil || client != first {
				t.Errorf("shared client: %v", err)
			}
		}()
	}
	workers.Wait()
	_ = server.Close()
	_ = first.Wait()
	deadline := time.Now().Add(5 * time.Second)
	for {
		outbound.clientAccess.Lock()
		cleared := outbound.client == nil
		outbound.clientAccess.Unlock()
		if cleared {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("disconnected client was retained")
		}
		time.Sleep(time.Millisecond)
	}
	second, err := outbound.connect(context.Background())
	if err != nil || second == first {
		t.Fatalf("reconnect: %v", err)
	}
	outbound.InterfaceUpdated(context.Background())
	third, err := outbound.connect(context.Background())
	if err != nil || third == second {
		t.Fatalf("interface reconnect: %v", err)
	}
	for i := 0; i < 16; i++ {
		workers.Add(1)
		go func() { defer workers.Done(); outbound.InterfaceUpdated(context.Background()); _ = outbound.Close() }()
	}
	workers.Wait()
	if client, err := outbound.connect(context.Background()); client != nil || !errors.Is(err, net.ErrClosed) {
		t.Fatalf("closed outbound accepted new session: %v", err)
	}
}
