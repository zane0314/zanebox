package ja3

import (
	"encoding/binary"
	"testing"
)

func TestAnyBoxExtensionLengths(t *testing.T) {
	for _, tc := range []struct {
		kind  uint16
		body  []byte
		valid bool
	}{
		{43, []byte{2, 3}, false}, {43, []byte{1, 3}, false}, {43, []byte{2, 3, 4}, true},
		{13, []byte{0, 2, 4}, false}, {13, []byte{0, 1, 4}, false}, {13, []byte{0, 2, 4, 3}, true}, {10, []byte{0, 1, 4}, false},
	} {
		ext := make([]byte, 6+len(tc.body))
		binary.BigEndian.PutUint16(ext, uint16(len(ext)-2))
		binary.BigEndian.PutUint16(ext[2:], tc.kind)
		binary.BigEndian.PutUint16(ext[4:], uint16(len(tc.body)))
		copy(ext[6:], tc.body)
		err := new(ClientHello).parseExtensions(ext)
		if (err == nil) != tc.valid {
			t.Fatalf("kind %d body %x err %v", tc.kind, tc.body, err)
		}
	}
}
