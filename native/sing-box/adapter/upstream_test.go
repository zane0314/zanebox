package adapter

import (
	"context"
	"net"
	"sync"
	"testing"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type routeTestRouter struct {
	ConnectionRouter
	check func(context.Context, InboundContext)
}

func (r routeTestRouter) RouteConnectionEx(ctx context.Context, _ net.Conn, metadata InboundContext, _ N.CloseHandlerFunc) {
	r.check(ctx, metadata)
}
func (r routeTestRouter) RoutePacketConnectionEx(ctx context.Context, _ N.PacketConn, metadata InboundContext, _ N.CloseHandlerFunc) {
	r.check(ctx, metadata)
}

type routeTestExpectedKey struct{}

func TestRouteHandlerRequestIsolation(t *testing.T) {
	base := InboundContext{Inbound: "base", Source: M.ParseSocksaddr("192.0.2.10:10"), Destination: M.ParseSocksaddr("192.0.2.20:20")}
	handler := NewRouteHandler(base, routeTestRouter{check: func(ctx context.Context, got InboundContext) {
		want := ctx.Value(routeTestExpectedKey{}).(InboundContext)
		if got.Source != want.Source || got.Destination != want.Destination || got.Inbound != want.Inbound {
			t.Errorf("request metadata: got %v -> %v, want %v -> %v", got.Source, got.Destination, want.Source, want.Destination)
		}
	}})
	call := func(packet bool, source, destination M.Socksaddr) {
		want := base
		if source.IsValid() {
			want.Source = source
		}
		if destination.IsValid() {
			want.Destination = destination
		}
		ctx := context.WithValue(context.Background(), routeTestExpectedKey{}, want)
		if packet {
			handler.NewPacketConnectionEx(ctx, nil, source, destination, nil)
		} else {
			handler.NewConnectionEx(ctx, nil, source, destination, nil)
		}
	}
	for _, packet := range []bool{false, true} {
		call(packet, M.ParseSocksaddr("198.51.100.1:100"), M.ParseSocksaddr("198.51.100.2:200"))
		call(packet, M.Socksaddr{}, M.Socksaddr{})
		call(packet, M.ParseSocksaddr("198.51.100.3:300"), M.Socksaddr{})
		call(packet, M.Socksaddr{}, M.ParseSocksaddr("198.51.100.4:400"))
	}
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := range 32 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			for j := range 32 {
				source, destination := base.Source, base.Destination
				source.Port, destination.Port = uint16(1000+i), uint16(2000+j)
				if j%2 == 0 {
					source = M.Socksaddr{}
				}
				call(i%2 == 0, source, destination)
			}
		}()
	}
	close(start)
	wg.Wait()
}
