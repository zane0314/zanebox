package cachefile

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	"net/netip"
	"path/filepath"
	"testing"
)

func TestAnyBoxFakeIPReset(t *testing.T) {
	cache := New(context.Background(), logger.NOP(), option.CacheFileOptions{Path: filepath.Join(t.TempDir(), "cache.db"), StoreFakeIP: true})
	if err := cache.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	defer cache.Close()
	for _, values := range [][]string{nil, {"198.18.0.2"}, {"fd00::2"}, {"198.18.0.2", "fd00::2"}} {
		for _, value := range values {
			if err := cache.FakeIPStore(netip.MustParseAddr(value), "test.example"); err != nil {
				t.Fatal(err)
			}
		}
		if err := cache.FakeIPReset(); err != nil {
			t.Fatal(err)
		}
		for _, value := range values {
			if _, loaded := cache.FakeIPLoad(netip.MustParseAddr(value)); loaded {
				t.Fatal("stale mapping")
			}
		}
	}
}
