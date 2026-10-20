package dialer

import (
	"context"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	M "github.com/sagernet/sing/common/metadata"
)

type concurrentFixture struct {
	started atomic.Int32
	ready   chan struct{}
	closed  chan struct{}
}

type resolvedFixture struct{ adapter.DNSRouter }

func (resolvedFixture) Lookup(context.Context, string, adapter.DNSQueryOptions) ([]netip.Addr, error) {
	return []netip.Addr{netip.MustParseAddr("192.0.2.1"), netip.MustParseAddr("192.0.2.2")}, nil
}

type serialFixture struct {
	started atomic.Int32
	block   bool
}

func (d *serialFixture) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	d.started.Add(1)
	if d.block {
		<-ctx.Done()
		return nil, ctx.Err()
	}
	a, b := net.Pipe()
	_ = b.Close()
	return a, nil
}
func (*serialFixture) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	panic("not used")
}
func (d *serialFixture) DialParallelInterface(ctx context.Context, network string, address M.Socksaddr, _ *C.NetworkStrategy, _ []C.InterfaceType, _ []C.InterfaceType, _ time.Duration) (net.Conn, error) {
	return d.DialContext(ctx, network, address)
}
func (*serialFixture) ListenSerialInterfacePacket(context.Context, M.Socksaddr, *C.NetworkStrategy, []C.InterfaceType, []C.InterfaceType, time.Duration) (net.PacketConn, error) {
	panic("not used")
}

func TestTCPConcurrentResolvePolicyAndCancellation(t *testing.T) {
	for _, parallelInterface := range []bool{false, true} {
		for _, enabled := range []bool{false, true} {
			d := &serialFixture{block: enabled}
			base := resolveDialer{router: resolvedFixture{}, dialer: d, parallel: true, tcpConcurrent: &enabled}
			var resolve ResolveDialer = &base
			if parallelInterface {
				resolve = &resolveParallelNetworkDialer{resolveDialer: base, dialer: d}
			}
			ctx, cancel := context.WithTimeout(context.Background(), 60*time.Millisecond)
			conn, err := resolve.DialContext(ctx, "tcp", M.ParseSocksaddr("example.test:443"))
			cancel()
			if conn != nil {
				_ = conn.Close()
			}
			want := int32(1)
			if enabled {
				want = 2
				if err == nil {
					t.Fatal("cancellation returned success")
				}
			} else if err != nil {
				t.Fatal(err)
			}
			if got := d.started.Load(); got != want {
				t.Fatalf("interface=%t enabled=%t attempts=%d want=%d", parallelInterface, enabled, got, want)
			}
		}
	}
}

type closeFixture struct {
	net.Conn
	once   sync.Once
	closed chan struct{}
}

func (c *closeFixture) Close() error { c.once.Do(func() { close(c.closed) }); return c.Conn.Close() }

func (d *concurrentFixture) DialContext(ctx context.Context, _ string, address M.Socksaddr) (net.Conn, error) {
	if d.started.Add(1) == 2 {
		close(d.ready)
	}
	select {
	case <-d.ready:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	a, b := net.Pipe()
	_ = b.Close()
	if address.Addr.String() == "192.0.2.1" {
		// Deliberately ignore cancellation: late successful sockets must close too.
		time.Sleep(40 * time.Millisecond)
		return &closeFixture{Conn: a, closed: d.closed}, nil
	}
	return a, nil
}
func (*concurrentFixture) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	panic("not used")
}

func TestTCPConcurrentRacesSameFamilyAndClosesLateSuccess(t *testing.T) {
	d := &concurrentFixture{ready: make(chan struct{}), closed: make(chan struct{})}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, err := dialConcurrentTCP(ctx, d, "tcp", M.ParseSocksaddr("example.test:443"), []netip.Addr{netip.MustParseAddr("192.0.2.1"), netip.MustParseAddr("192.0.2.2")})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if d.started.Load() != 2 {
		t.Fatal("same-family addresses were not raced")
	}
	select {
	case <-d.closed:
	case <-time.After(time.Second):
		t.Fatal("late successful loser leaked")
	}
}
