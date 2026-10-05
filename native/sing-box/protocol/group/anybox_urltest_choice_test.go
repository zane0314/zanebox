package group

import (
	"context"
	"errors"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	"net"
	"sync"
	"testing"
)

type choiceOutbound struct {
	adapter.Outbound
	tag string
}

func (c *choiceOutbound) Tag() string       { return c.tag }
func (c *choiceOutbound) Network() []string { return []string{"tcp", "udp"} }
func (c *choiceOutbound) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("fixture")
}
func (c *choiceOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("fixture")
}
func TestAnyBoxURLTestChoiceRace(t *testing.T) {
	a, b := &choiceOutbound{tag: "a"}, &choiceOutbound{tag: "b"}
	g := &URLTestGroup{outbounds: []adapter.Outbound{a, b}, history: urltest.NewHistoryStorage(), interruptGroup: interrupt.NewGroup()}
	s := &URLTest{group: g, logger: log.NewNOPFactory().Logger()}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				g.history.StoreURLTestHistory("a", &adapter.URLTestHistory{Delay: uint16(1 + j%2*100)})
				g.history.StoreURLTestHistory("b", &adapter.URLTestHistory{Delay: uint16(101 - j%2*100)})
				g.performUpdateCheck()
				s.Now()
				g.Select("tcp")
				_, _ = s.DialContext(context.Background(), "tcp", M.Socksaddr{})
				_, _ = s.ListenPacket(context.Background(), M.Socksaddr{})
			}
		}()
	}
	wg.Wait()
	g.history.StoreURLTestHistory("a", &adapter.URLTestHistory{Delay: 100})
	g.history.StoreURLTestHistory("b", &adapter.URLTestHistory{Delay: 1})
	g.performUpdateCheck()
	if s.Now() != "b" {
		t.Fatal("updated choice not visible:", s.Now())
	}
}
