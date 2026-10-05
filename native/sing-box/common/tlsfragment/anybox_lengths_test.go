package tf

import (
	"encoding/binary"
	"testing"
)

func TestAnyBoxSNI(t *testing.T) {
	for _, name := range []string{"example.com", "", ".example.com", "a..com", "example.com."} {
		ext := make([]byte, 11+len(name))
		binary.BigEndian.PutUint16(ext, uint16(len(ext)-2))
		binary.BigEndian.PutUint16(ext[4:], uint16(len(ext)-6))
		binary.BigEndian.PutUint16(ext[6:], uint16(len(ext)-8))
		binary.BigEndian.PutUint16(ext[9:], uint16(len(name)))
		copy(ext[11:], name)
		got := indexTLSServerNameFromExtensions(ext)
		if (got != nil) != (name == "example.com") {
			t.Fatalf("name %q result %v", name, got)
		}
		if name == "example.com" {
			ext[10]++
			if indexTLSServerNameFromExtensions(ext) != nil {
				t.Fatal("accepted SNI length mismatch")
			}
		}
	}
}
