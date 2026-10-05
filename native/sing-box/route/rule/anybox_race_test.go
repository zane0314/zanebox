package rule

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"sync"
	"testing"
)

func TestAnyBoxRulePublication(t *testing.T) {
	local := &LocalRuleSet{ctx: context.Background()}
	remote := &RemoteRuleSet{ctx: context.Background(), cancel: func() {}}
	var group sync.WaitGroup
	for n := 0; n < 3; n++ {
		group.Add(1)
		go func() {
			defer group.Done()
			for i := 0; i < 200; i++ {
				local.Match(&adapter.InboundContext{})
				local.mergeableRule()
				remote.Match(&adapter.InboundContext{})
				remote.mergeableRule()
			}
		}()
	}
	for i := 0; i < 200; i++ {
		if err := local.reloadRules([]option.HeadlessRule{}); err != nil {
			t.Fatal(err)
		}
		local.Cleanup()
		remote.access.Lock()
		remote.rules = []adapter.HeadlessRule{}
		remote.access.Unlock()
		remote.Cleanup()
	}
	group.Wait()
	local.Close()
	remote.Close()
	reject := &RuleActionReject{Method: C.RuleActionRejectMethodDefault}
	for i := 0; i < 10000; i++ {
		reject.Error(nil)
	}
	if len(reject.dropCounter) != 51 {
		t.Fatal(len(reject.dropCounter))
	}
}
