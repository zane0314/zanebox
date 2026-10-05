//go:build with_quic

package sip003

import (
	"context"
	"net"
	"net/netip"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/v2ray"
	"github.com/sagernet/sing-box/transport/v2rayquic"
	"github.com/sagernet/sing/common"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type anyboxTransport struct{ closed bool }

func (p *anyboxTransport) DialContext(context.Context) (net.Conn, error) { return nil, nil }
func (p *anyboxTransport) Close() error                                  { p.closed = true; return nil }

func TestAnyBoxV2RayPluginOwnership(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p := &anyboxTransport{}
	var received context.Context
	v2ray.RegisterQUICConstructor(nil, func(c context.Context, _ N.Dialer, _ M.Socksaddr, _ option.V2RayQUICOptions, _ tls.Config) (adapter.V2RayClientTransport, error) {
		received = c
		return p, nil
	})
	defer v2ray.RegisterQUICConstructor(v2rayquic.NewServer, v2rayquic.NewClient)
	_, err := newV2RayPlugin(ctx, Args{"mode": {"quic"}, "tls": {"1"}}, nil, nil, M.Socksaddr{Addr: netip.MustParseAddr("127.0.0.1"), Port: 443})
	if err != nil {
		t.Fatal(err)
	}
	cancel()
	if received.Err() != context.Canceled {
		t.Fatal("plugin detached from outbound context")
	}
	if err := common.Close(&v2rayMuxWrapper{p}); err != nil {
		t.Fatal(err)
	}
	if !p.closed {
		t.Fatal("mux wrapper did not forward close")
	}
}
