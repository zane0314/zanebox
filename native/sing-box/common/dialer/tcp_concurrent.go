package dialer

import (
	"context"
	"errors"
	"net"
	"net/netip"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// Race resolved TCP addresses on the existing dialer, preserving its socket
// protection, interface binding and detour. Late successful losers are closed.
func dialConcurrentTCP(ctx context.Context, dialer N.Dialer, network string, destination M.Socksaddr, addresses []netip.Addr) (net.Conn, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		conn net.Conn
		err  error
	}
	results := make(chan result)
	returned := make(chan struct{})
	defer close(returned)
	for _, address := range addresses {
		go func(address netip.Addr) {
			conn, err := dialer.DialContext(ctx, network, M.SocksaddrFrom(address, destination.Port))
			select {
			case results <- result{conn, err}:
			case <-returned:
				if conn != nil {
					_ = conn.Close()
				}
			}
		}(address)
	}
	var errs []error
	for range addresses {
		select {
		case res := <-results:
			if res.err == nil {
				return res.conn, nil
			}
			if res.conn != nil {
				_ = res.conn.Close()
			}
			errs = append(errs, res.err)
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	if len(errs) == 0 {
		return nil, net.ErrClosed
	}
	return nil, errors.Join(errs...)
}

type interfaceTCPDialer struct {
	ParallelInterfaceDialer
	strategy func(context.Context, string, M.Socksaddr) (net.Conn, error)
}

func (d interfaceTCPDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return d.strategy(ctx, network, destination)
}
