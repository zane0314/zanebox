package group

import (
	"context"
	"encoding/json"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type managedProbeOutbound struct {
	choiceOutbound
	probes *atomic.Int32
}

func (o *managedProbeOutbound) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	o.probes.Add(1)
	return o.choiceOutbound.DialContext(ctx, network, address)
}
func managedURLTest(t *testing.T, managed bool) (*URLTest, *atomic.Int32) {
	t.Helper()
	var probes atomic.Int32
	a := &managedProbeOutbound{choiceOutbound{tag: "a"}, &probes}
	b := &managedProbeOutbound{choiceOutbound{tag: "b"}, &probes}
	ctx := pause.WithDefaultManager(context.Background())
	ctx = service.ContextWithPtr(ctx, urltest.NewHistoryStorage())
	ctx = service.ContextWith[adapter.OutboundManager](ctx, chainOutbounds{members: map[string]adapter.Outbound{"a": a, "b": b}})
	var options option.URLTestOutboundOptions
	extra := ""
	if managed {
		extra = `,"managed_test":true`
	}
	if err := json.Unmarshal([]byte(`{"outbounds":["a","b"]`+extra+`}`), &options); err != nil {
		t.Fatal(err)
	}
	outbound, err := NewURLTest(ctx, nil, log.NewNOPFactory().Logger(), "parent", options)
	if err != nil {
		t.Fatal(err)
	}
	s := outbound.(*URLTest)
	if err = s.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s, &probes
}
func TestManagedURLTestOnlyCommitsExplicitResults(t *testing.T) {
	s, _ := managedURLTest(t, true)
	s.group.history.StoreURLTestHistory("a", &adapter.URLTestHistory{Delay: 100})
	s.PerformUpdateCheck()
	if s.Now() != "" {
		t.Fatalf("uncommitted history selected %s", s.Now())
	}
	if member, _ := s.group.Select("tcp"); member != nil {
		t.Fatal("uncommitted selection escaped through Select")
	}
	if got := s.ApplyTestResults(map[string]uint16{"a": 100, "b": 200}); got != "a" {
		t.Fatal("explicit commit failed:", got)
	}
	s.group.history.StoreURLTestHistory("b", &adapter.URLTestHistory{Delay: 1})
	s.PerformUpdateCheck()
	if s.Now() != "a" {
		t.Fatal("candidate delay changed committed choice:", s.Now())
	}
	if got := s.ApplyTestResults(map[string]uint16{"a": 100, "b": 1}); got != "b" {
		t.Fatal("second explicit commit failed:", got)
	}
}
func TestManagedURLTestDoesNotScheduleBackgroundRequests(t *testing.T) {
	s, probes := managedURLTest(t, true)
	if err := s.PostStart(); err != nil {
		t.Fatal(err)
	}
	if s.group.started {
		t.Fatal("managed parent started background lifecycle")
	}
	s.group.Touch()
	if s.group.ticker != nil {
		t.Fatal("managed parent started periodic ticker")
	}
	s.InterfaceUpdated(context.Background())
	s.CheckOutbounds()
	if _, err := s.URLTest(context.Background()); err != nil {
		t.Fatal(err)
	}
	time.Sleep(20 * time.Millisecond)
	if probes.Load() != 0 {
		t.Fatalf("managed parent made %d native probes", probes.Load())
	}
}
func TestOrdinaryURLTestStillRespondsToHistoryUpdates(t *testing.T) {
	s, _ := managedURLTest(t, false)
	s.group.history.StoreURLTestHistory("a", &adapter.URLTestHistory{Delay: 100})
	s.PerformUpdateCheck()
	if s.Now() != "a" {
		t.Fatal("ordinary initial choice:", s.Now())
	}
	s.group.history.StoreURLTestHistory("b", &adapter.URLTestHistory{Delay: 1})
	s.PerformUpdateCheck()
	if s.Now() != "b" {
		t.Fatal("ordinary update disabled:", s.Now())
	}
	if err := s.PostStart(); err != nil {
		t.Fatal(err)
	}
	s.group.Touch()
	if !s.group.started || s.group.ticker == nil {
		t.Fatal("ordinary background lifecycle disabled")
	}
}
