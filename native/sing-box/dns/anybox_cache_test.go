package dns

import (
	"context"
	"errors"
	D "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"testing"
)

type anyboxCacheTransport struct{ adapter.DNSTransport }

func (anyboxCacheTransport) Tag() string { return "test" }
func TestAnyBoxCacheIsolation(t *testing.T) {
	ctx := context.Background()
	c := NewClient(ClientOptions{Context: ctx})
	transport := anyboxCacheTransport{}
	message := new(D.Msg)
	message.SetQuestion("example.org.", D.TypeHTTPS)
	defaults := adapter.DNSQueryOptions{}
	a := c.newCacheKey(transport, message.Question[0], message, defaults)
	ttl := uint32(10)
	for _, options := range []adapter.DNSQueryOptions{{Strategy: C.DomainStrategyIPv4Only}, {RewriteTTL: &ttl}} {
		b := c.newCacheKey(transport, message.Question[0], message, options)
		if a == b || a.persistentName() == b.persistentName() {
			t.Fatal("different response options share cache")
		}
	}
	response := new(D.Msg)
	response.SetReply(message)
	c.storeCache(a, response, 60)
	checker := func(*D.Msg) bool { return false }
	_, _, _, err := c.beginExchange(ctx, transport, message, defaults, checker, true)
	if !errors.Is(err, ErrResponseRejectedCached) {
		t.Fatalf("cached exchange bypassed checker: %v", err)
	}
	_, err = c.questionCache(ctx, transport, message, defaults, checker)
	if !errors.Is(err, ErrResponseRejectedCached) {
		t.Fatalf("cached lookup bypassed checker: %v", err)
	}
}
