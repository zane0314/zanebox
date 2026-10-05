package srs

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"net/netip"
	"testing"
)

func TestAnyBoxIPRanges(t *testing.T) {
	var data bytes.Buffer
	data.WriteByte(1)
	binary.Write(&data, binary.BigEndian, uint64(2))
	for _, addr := range []string{"10.0.0.10", "10.0.0.20", "10.0.0.1", "10.0.0.15"} {
		a := netip.MustParseAddr(addr)
		data.WriteByte(4)
		data.Write(a.AsSlice())
	}
	set, err := readIPSet(bufio.NewReader(&data))
	if err != nil {
		t.Fatal(err)
	}
	for _, addr := range []string{"10.0.0.1", "10.0.0.15", "10.0.0.20"} {
		if !set.Contains(netip.MustParseAddr(addr)) {
			t.Fatal(addr)
		}
	}
	for _, bits := range []byte{33, 255} {
		if _, err := readPrefix(bufio.NewReader(bytes.NewReader([]byte{4, 10, 0, 0, 1, bits}))); err == nil {
			t.Fatal("invalid prefix accepted", bits)
		}
	}
	if _, err := readPrefix(bufio.NewReader(bytes.NewReader([]byte{4, 10, 0, 0, 1, 32}))); err != nil {
		t.Fatal(err)
	}
}
