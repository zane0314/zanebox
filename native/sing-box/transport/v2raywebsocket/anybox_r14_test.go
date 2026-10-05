package v2raywebsocket

import (
	"net"
	"net/http"
	"net/url"
	"sync"
	"testing"

	"github.com/sagernet/ws"
)

func TestAnyBoxR14ConcurrentUpgradeKeepsSharedSubprotocol(t *testing.T) {
	const rounds = 32
	const protocol = "anybox-r14"

	client := new(Client)
	requestURL := &url.URL{Scheme: "ws", Host: "example.org", Path: "/ws"}
	sharedHeaders := make(http.Header)
	sharedHeaders.Set("Sec-WebSocket-Protocol", protocol)
	sharedHeaders.Set("X-AnyBox-Test", "kept")
	errs := make(chan error, rounds*2)
	var wg sync.WaitGroup
	for i := 0; i < rounds; i++ {
		clientConn, serverConn := net.Pipe()
		wg.Add(2)
		go func() {
			defer wg.Done()
			defer serverConn.Close()
			handshake, err := (ws.Upgrader{
				Protocol: func(value []byte) bool { return string(value) == protocol },
			}).Upgrade(serverConn)
			if err != nil {
				errs <- err
				return
			}
			if handshake.Protocol != protocol {
				errs <- &unexpectedSubprotocol{got: handshake.Protocol}
			}
		}()
		go func() {
			defer wg.Done()
			conn, err := client.upgrade(clientConn, requestURL, sharedHeaders)
			if err != nil {
				errs <- err
				return
			}
			_ = conn.Conn.Close()
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	if got := sharedHeaders.Get("Sec-WebSocket-Protocol"); got != protocol {
		t.Fatalf("shared subprotocol after upgrades = %q, want %q", got, protocol)
	}
}

type unexpectedSubprotocol struct {
	got string
}

func (e *unexpectedSubprotocol) Error() string {
	return "unexpected negotiated subprotocol: " + e.got
}
