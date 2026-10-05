package listener

import (
	"context"
	"net"
	"net/netip"
	"strings"
	"syscall"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/redir"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"

	"github.com/database64128/tfo-go/v2"
)

func (l *Listener) ListenTCP() (net.Listener, error) {
	//nolint:staticcheck
	if l.listenOptions.ProxyProtocol || l.listenOptions.ProxyProtocolAcceptNoHeader {
		return nil, E.New("Proxy Protocol is deprecated and removed in sing-box 1.6.0")
	}
	var err error
	bindAddr := M.SocksaddrFrom(l.listenOptions.Listen.Build(netip.AddrFrom4([4]byte{127, 0, 0, 1})), l.listenOptions.ListenPort)
	var listenConfig net.ListenConfig
	if l.listenOptions.BindInterface != "" {
		listenConfig.Control = control.Append(listenConfig.Control, control.BindToInterface(service.FromContext[adapter.NetworkManager](l.ctx).InterfaceFinder(), l.listenOptions.BindInterface, -1))
	}
	if l.listenOptions.RoutingMark != 0 {
		listenConfig.Control = control.Append(listenConfig.Control, control.RoutingMark(uint32(l.listenOptions.RoutingMark)))
	}
	if l.listenOptions.ReuseAddr {
		listenConfig.Control = control.Append(listenConfig.Control, control.ReuseAddr())
	}
	if l.listenOptions.DisableTCPKeepAlive {
		listenConfig.KeepAlive = -1
		listenConfig.KeepAliveConfig.Enable = false
	} else {
		keepIdle := time.Duration(l.listenOptions.TCPKeepAlive)
		if keepIdle == 0 {
			keepIdle = C.TCPKeepAliveInitial
		}
		keepInterval := time.Duration(l.listenOptions.TCPKeepAliveInterval)
		if keepInterval == 0 {
			keepInterval = C.TCPKeepAliveInterval
		}
		listenConfig.KeepAliveConfig = net.KeepAliveConfig{
			Enable:   true,
			Idle:     keepIdle,
			Interval: keepInterval,
		}
	}
	if l.listenOptions.TCPMultiPath {
		listenConfig.SetMultipathTCP(true)
	}
	if l.tproxy {
		listenConfig.Control = control.Append(listenConfig.Control, func(network, address string, conn syscall.RawConn) error {
			return control.Raw(conn, func(fd uintptr) error {
				return redir.TProxy(fd, !strings.HasSuffix(network, "4"), false)
			})
		})
	}
	tcpListener, err := ListenNetworkNamespace[net.Listener](l.ctx, l.listenOptions.NetNs, func() (net.Listener, error) {
		if l.listenOptions.TCPFastOpen {
			var tfoConfig tfo.ListenConfig
			tfoConfig.ListenConfig = listenConfig
			return tfoConfig.Listen(l.ctx, M.NetworkFromNetAddr(N.NetworkTCP, bindAddr.Addr), bindAddr.String())
		} else {
			return listenConfig.Listen(l.ctx, M.NetworkFromNetAddr(N.NetworkTCP, bindAddr.Addr), bindAddr.String())
		}
	})
	if err != nil {
		return nil, err
	}
	l.logger.Info("tcp server started at ", tcpListener.Addr())
	l.tcpListener = tcpListener
	return tcpListener, err
}

const (
	tcpAcceptTemporaryDelayMin = 5 * time.Millisecond
	tcpAcceptTemporaryDelayMax = time.Second
)

// acceptTCP keeps temporary Accept failures from turning into a busy loop.
// The caller's context also lets normal listener shutdown interrupt the delay.
func acceptTCP(ctx context.Context, tcpListener net.Listener, onTemporaryError func(error)) (net.Conn, error) {
	var tempDelay time.Duration
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}

		conn, err := tcpListener.Accept()
		if err == nil {
			return conn, nil
		}
		//nolint:staticcheck
		netError, isNetError := err.(net.Error)
		if !isNetError || !netError.Temporary() {
			return nil, err
		}
		if onTemporaryError != nil {
			onTemporaryError(err)
		}
		if tempDelay == 0 {
			tempDelay = tcpAcceptTemporaryDelayMin
		} else if tempDelay >= tcpAcceptTemporaryDelayMax/2 {
			tempDelay = tcpAcceptTemporaryDelayMax
		} else {
			tempDelay *= 2
		}
		timer := time.NewTimer(tempDelay)
		select {
		case <-timer.C:
		case <-ctx.Done():
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
			return nil, ctx.Err()
		}
	}
}

func (l *Listener) loopTCPIn() {
	tcpListener := l.tcpListener
	var metadata adapter.InboundContext
	for {
		conn, err := acceptTCP(l.ctx, tcpListener, func(err error) {
			//nolint:staticcheck
			l.logger.Error(err)
		})
		if err != nil {
			if l.shutdown.Load() || l.ctx.Err() != nil || E.IsClosed(err) {
				return
			}
			_ = tcpListener.Close()
			l.logger.Error("tcp listener closed: ", err)
			return
		}
		//nolint:staticcheck
		metadata.InboundDetour = l.listenOptions.Detour
		metadata.Source = M.SocksaddrFromNet(conn.RemoteAddr()).Unwrap()
		metadata.OriginDestination = M.SocksaddrFromNet(conn.LocalAddr()).Unwrap()
		ctx := log.ContextWithNewID(l.ctx)
		l.logger.InfoContext(ctx, "inbound connection from ", metadata.Source)
		go l.connHandler.NewConnection(ctx, conn, metadata, nil)
	}
}
