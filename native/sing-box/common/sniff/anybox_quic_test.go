package sniff

import (
	"context"
	"crypto"
	"crypto/aes"
	"encoding/binary"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/sniff/internal/qtls"
	"golang.org/x/crypto/hkdf"
	"testing"
)

// Authenticated local packets exercise decryption and the actual frame parser.
func anyBoxInitial(body []byte) []byte {
	dcid := []byte{1, 2, 3, 4, 5, 6, 7, 8}
	initial := hkdf.Extract(crypto.SHA256.New, dcid, qtls.SaltV1)
	secret := qtls.HKDFExpandLabel(crypto.SHA256, initial, nil, "client in", 32)
	key := qtls.HKDFExpandLabel(crypto.SHA256, secret, nil, "quic key", 16)
	iv := qtls.HKDFExpandLabel(crypto.SHA256, secret, nil, "quic iv", 12)
	cipher := qtls.AEADAESGCMTLS13(key, iv)
	for len(body) < 4 {
		body = append(body, 0)
	}
	header := append([]byte{0xc3, 0, 0, 0, 1, 8}, dcid...)
	header = append(header, 0, 0)
	size := 4 + len(body) + cipher.Overhead()
	header = append(header, byte(size>>8)|0x40, byte(size))
	pn := len(header)
	header = append(header, 0, 0, 0, 0)
	packet := append(header, cipher.Seal(nil, make([]byte, 8), body, header)...)
	hp := qtls.HKDFExpandLabel(crypto.SHA256, secret, nil, "quic hp", 16)
	block, _ := aes.NewCipher(hp)
	mask := make([]byte, 16)
	block.Encrypt(mask, packet[pn+4:pn+20])
	packet[0] ^= mask[0] & 0x0f
	for i := 0; i < 4; i++ {
		packet[pn+i] ^= mask[i+1]
	}
	return packet
}
func TestAnyBoxQUICBounds(t *testing.T) {
	for _, body := range [][]byte{{6, 0, 0, 0}, {6, 0, 63, 1}, {6, 0, 0x80, 1, 0, 0, 1}} {
		if err := QUICClientHello(context.Background(), new(adapter.InboundContext), anyBoxInitial(body)); err == nil {
			t.Fatalf("accepted incomplete frame %x", body)
		}
	}
	hello := make([]byte, 45)
	hello[0] = 1
	hello[3] = 41
	hello[4] = 3
	hello[5] = 3
	binary.BigEndian.PutUint16(hello[39:41], 2)
	hello[41] = 0x13
	hello[42] = 1
	hello[43] = 1
	metadata := new(adapter.InboundContext)
	first := append([]byte{6, 0, 20}, hello[:20]...)
	if QUICClientHello(context.Background(), metadata, anyBoxInitial(first)) == nil {
		t.Fatal("incomplete hello accepted")
	}
	second := append([]byte{6, 20, 25}, hello[20:]...)
	if err := QUICClientHello(context.Background(), metadata, anyBoxInitial(second)); err != nil {
		t.Fatal(err)
	}
}
