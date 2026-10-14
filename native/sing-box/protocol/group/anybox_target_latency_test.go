package group

import (
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
)

type networkOutbound struct {
	choiceOutbound
	networks []string
}

func (n *networkOutbound) Network() []string { return n.networks }

func newTargetGroup(outbounds ...adapter.Outbound) (*URLTest, *URLTestGroup) {
	g := &URLTestGroup{outbounds: outbounds, history: urltest.NewHistoryStorage(), interruptGroup: interrupt.NewGroup(), tolerance: 60000}
	return &URLTest{group: g, logger: log.NewNOPFactory().Logger()}, g
}

func TestApplyTestResultsPicksStrictFastestIgnoringTolerance(t *testing.T) {
	a := &networkOutbound{choiceOutbound{tag: "a"}, []string{"tcp", "udp"}}
	b := &networkOutbound{choiceOutbound{tag: "b"}, []string{"tcp", "udp"}}
	s, g := newTargetGroup(a, b)
	g.history.StoreURLTestHistory("b", &adapter.URLTestHistory{Delay: 40})
	g.performUpdateCheck()
	if s.Now() != "b" {
		t.Fatal("setup choice:", s.Now())
	}
	if got := s.ApplyTestResults(map[string]uint16{"a": 40, "b": 500}); got != "a" {
		t.Fatal("tolerance leaked into choice:", got)
	}
	if g.selectedOutboundTCP.Load() != adapter.Outbound(a) || g.selectedOutboundUDP.Load() != adapter.Outbound(a) {
		t.Fatal("tcp/udp choice not updated")
	}
	if h := g.history.LoadURLTestHistory("b"); h == nil || h.Delay != 500 {
		t.Fatal("history not refreshed for b:", h)
	}
}

func TestApplyTestResultsFailureAndUnknownMembers(t *testing.T) {
	a := &networkOutbound{choiceOutbound{tag: "a"}, []string{"tcp", "udp"}}
	b := &networkOutbound{choiceOutbound{tag: "b"}, []string{"tcp", "udp"}}
	s, g := newTargetGroup(a, b)
	g.selectedOutboundTCP.Store(adapter.Outbound(b))
	g.selectedOutboundUDP.Store(adapter.Outbound(b))
	g.history.StoreURLTestHistory("a", &adapter.URLTestHistory{Delay: 10})
	if got := s.ApplyTestResults(map[string]uint16{"a": 0, "b": 0, "ghost": 1}); got != "" {
		t.Fatal("all failed must not choose:", got)
	}
	if s.Now() != "b" {
		t.Fatal("choice changed after all failed:", s.Now())
	}
	if g.history.LoadURLTestHistory("a") != nil {
		t.Fatal("failed member history kept")
	}
	if got := s.ApplyTestResults(map[string]uint16{"ghost": 1}); got != "" || s.Now() != "b" {
		t.Fatal("unknown member affected the group:", got, s.Now())
	}
}

func TestApplyTestResultsRespectsNetworkSupport(t *testing.T) {
	tcpOnly := &networkOutbound{choiceOutbound{tag: "tcpOnly"}, []string{"tcp"}}
	both := &networkOutbound{choiceOutbound{tag: "both"}, []string{"tcp", "udp"}}
	_, g := newTargetGroup(tcpOnly, both)
	s := &URLTest{group: g, logger: log.NewNOPFactory().Logger()}
	if got := s.ApplyTestResults(map[string]uint16{"tcpOnly": 10, "both": 90}); got != "tcpOnly" {
		t.Fatal("tcp choice:", got)
	}
	if g.selectedOutboundTCP.Load() != adapter.Outbound(tcpOnly) {
		t.Fatal("tcp must use the fastest")
	}
	if g.selectedOutboundUDP.Load() != adapter.Outbound(both) {
		t.Fatal("udp must skip the tcp-only member")
	}
}
