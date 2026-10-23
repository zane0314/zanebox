package transport

import (
	"context"
	"fmt"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	boxDNS "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"

	mDNS "github.com/miekg/dns"
)

type udpLoopbackServer struct {
	net.PacketConn
	mu       sync.Mutex
	requests map[string]int
}

func newUDPLoopbackServer(t *testing.T, firstOnly bool) *udpLoopbackServer {
	t.Helper()
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &udpLoopbackServer{PacketConn: conn, requests: make(map[string]int)}
	done := make(chan struct{})
	go func() {
		defer close(done)
		buffer := make([]byte, 65535)
		for {
			n, addr, err := conn.ReadFrom(buffer)
			if err != nil {
				return
			}
			request := new(mDNS.Msg)
			if err = request.Unpack(buffer[:n]); err != nil {
				t.Errorf("unpack query: %v", err)
				return
			}
			server.mu.Lock()
			server.requests[addr.String()]++
			count := server.requests[addr.String()]
			server.mu.Unlock()
			if firstOnly && count > 1 {
				continue
			}
			response := new(mDNS.Msg)
			response.SetReply(request)
			wire, err := response.Pack()
			if err != nil {
				t.Errorf("pack response: %v", err)
				return
			}
			if _, err = conn.WriteTo(wire, addr); err != nil {
				return
			}
		}
	}()
	t.Cleanup(func() { conn.Close(); <-done })
	return server
}
func (s *udpLoopbackServer) sourceCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.requests)
}
func newTestUDPTransport(t *testing.T, server *udpLoopbackServer) *UDPTransport {
	t.Helper()
	transportDialer, err := dialer.NewDefault(context.Background(), option.DialerOptions{})
	if err != nil {
		t.Fatal(err)
	}
	tr := NewUDPRaw(logger.NOP(), boxDNS.NewTransportAdapter(C.DNSTypeUDP, "test", nil), transportDialer, M.SocksaddrFromNet(server.LocalAddr()))
	t.Cleanup(func() { tr.Close() })
	return tr
}
func exchangeUDP(tr *UDPTransport, id uint16) error {
	message := new(mDNS.Msg)
	message.SetQuestion(fmt.Sprintf("query-%d.example.", id), mDNS.TypeA)
	message.Id = id
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	response, err := tr.Exchange(ctx, message)
	if err != nil {
		return err
	}
	if response.Id != id || len(response.Question) != 1 || response.Question[0] != message.Question[0] {
		return fmt.Errorf("response ID/question mismatch for %d", id)
	}
	return nil
}
func waitUDPReuseState(t *testing.T, tr *UDPTransport, state int32, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for tr.multiplexer.reuseState.Load() != state {
		if time.Now().After(deadline) {
			t.Fatalf("reuse state %d, want %d", tr.multiplexer.reuseState.Load(), state)
		}
		time.Sleep(10 * time.Millisecond)
	}
}
func burstUDP(t *testing.T, tr *UDPTransport, start uint16, count int) {
	t.Helper()
	results := make(chan error, count)
	for i := range count {
		go func(id uint16) { results <- exchangeUDP(tr, id) }(start + uint16(i))
	}
	for range count {
		if err := <-results; err != nil {
			t.Fatalf("concurrent UDP query failed: %v", err)
		}
	}
}

func TestUDPTransportSingleQueryServer(t *testing.T) {
	t.Parallel()
	server := newUDPLoopbackServer(t, true)
	tr := newTestUDPTransport(t, server)
	for id := uint16(100); id < 103; id++ {
		if err := exchangeUDP(tr, id); err != nil {
			t.Fatalf("sequential UDP query %d failed: %v", id, err)
		}
	}
	burstUDP(t, tr, 200, 6)
	waitUDPReuseState(t, tr, reuseStateUnsupported, reuseProbeTimeout+time.Second)
	// Once the failed probe completes, subsequent queries must stay on fresh sockets.
	burstUDP(t, tr, 300, 4)
}

func TestUDPTransportProbeEnablesReuse(t *testing.T) {
	t.Parallel()
	server := newUDPLoopbackServer(t, false)
	tr := newTestUDPTransport(t, server)
	if err := exchangeUDP(tr, 100); err != nil {
		t.Fatal(err)
	}
	waitUDPReuseState(t, tr, reuseStateSupported, time.Second)
	if err := exchangeUDP(tr, 101); err != nil {
		t.Fatal(err)
	}
	sources := server.sourceCount()
	burstUDP(t, tr, 200, 8)
	for id := uint16(300); id < 303; id++ {
		if err := exchangeUDP(tr, id); err != nil {
			t.Fatal(err)
		}
	}
	if got := server.sourceCount(); got != sources {
		t.Fatalf("unnecessary UDP redial: %d sources, previously %d", got, sources)
	}
	tr.Reset()
	if state := tr.multiplexer.reuseState.Load(); state != reuseStateUnknown {
		t.Fatalf("Reset left reuse state %d", state)
	}
	if err := exchangeUDP(tr, 400); err != nil {
		t.Fatal(err)
	}
	waitUDPReuseState(t, tr, reuseStateSupported, time.Second)
	if err := exchangeUDP(tr, 401); err != nil {
		t.Fatal(err)
	}
	if server.sourceCount() <= sources {
		t.Fatal("Reset did not replace UDP connection")
	}
}
