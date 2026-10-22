package libcore

import (
	"context"
	"errors"
	"net/netip"
	"strings"
	"syscall"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/service"
	"golang.org/x/sys/unix"
)

// SubscriptionDNSPlatform binds each already-protected socket to the chosen physical network.
type SubscriptionDNSPlatform interface {
	AutoDetectInterfaceControl(fd int32) error
}

type SubscriptionDNSQuery struct {
	ctx     context.Context
	cancel  context.CancelFunc
	network *subscriptionDNSNetwork
}

func NewSubscriptionDNSQuery(protectPath string, platform SubscriptionDNSPlatform) *SubscriptionDNSQuery {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	return &SubscriptionDNSQuery{ctx: ctx, cancel: cancel, network: &subscriptionDNSNetwork{protectPath: protectPath, platform: platform}}
}

func (q *SubscriptionDNSQuery) Cancel() { q.cancel() }

// Lookup uses sing-box's DNS client/transports only; the caller bootstraps the resolver address.
func (q *SubscriptionDNSQuery) Lookup(serverJSON, domain, strategy string) (string, error) {
	if err := q.ctx.Err(); err != nil {
		return "", err
	}
	registry := nekoboxAndroidDNSTransportRegistry(nil)
	ctx := service.ContextWithDefaultRegistry(q.ctx)
	service.MustRegister[adapter.NetworkManager](ctx, q.network)
	service.MustRegister[option.DNSTransportOptionsRegistry](ctx, registry)
	var options option.DNSServerOptions
	if err := options.UnmarshalJSONContext(ctx, []byte(serverJSON)); err != nil {
		return "", err
	}
	var remote *option.RemoteDNSServerOptions
	switch v := options.Options.(type) {
	case *option.RemoteDNSServerOptions:
		remote = v
	case *option.RemoteTLSDNSServerOptions:
		remote = &v.RemoteDNSServerOptions
	case *option.RemoteHTTPSDNSServerOptions:
		remote = &v.RemoteDNSServerOptions
	default:
		return "", errors.New("unsupported subscription DNS transport")
	}
	// Resolve only with an explicitly bootstrapped IP, with no detour or alternate resolver.
	if _, err := netip.ParseAddr(remote.Server); err != nil {
		return "", errors.New("subscription DNS server must be bootstrapped")
	}
	remote.DialerOptions = option.DialerOptions{}
	domainStrategy, ok := map[string]C.DomainStrategy{"": C.DomainStrategyAsIS, "prefer_ipv4": C.DomainStrategyPreferIPv4, "prefer_ipv6": C.DomainStrategyPreferIPv6, "ipv4_only": C.DomainStrategyIPv4Only, "ipv6_only": C.DomainStrategyIPv6Only}[strategy]
	if !ok {
		return "", errors.New("invalid subscription DNS address family")
	}
	transport, err := registry.CreateDNSTransport(ctx, log.NewNOPFactory().Logger(), "subscription-query", options.Type, options.Options)
	if err != nil {
		return "", err
	}
	defer transport.Close()
	if err = transport.Start(adapter.StartStateStart); err != nil {
		return "", err
	}
	client := dns.NewClient(dns.ClientOptions{Context: ctx, DisableCache: true, Logger: log.NewNOPFactory().Logger()})
	addresses, err := client.Lookup(ctx, transport, domain, adapter.DNSQueryOptions{Strategy: domainStrategy, DisableCache: true}, nil)
	if err != nil {
		return "", err
	}
	result := make([]string, 0, len(addresses))
	for _, address := range addresses {
		result = append(result, address.String())
	}
	return strings.Join(result, "\n"), nil
}

// The transport needs only direct dialing policy, not a second Box or interface monitor.
type subscriptionDNSNetwork struct {
	adapter.NetworkManager
	protectPath string
	platform    SubscriptionDNSPlatform
}

func (n *subscriptionDNSNetwork) InterfaceFinder() control.InterfaceFinder {
	return control.NewDefaultInterfaceFinder()
}
func (n *subscriptionDNSNetwork) DefaultOptions() adapter.NetworkOptions {
	return adapter.NetworkOptions{}
}
func (n *subscriptionDNSNetwork) AutoDetectInterface() bool                { return true }
func (n *subscriptionDNSNetwork) AutoRedirectOutputMarkFunc() control.Func { return nil }
func (n *subscriptionDNSNetwork) AutoRedirectOutputMark() uint32           { return 0 }
func (n *subscriptionDNSNetwork) AutoDetectInterfaceFunc() control.Func {
	return func(_, _ string, raw syscall.RawConn) error {
		return control.Raw(raw, func(fd uintptr) error {
			if n.protectPath != "" {
				err := sendFdToProtect(int(fd), n.protectPath)
				if err != nil && !errors.Is(err, unix.ENOENT) && !errors.Is(err, unix.ECONNREFUSED) {
					return err
				}
			}
			if n.platform != nil {
				return n.platform.AutoDetectInterfaceControl(int32(fd))
			}
			return nil
		})
	}
}
