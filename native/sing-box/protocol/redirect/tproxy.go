package redirect

import (
	"context"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/common/listener"
	"github.com/sagernet/sing-box/common/redir"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/control"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

func RegisterTProxy(registry *inbound.Registry) {
	inbound.Register[option.TProxyInboundOptions](registry, C.TypeTProxy, NewTProxy)
}

type TProxy struct {
	inbound.Adapter
	ctx      context.Context
	router   adapter.Router
	logger   log.ContextLogger
	listener *listener.Listener
	udpNat   *tun.UDPNat
}

func NewTProxy(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.TProxyInboundOptions) (adapter.Inbound, error) {
	tproxy := &TProxy{
		Adapter: inbound.NewAdapter(C.TypeTProxy, tag),
		ctx:     ctx,
		router:  router,
		logger:  logger,
	}
	var udpTimeout time.Duration
	if options.UDPTimeout != 0 {
		udpTimeout = time.Duration(options.UDPTimeout)
	} else {
		udpTimeout = C.UDPTimeout
	}
	networkManager := service.FromContext[adapter.NetworkManager](ctx)
	tproxy.udpNat = tun.NewUDPNat(tun.UDPNatOptions{
		Handler:         tproxy,
		Prepare:         tproxy.preparePacketConnection,
		Timeout:         udpTimeout,
		Mapping:         tun.NATMapping(options.UDPMapping),
		Filtering:       tun.NATFiltering(options.UDPFiltering),
		MaxSize:         options.UDPNATMax,
		InterfaceFinder: networkManager.InterfaceFinder(),
	})
	tproxy.listener = listener.New(listener.Options{
		Context:           ctx,
		Logger:            logger,
		Network:           options.Network.Build(),
		Listen:            options.ListenOptions,
		ConnectionHandler: tproxy,
		OOBPacketHandler:  tproxy,
		TProxy:            true,
	})
	return tproxy, nil
}

func (t *TProxy) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	err := t.udpNat.Start()
	if err != nil {
		return err
	}
	err = t.listener.Start()
	if err != nil {
		_ = t.udpNat.Close()
	}
	return err
}

func (t *TProxy) InterfaceUpdated(ctx context.Context) {
	t.udpNat.Purge()
}

func (t *TProxy) Close() error {
	_ = t.udpNat.Close()
	return t.listener.Close()
}

func (t *TProxy) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	metadata.Inbound = t.Tag()
	metadata.InboundType = t.Type()
	metadata.Destination = M.SocksaddrFromNet(conn.LocalAddr()).Unwrap()
	t.logger.InfoContext(ctx, "inbound connection to ", metadata.Destination)
	t.router.RouteConnectionEx(ctx, conn, metadata, onClose)
}

func (t *TProxy) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	t.logger.InfoContext(ctx, "inbound packet connection from ", source)
	t.logger.InfoContext(ctx, "inbound packet connection to ", destination)
	var metadata adapter.InboundContext
	metadata.Inbound = t.Tag()
	metadata.InboundType = t.Type()
	metadata.Source = source
	metadata.Destination = destination
	metadata.OriginDestination = t.listener.UDPAddr()
	t.router.RoutePacketConnectionEx(ctx, conn, metadata, onClose)
}

func (t *TProxy) NewPacket(buffer *buf.Buffer, oob []byte, source M.Socksaddr) {
	destination, err := redir.GetOriginalDestinationFromOOB(oob)
	if err != nil {
		t.logger.Warn("process packet from ", source, ": get tproxy destination: ", err)
		return
	}
	t.udpNat.NewPacket([][]byte{buffer.Bytes()}, source, M.SocksaddrFromNetIP(destination), nil)
}

func (t *TProxy) preparePacketConnection(source M.Socksaddr, destination M.Socksaddr, userData any) (bool, context.Context, N.PacketWriter, N.CloseHandlerFunc) {
	ctx := log.ContextWithNewID(t.ctx)
	writer := &tproxyPacketWriter{
		ctx:         ctx,
		listener:    t.listener,
		source:      source.AddrPort(),
		destination: destination,
	}
	return true, ctx, writer, func(it error) {
		_ = writer.Close()
	}
}

type tproxyPacketListener interface {
	ListenPacket(net.ListenConfig, context.Context, string, string) (net.PacketConn, error)
}

type tproxyPacketWriter struct {
	ctx         context.Context
	listener    tproxyPacketListener
	source      netip.AddrPort
	destination M.Socksaddr
	// access protects conn and closed; WritePacket releases it before UDP I/O.
	access sync.Mutex
	conn   *net.UDPConn
	closed bool
}

func (w *tproxyPacketWriter) Close() error {
	w.access.Lock()
	w.closed = true
	conn := w.conn
	w.conn = nil
	w.access.Unlock()
	return common.Close(common.PtrOrNil(conn))
}

func (w *tproxyPacketWriter) getConn(destination M.Socksaddr) (*net.UDPConn, bool, error) {
	w.access.Lock()
	defer w.access.Unlock()
	if w.closed {
		return nil, false, net.ErrClosed
	}
	if w.destination == destination && w.conn != nil {
		return w.conn, true, nil
	}
	var listenConfig net.ListenConfig
	listenConfig.Control = control.Append(listenConfig.Control, control.ReuseAddr())
	listenConfig.Control = control.Append(listenConfig.Control, redir.TProxyWriteBack())
	packetConn, err := w.listener.ListenPacket(listenConfig, w.ctx, "udp", destination.String())
	if err != nil {
		return nil, false, err
	}
	udpConn := packetConn.(*net.UDPConn)
	if w.destination == destination {
		w.conn = udpConn
		return udpConn, true, nil
	}
	return udpConn, false, nil
}

func (w *tproxyPacketWriter) closeConnIfCurrent(conn *net.UDPConn) error {
	w.access.Lock()
	// A failed write may belong to an old socket after another lifecycle action.
	if w.conn != conn {
		w.access.Unlock()
		return nil
	}
	w.conn = nil
	w.access.Unlock()
	return common.Close(common.PtrOrNil(conn))
}

func (w *tproxyPacketWriter) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	defer buffer.Release()
	conn, persistent, err := w.getConn(destination)
	if err != nil {
		return err
	}
	if !persistent {
		defer conn.Close()
	}
	_, err = conn.WriteToUDPAddrPort(buffer.Bytes(), w.source)
	if err != nil && persistent {
		_ = w.closeConnIfCurrent(conn)
	}
	return err
}
