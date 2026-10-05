package fakeip

import (
	"context"
	"errors"
	"github.com/sagernet/sing/common/logger"
	"net/netip"
	"testing"
)

type failingStorage struct{ *MemoryStorage }

func (f failingStorage) FakeIPStore(netip.Addr, string) error {
	return errors.New("injected disk failure")
}
func TestAnyBoxRangeAndWriteFailure(t *testing.T) {
	for _, cidr := range []string{"198.18.0.0/31", "198.18.0.0/32", "fc00::/127", "fc00::/128"} {
		p := netip.MustParsePrefix(cidr)
		v4, v6 := netip.Prefix{}, netip.Prefix{}
		if p.Addr().Is4() {
			v4 = p
		} else {
			v6 = p
		}
		s := NewStore(context.Background(), logger.NOP(), v4, v6)
		if err := s.Start(); err == nil {
			t.Errorf("accepted insufficient prefix %s", cidr)
		}
	}
	s := NewStore(context.Background(), logger.NOP(), netip.MustParsePrefix("198.18.0.9/24"), netip.Prefix{})
	if err := s.Start(); err != nil {
		t.Fatal(err)
	}
	a, err := s.Create("one.example", false)
	if err != nil || a != netip.MustParseAddr("198.18.0.2") {
		t.Fatalf("masked allocation %v %v", a, err)
	}
	s.storage = failingStorage{NewMemoryStorage()}
	if _, err = s.Create("disk.example", false); err == nil {
		t.Fatal("persist failure was reported as successful address")
	}
}
