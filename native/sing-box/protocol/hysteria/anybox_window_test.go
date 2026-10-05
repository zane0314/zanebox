package hysteria

import (
	"github.com/sagernet/sing-box/option"
	corehysteria "github.com/sagernet/sing-quic/hysteria"
	"reflect"
	"testing"
)

func TestAnyBoxLegacyWindowMeaning(t *testing.T) {
	// Hysteria v1 uses "conn" to mean an individual proxied stream.
	out := buildOutboundQUICOptions(option.HysteriaOutboundOptions{ReceiveWindowConn: 16 << 20, ReceiveWindow: 64 << 20})
	if out.StreamReceiveWindow != 16<<20 || out.ConnectionReceiveWindow != 64<<20 {
		t.Fatalf("outbound windows reversed: stream=%d connection=%d", out.StreamReceiveWindow, out.ConnectionReceiveWindow)
	}
	in := buildInboundQUICOptions(option.HysteriaInboundOptions{ReceiveWindowConn: 16 << 20, ReceiveWindowClient: 64 << 20})
	if in.StreamReceiveWindow != 16<<20 || in.ConnectionReceiveWindow != 64<<20 {
		t.Fatalf("inbound windows reversed: stream=%d connection=%d", in.StreamReceiveWindow, in.ConnectionReceiveWindow)
	}
}

func TestAnyBoxModernWindowsTakePrecedence(t *testing.T) {
	opts := option.HysteriaOutboundOptions{ReceiveWindowConn: 1, ReceiveWindow: 2}
	if err := opts.StreamReceiveWindow.UnmarshalJSON([]byte("16777216")); err != nil {
		t.Fatal(err)
	}
	if err := opts.ConnectionReceiveWindow.UnmarshalJSON([]byte("67108864")); err != nil {
		t.Fatal(err)
	}
	out := buildOutboundQUICOptions(opts)
	if out.StreamReceiveWindow != 16<<20 || out.ConnectionReceiveWindow != 64<<20 {
		t.Fatal("legacy values override explicit modern fields")
	}
	in := buildInboundQUICOptions(option.HysteriaInboundOptions{QUICOptions: opts.QUICOptions, ReceiveWindowConn: 1, ReceiveWindowClient: 2})
	if in.StreamReceiveWindow != 16<<20 || in.ConnectionReceiveWindow != 64<<20 {
		t.Fatal("inbound legacy values override modern fields")
	}
	defaults := buildOutboundQUICOptions(option.HysteriaOutboundOptions{})
	if defaults.StreamReceiveWindow != 0 || defaults.ConnectionReceiveWindow != 0 {
		t.Fatal("unset fields no longer defer to QUIC defaults")
	}
}

func TestAnyBoxMixedPortsCoreCompatibility(t *testing.T) {
	ports, err := corehysteria.ParsePorts([]string{"443:443", "10000:10002"})
	if err != nil || !reflect.DeepEqual(ports, []uint16{443, 10000, 10001, 10002}) {
		t.Fatalf("exported port ranges incompatible: %v %v", ports, err)
	}
}
