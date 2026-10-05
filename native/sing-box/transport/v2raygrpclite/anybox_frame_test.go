package v2raygrpclite

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"testing"
)

func TestAnyBoxFrameBounds(t *testing.T) {
	data := make([]byte, 16)
	n := binary.PutUvarint(data[6:], ^uint64(0))
	conn := &GunConn{reader: bufio.NewReader(bytes.NewReader(data[:6+n]))}
	if _, err := conn.read(make([]byte, 10)); err == nil {
		t.Fatal("oversized frame accepted")
	}
}
