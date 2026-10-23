package ninja

import (
	"bytes"
	"encoding/binary"
	"errors"
	M "github.com/sagernet/sing/common/metadata"
	"io"
	"net"
	"testing"
	"time"
)

func TestPacketRejectsDifferentDestination(t *testing.T) {
	_, raw := memoryCore(t)
	packet, e := newCorePacketConn(raw, "aes-128-gcm", "test-password", "test-node", M.ParseSocksaddr("192.0.2.1:53"))
	if e != nil {
		t.Fatal(e)
	}
	if _, e = packet.WriteTo([]byte("data"), M.ParseSocksaddr("192.0.2.2:53").UDPAddr()); e == nil {
		t.Fatal("different destination accepted")
	}
}

func packetMemory(t *testing.T) (*corePacketConn, *coreMemoryConn) {
	t.Helper()
	raw := &coreMemoryConn{input: bytes.NewReader(nil), limit: 7}
	c, e := newCorePacketConn(raw, "aes-128-gcm", "test-password", "test-node", M.ParseSocksaddr("192.0.2.1:53"))
	if e != nil {
		t.Fatal(e)
	}
	return c.(*corePacketConn), raw
}
func packetResponse(salt []byte, payloads ...[]byte) []byte {
	serverSalt := bytes.Repeat([]byte{0x5a}, 16)
	a := peerCipher(serverSalt)
	wire := append([]byte(nil), serverSalt...)
	for i, payload := range payloads {
		var h []byte
		if i == 0 {
			h = make([]byte, 29)
			h[0] = 1
			copy(h[9:25], salt)
			binary.BigEndian.PutUint16(h[25:], uint16(len(payload)+3))
			binary.BigEndian.PutUint16(h[27:], 3)
		} else {
			h = []byte{0, 0, 0, 3}
			binary.BigEndian.PutUint16(h, uint16(len(payload)+3))
		}
		wire = append(wire, peerSeal(a, uint64(2*i), h)...)
		wire = append(wire, peerSeal(a, uint64(2*i+1), append(append([]byte(nil), payload...), 0, 0, 0))...)
	}
	return wire
}
func packetBody(t *testing.T, source M.Socksaddr, data []byte) []byte {
	t.Helper()
	var b bytes.Buffer
	if e := M.SocksaddrSerializer.WriteAddrPort(&b, source); e != nil {
		t.Fatal(e)
	}
	b.Write(data)
	return b.Bytes()
}
func TestPacketPreservesDatagrams(t *testing.T) {
	c, raw := packetMemory(t)
	if raw.output.Len() != 0 {
		t.Fatal("UDP sent an empty handshake")
	}
	if _, e := c.WriteTo(make([]byte, 70000), c.destination); e == nil || raw.output.Len() != 0 {
		t.Fatal("oversized first datagram accepted")
	}
	packets := [][]byte{bytes.Repeat([]byte{'a'}, 40000), bytes.Repeat([]byte{'b'}, 50000), {}, []byte("last")}
	for _, p := range packets {
		if n, e := c.WriteTo(p, c.destination); n != len(p) || e != nil {
			t.Fatalf("write datagram %d %v", n, e)
		}
	}
	r := bytes.NewReader(raw.output.Bytes())
	salt := make([]byte, 16)
	io.ReadFull(r, salt)
	r.Seek(16, io.SeekCurrent)
	a := peerCipher(salt)
	h, e := peerOpen(r, a, 0, 12)
	if e != nil {
		t.Fatal(e)
	}
	if h[1] != 2 {
		t.Fatal("not native UDP")
	}
	payload, e := peerOpen(r, a, 1, int(binary.BigEndian.Uint16(h[10:])))
	if e != nil {
		t.Fatal(e)
	}
	first := bytes.NewReader(payload)
	dest, e := M.SocksaddrSerializer.ReadAddrPort(first)
	if e != nil || dest != c.destination {
		t.Fatalf("destination %v %v", dest, e)
	}
	var pad uint16
	binary.Read(first, binary.BigEndian, &pad)
	first.Seek(int64(pad), io.SeekCurrent)
	got, e := io.ReadAll(first)
	if e != nil || !bytes.Equal(got, packets[0]) {
		t.Fatal("first datagram split")
	}
	for i := 1; i < len(packets); i++ {
		h, e := peerOpen(r, a, uint64(i*2), 4)
		if e != nil {
			t.Fatal(e)
		}
		total, pad := int(binary.BigEndian.Uint16(h)), int(binary.BigEndian.Uint16(h[2:]))
		body, e := peerOpen(r, a, uint64(i*2+1), total)
		if e != nil {
			t.Fatal(e)
		}
		if !bytes.Equal(body[:total-pad], packets[i]) {
			t.Fatal("datagram changed or address prefix added")
		}
	}
	if r.Len() != 0 {
		t.Fatal("datagram framing changed")
	}
	if _, e = c.WriteTo(make([]byte, 65535), c.destination); e == nil {
		t.Fatal("oversized subsequent datagram accepted")
	}
	source := M.ParseSocksaddr("192.0.2.1:53")
	raw.input = bytes.NewReader(packetResponse(c.salt, packetBody(t, source, packets[0]), packetBody(t, source, packets[1]), packetBody(t, source, nil)))
	b := make([]byte, 65535)
	for _, want := range packets[:3] {
		n, addr, e := c.ReadFrom(b)
		if e != nil || !bytes.Equal(b[:n], want) || M.SocksaddrFromNet(addr) != source {
			t.Fatalf("read packet %d %v %v", n, addr, e)
		}
	}
}
func TestPacketShortBufferDropsOnlyOneDatagram(t *testing.T) {
	c, raw := packetMemory(t)
	source := M.ParseSocksaddr("192.0.2.1:53")
	raw.input = bytes.NewReader(packetResponse(c.salt, packetBody(t, source, []byte("long packet")), packetBody(t, source, []byte("next"))))
	if n, _, e := c.ReadFrom(make([]byte, 2)); n != 2 || !errors.Is(e, io.ErrShortBuffer) {
		t.Fatalf("short buffer %d %v", n, e)
	}
	b := make([]byte, 20)
	n, _, e := c.ReadFrom(b)
	if e != nil || string(b[:n]) != "next" {
		t.Fatalf("next datagram %q %v", b[:n], e)
	}
}
func TestPacketMalformedResponse(t *testing.T) {
	for _, kind := range []string{"source", "empty-source", "header-auth", "body-auth", "missing-header", "missing-body", "length"} {
		t.Run(kind, func(t *testing.T) {
			c, raw := packetMemory(t)
			wire := packetResponse(c.salt, packetBody(t, M.ParseSocksaddr("192.0.2.1:53"), []byte("data")))
			switch kind {
			case "empty-source":
				wire = packetResponse(c.salt, []byte{3, 0, 0, 53})
			case "source":
				wire = packetResponse(c.salt, []byte{1, 192})
			case "header-auth":
				wire[20] ^= 1
			case "body-auth":
				wire[len(wire)-1] ^= 1
			case "missing-header":
				wire = wire[:16]
			case "missing-body":
				wire = wire[:16+29+16]
			case "length":
				salt := wire[:16]
				a := peerCipher(salt)
				h := make([]byte, 29)
				h[0] = 1
				copy(h[9:25], c.salt)
				binary.BigEndian.PutUint16(h[27:], 1)
				wire = append(append([]byte(nil), salt...), peerSeal(a, 0, h)...)
			}
			raw.input = bytes.NewReader(wire)
			if _, _, e := c.ReadFrom(make([]byte, 100)); e == nil {
				t.Fatal("invalid packet accepted")
			}
			if !raw.closed {
				t.Fatal("malformed packet stream left open")
			}
		})
	}
}
func TestPacketCloseInterruptsRead(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	packet, e := newCorePacketConn(a, "aes-128-gcm", "test-password", "test-node", M.ParseSocksaddr("192.0.2.1:53"))
	if e != nil {
		t.Fatal(e)
	}
	done := make(chan error, 1)
	go func() { _, _, e := packet.ReadFrom(make([]byte, 1)); done <- e }()
	packet.Close()
	select {
	case e := <-done:
		if e == nil {
			t.Fatal("close ignored")
		}
	case <-time.After(time.Second):
		t.Fatal("packet read remained blocked")
	}
}
