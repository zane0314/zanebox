package libcore

import (
	"context"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns/transport/fakeip"
	"github.com/sagernet/sing-box/experimental/cachefile"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/service"
)

// Child exits without Store.Close or CacheFile.Close, as Android process death does.
func TestAnyBoxFakeIPCrashChild(t *testing.T) {
	path := os.Getenv("ANYBOX_FAKEIP_CRASH_DB")
	if path == "" {
		t.Skip("subprocess helper")
	}
	ctx := service.ContextWithDefaultRegistry(context.Background())
	cache := cachefile.New(ctx, logger.NOP(), option.CacheFileOptions{Path: path, StoreFakeIP: true})
	if err := cache.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	service.MustRegister[adapter.CacheFile](ctx, cache)
	store := fakeip.NewStore(ctx, logger.NOP(), netip.MustParsePrefix("198.18.0.0/24"), netip.MustParsePrefix("fd00::/120"))
	if err := store.Start(); err != nil {
		t.Fatal(err)
	}
	first := netip.MustParseAddr("198.18.0.2")
	if os.Getenv("ANYBOX_FAKEIP_CRASH_PHASE") == "reset" {
		if err := store.Reset(); err != nil {
			t.Fatal(err)
		}
	}
	if os.Getenv("ANYBOX_FAKEIP_CRASH_PHASE") == "create" || os.Getenv("ANYBOX_FAKEIP_CRASH_PHASE") == "reset" {
		address, err := store.Create("first.example", false)
		if err != nil || address != first {
			t.Fatalf("initial allocation %v: %v", address, err)
		}
		if address, err := store.Create("v6.example", true); err != nil || address != netip.MustParseAddr("fd00::2") {
			t.Fatalf("IPv6 allocation %v: %v", address, err)
		}
	} else {
		if name, ok := store.Lookup(netip.MustParseAddr("fd00::2")); !ok || name != "v6.example" {
			t.Fatal("IPv6 mapping lost")
		}
		if name, ok := store.Lookup(first); !ok || name != "first.example" {
			t.Fatalf("mapping lost after ungraceful exit: %q %v", name, ok)
		}
		if os.Getenv("ANYBOX_FAKEIP_CRASH_PHASE") == "allocate" {
			address, err := store.Create("second.example", false)
			if err != nil || address == first {
				t.Fatalf("cursor reused durable mapping: %v %v", address, err)
			}
			if name, ok := store.Lookup(first); !ok || name != "first.example" {
				t.Fatal("first mapping overwritten")
			}
		}
	}
	os.Exit(0)
}

func TestAnyBoxFakeIPPersistsAcrossProcessDeath(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cache.db")
	for _, phase := range []string{"create", "load", "load", "allocate", "reset", "load", "allocate"} {
		command := exec.Command(os.Args[0], "-test.run=^TestAnyBoxFakeIPCrashChild$")
		command.Env = append(os.Environ(), "ANYBOX_FAKEIP_CRASH_DB="+path, "ANYBOX_FAKEIP_CRASH_PHASE="+phase)
		if output, err := command.CombinedOutput(); err != nil {
			t.Fatalf("phase %s: %v\n%s", phase, err, output)
		}
	}
}
