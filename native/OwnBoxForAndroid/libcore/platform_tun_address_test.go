package libcore

import (
	"net/netip"
	"slices"
	"testing"

	tun "github.com/sagernet/sing-tun"
)

func TestAnyBoxPlatformTunAddresses(t *testing.T) {
	a, b := &boxPlatformInterfaceWrapper{}, &boxPlatformInterfaceWrapper{}
	if len(a.MyInterfaceAddress()) != 0 {
		t.Fatal("unopened box has addresses")
	}
	options := &tun.Options{
		Inet4Address: []netip.Prefix{netip.MustParsePrefix("172.19.0.1/30")},
		Inet6Address: []netip.Prefix{netip.MustParsePrefix("fdfe:dcba:9876::1/126")},
	}
	a.myTunAddress = myTunAddress(options)
	b.myTunAddress = myTunAddress(&tun.Options{Inet4Address: []netip.Prefix{netip.MustParsePrefix("172.20.0.1/30")}})
	want := []netip.Addr{netip.MustParseAddr("172.19.0.1"), netip.MustParseAddr("fdfe:dcba:9876::1")}
	if !slices.Equal(a.MyInterfaceAddress(), want) {
		t.Fatalf("own addresses = %v, want %v", a.MyInterfaceAddress(), want)
	}
	for _, value := range []string{"172.19.0.0", "172.19.0.2", "fdfe:dcba:9876::", "fdfe:dcba:9876::2", "172.20.0.1"} {
		if slices.Contains(a.MyInterfaceAddress(), netip.MustParseAddr(value)) {
			t.Fatalf("non-own address %s included", value)
		}
	}
	if !slices.Equal(b.MyInterfaceAddress(), []netip.Addr{netip.MustParseAddr("172.20.0.1")}) {
		t.Fatalf("second box addresses = %v", b.MyInterfaceAddress())
	}
	options.Inet4Address[0] = netip.MustParsePrefix("192.0.2.1/24")
	if !slices.Equal(a.MyInterfaceAddress(), want) {
		t.Fatal("cached addresses alias tun options")
	}
	if len(myTunAddress(&tun.Options{})) != 0 {
		t.Fatal("empty options produced addresses")
	}
}
