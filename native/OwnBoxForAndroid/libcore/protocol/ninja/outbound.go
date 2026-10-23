package ninja

import (
	"context"
	"fmt"
	"net"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/v2raywebsocket"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const Type = "ninja"

type OutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	Method       string                        `json:"method"`
	Password     string                        `json:"password"`
	NodePassword string                        `json:"node_password"`
	Pass         bool                          `json:"pass,omitempty"`
	PassOptions  *PassOptions                  `json:"pass_options,omitempty"`
	TLS          *option.OutboundTLSOptions    `json:"tls,omitempty"`
	Transport    *option.V2RayTransportOptions `json:"transport,omitempty"`
	UDPOverTCP   *option.UDPOverTCPOptions     `json:"udp_over_tcp,omitempty"`
}

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[OutboundOptions](registry, Type, NewOutbound)
}

var _ adapter.Outbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	dialer     N.Dialer
	serverAddr M.Socksaddr
	options    OutboundOptions
	tlsConfig  tls.Config
	udpEnabled bool
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options OutboundOptions) (adapter.Outbound, error) {
	if options.Method != "aes-128-gcm" {
		return nil, fmt.Errorf("ninja: unsupported method %q", options.Method)
	}
	if options.Password == "" || options.NodePassword == "" {
		return nil, fmt.Errorf("ninja: password and node_password are required")
	}
	serverAddr := options.ServerOptions.Build()
	if (!serverAddr.IsIP() && !M.IsDomainName(options.Server)) || options.ServerPort == 0 {
		return nil, fmt.Errorf("ninja: valid server and server_port are required")
	}
	if options.TLS == nil || !options.TLS.Enabled {
		return nil, fmt.Errorf("ninja: TLS is required")
	}
	if options.Transport != nil && options.Transport.Type != "ws" {
		return nil, fmt.Errorf("ninja: only WebSocket transport is supported")
	}
	if options.UDPOverTCP != nil && options.UDPOverTCP.Version != 0 && options.UDPOverTCP.Version != 2 {
		return nil, fmt.Errorf("ninja: only UDP over TCP version 2 is supported")
	}
	if options.Pass {
		if options.PassOptions == nil {
			return nil, fmt.Errorf("ninja: pass_options are required when pass is enabled")
		}
		normalized, err := normalizePassOptions(*options.PassOptions)
		if err != nil {
			return nil, err
		}
		options.PassOptions = &normalized
	}
	tlsOptions := *options.TLS
	if tlsOptions.UTLS == nil {
		tlsOptions.UTLS = &option.OutboundUTLSOptions{Enabled: true, Fingerprint: "chrome"}
	}
	if len(tlsOptions.ALPN) == 0 {
		tlsOptions.ALPN = []string{"http/1.1"}
	}
	config, err := tls.NewClient(ctx, logger, options.Server, tlsOptions)
	if err != nil {
		return nil, err
	}
	base, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	networks := []string{N.NetworkTCP}
	enabledUOT := options.UDPOverTCP != nil && options.UDPOverTCP.Enabled
	if enabledUOT {
		networks = append(networks, N.NetworkUDP)
	}
	h := &Outbound{Adapter: outbound.NewAdapterWithDialerOptions(Type, tag, networks, options.DialerOptions), dialer: base, serverAddr: serverAddr, options: options, tlsConfig: config}
	h.udpEnabled = enabledUOT
	return h, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		return (*ninjaDialer)(h).DialContext(ctx, network, destination)
	case N.NetworkUDP:
		if h.udpEnabled {
			return (*ninjaDialer)(h).DialContext(ctx, network, destination)
		}
		return nil, fmt.Errorf("ninja: UDP is not enabled")
	default:
		return nil, fmt.Errorf("ninja: unsupported network %q", network)
	}
}
func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if !h.udpEnabled {
		return nil, fmt.Errorf("ninja: UDP is not enabled")
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	conn, err := (*ninjaDialer)(h).DialContext(ctx, N.NetworkUDP, destination)
	if err != nil {
		return nil, err
	}
	return conn.(net.PacketConn), nil
}

// Each upgrade consumes one connection made by the protected sing-box dialer.
type connectedDialer struct{ conn net.Conn }

func (d *connectedDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	if d.conn == nil {
		return nil, net.ErrClosed
	}
	conn := d.conn
	d.conn = nil
	return conn, nil
}
func (d *connectedDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, fmt.Errorf("ninja: transport requires TCP")
}

type ninjaDialer Outbound

func (h *ninjaDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (conn net.Conn, err error) {
	network = N.NetworkName(network)
	if network != N.NetworkTCP && network != N.NetworkUDP {
		return nil, fmt.Errorf("ninja: core requires TCP")
	}
	raw, err := h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
	if err != nil {
		return nil, err
	}
	stop := context.AfterFunc(ctx, func() { raw.Close() })
	defer func() {
		stop()
		if err != nil {
			raw.Close()
		}
	}()
	if deadline, ok := ctx.Deadline(); ok {
		if err = raw.SetDeadline(deadline); err != nil {
			return nil, err
		}
	}
	if h.options.Transport != nil {
		var transport adapter.V2RayClientTransport
		transport, err = v2raywebsocket.NewClient(ctx, &connectedDialer{conn: raw}, h.serverAddr, h.options.Transport.WebsocketOptions, h.tlsConfig)
		if err == nil {
			conn, err = transport.DialContext(ctx)
		}
	} else {
		conn, err = tls.ClientHandshake(ctx, raw, h.tlsConfig)
	}
	if err != nil {
		return nil, err
	}
	if h.options.Pass {
		conn, err = newPassConn(conn, *h.options.PassOptions)
		if err != nil {
			return nil, err
		}
	}
	if network == N.NetworkUDP {
		var packet net.PacketConn
		packet, err = newCorePacketConn(conn, h.options.Method, h.options.Password, h.options.NodePassword, destination)
		if err != nil {
			return nil, err
		}
		conn = packet.(*corePacketConn)
	} else {
		conn, err = newCoreConn(conn, h.options.Method, h.options.Password, h.options.NodePassword, destination)
		if err != nil {
			return nil, err
		}
		if _, err = conn.Write(nil); err != nil {
			return nil, err
		}
	}
	stop()
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	if err = raw.SetDeadline(time.Time{}); err != nil {
		return nil, err
	}
	return conn, nil
}
func (h *ninjaDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, fmt.Errorf("ninja: core requires TCP")
}
