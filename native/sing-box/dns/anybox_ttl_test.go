package dns

import (
	D "github.com/miekg/dns"
	"testing"
)

func TestAnyBoxZeroTTL(t *testing.T) {
	for _, ttls := range [][]uint32{{0, 60}, {60, 0}, {60, 20}, {20, 60}} {
		msg := new(D.Msg)
		for _, ttl := range ttls {
			msg.Answer = append(msg.Answer, &D.A{Hdr: D.RR_Header{Name: "test.", Rrtype: D.TypeA, Ttl: ttl}})
		}
		want := min(ttls[0], ttls[1])
		if got := computeTimeToLive(msg); got != want {
			t.Fatalf("%v got %d want %d", ttls, got, want)
		}
	}
}
