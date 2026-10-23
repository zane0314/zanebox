package group

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

type chainCache struct {
	adapter.CacheFile
	loads int
}

func (c *chainCache) LoadSelected(string) string { c.loads++; return "cached" }

type chainOutbounds struct {
	adapter.OutboundManager
	members map[string]adapter.Outbound
}

func (m chainOutbounds) Outbound(tag string) (adapter.Outbound, bool) {
	o, ok := m.members[tag]
	return o, ok
}

func TestChainSelectorCacheRestoreIsExplicitlyDisabled(t *testing.T) {
	for _, tc := range []struct {
		name, extra, want string
		loads             int
	}{
		{"ordinary default", "", "cached", 1},
		{"explicit enabled", `,"restore_cache":true`, "cached", 1},
		{"dynamic chain", `,"restore_cache":false`, "blocked", 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var options option.SelectorOutboundOptions
			if err := json.Unmarshal([]byte(`{"outbounds":["cached","blocked"],"default":"blocked"`+tc.extra+`}`), &options); err != nil {
				t.Fatal(err)
			}
			cache := &chainCache{}
			ctx := service.ContextWith[adapter.CacheFile](context.Background(), cache)
			ctx = service.ContextWith[adapter.OutboundManager](ctx, chainOutbounds{members: map[string]adapter.Outbound{"cached": &choiceOutbound{tag: "cached"}, "blocked": &choiceOutbound{tag: "blocked"}}})
			outbound, err := NewSelector(ctx, nil, log.NewNOPFactory().Logger(), "selector", options)
			if err != nil {
				t.Fatal(err)
			}
			selector := outbound.(*Selector)
			if err = selector.Start(); err != nil {
				t.Fatal(err)
			}
			if selector.Now() != tc.want || cache.loads != tc.loads {
				t.Fatalf("selected=%s, cache reads=%d; want %s/%d", selector.Now(), cache.loads, tc.want, tc.loads)
			}
		})
	}
}
