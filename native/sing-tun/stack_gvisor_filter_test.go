//go:build with_gvisor

package tun

import (
	"net/netip"
	"testing"

	"github.com/sagernet/gvisor/pkg/buffer"
	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/header"
	"github.com/sagernet/gvisor/pkg/tcpip/stack"
)

type attachProbe struct {
	stack.LinkEndpoint
	dispatcher stack.NetworkDispatcher
}

func (p *attachProbe) Attach(d stack.NetworkDispatcher) { p.dispatcher = d }

type dispatcherProbe struct {
	stack.NetworkDispatcher
	packet *stack.PacketBuffer
}

func (p *dispatcherProbe) DeliverNetworkPacket(_ tcpip.NetworkProtocolNumber, pkt *stack.PacketBuffer) {
	p.packet = pkt
}

type writerProbe struct {
	GVisorTun
	packet *stack.PacketBuffer
}

func (p *writerProbe) WritePacket(pkt *stack.PacketBuffer) (int, error) {
	p.packet = pkt
	return 0, nil
}

func TestLinkEndpointFilterAttach(t *testing.T) {
	underlying := &attachProbe{}
	dispatcher := &dispatcherProbe{}
	writer := &writerProbe{}
	w := &LinkEndpointFilter{
		LinkEndpoint:         underlying,
		Writer:               writer,
		BroadcastAddress:     netip.MustParseAddr("10.0.0.255"),
		Inet4Address:         netip.MustParseAddr("10.0.0.1"),
		Inet6Address:         netip.MustParseAddr("fd00::1"),
		Inet4LoopbackAddress: []netip.Addr{netip.MustParseAddr("10.0.0.2")},
		Inet6LoopbackAddress: []netip.Addr{netip.MustParseAddr("fd00::2")},
	}
	w.Attach(dispatcher)
	filter, ok := underlying.dispatcher.(*networkDispatcherFilter)
	if !ok || filter.NetworkDispatcher != dispatcher || filter.writer != writer || filter.broadcastAddress != w.BroadcastAddress ||
		filter.inet4Address != w.Inet4Address || filter.inet6Address != w.Inet6Address ||
		filter.inet4LoopbackAddress[0] != w.Inet4LoopbackAddress[0] || filter.inet6LoopbackAddress[0] != w.Inet6LoopbackAddress[0] {
		t.Fatal("non-nil dispatcher must preserve the existing filter")
	}
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{})
	defer pkt.DecRef()
	filter.DeliverNetworkPacket(header.IPv4ProtocolNumber, pkt)
	if dispatcher.packet != pkt {
		t.Fatal("existing short-packet forwarding changed")
	}
	ipv4 := make([]byte, header.IPv4MinimumSize)
	header.IPv4(ipv4).Encode(&header.IPv4Fields{DstAddr: tcpip.AddrFrom4(w.BroadcastAddress.As4())})
	broadcast := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(ipv4)})
	defer broadcast.DecRef()
	filter.DeliverNetworkPacket(header.IPv4ProtocolNumber, broadcast)
	if writer.packet != broadcast || dispatcher.packet != pkt {
		t.Fatal("existing broadcast writeback changed")
	}
	w.Attach(nil)
	if underlying.dispatcher != nil {
		t.Fatalf("detach forwarded %T instead of nil", underlying.dispatcher)
	}
}
