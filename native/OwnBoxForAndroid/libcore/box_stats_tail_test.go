package libcore

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/v2rayapi"
	"github.com/sagernet/sing-box/option"
	"testing"
)

type tailTestOutbound struct{ adapter.Outbound }

func (tailTestOutbound) Tag() string { return "proxy" }
func TestQueryStatsDrainsTailAfterClose(t *testing.T) {
	stats := v2rayapi.NewStatsService(option.V2RayStatsServiceOptions{Enabled: true, Outbounds: []string{"proxy"}})
	flow := stats.RoutedFlow(context.Background(), adapter.InboundContext{}, nil, tailTestOutbound{})
	flow.CountReverse(12345)
	box := &BoxInstance{v2api: stats, closeBox: func() error { flow.CountReverse(678); return nil }}
	if err := box.Close(); err != nil {
		t.Fatal(err)
	}
	if got := box.QueryStats("proxy", "downlink"); got != 13023 {
		t.Fatalf("tail=%d, want 13023", got)
	}
	if got := box.QueryStats("proxy", "downlink"); got != 0 {
		t.Fatalf("double counting: %d", got)
	}
}
