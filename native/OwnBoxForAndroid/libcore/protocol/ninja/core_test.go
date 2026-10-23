package ninja

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"fmt"
	M "github.com/sagernet/sing/common/metadata"
	"io"
	"lukechampine.com/blake3"
	"net"
	"strings"
	"testing"
	"time"
)

func TestCoreRejectsUnsupportedMethod(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	if _, err := newCoreConn(a, "unknown", "test-password", "test-node", M.ParseSocksaddr("example.com:443")); err == nil {
		t.Fatal("unsupported method accepted")
	}
}

// The peer derives keys and constructs records independently from coreConn.
func peerKey(context string, material []byte) []byte {
	key := make([]byte, 16)
	blake3.DeriveKey(key, context, material)
	return key
}
func peerCipher(salt []byte) cipher.AEAD {
	psk := peerKey("de6dbaddc4e0dbaaeb65778b44e6fdac", []byte("test-password"))
	block, _ := aes.NewCipher(peerKey("52386811679ea908c4a2b02f367c472c", append(psk, salt...)))
	a, _ := cipher.NewGCM(block)
	return a
}
func peerSeal(a cipher.AEAD, counter uint64, data []byte) []byte {
	nonce := make([]byte, 12)
	binary.LittleEndian.PutUint64(nonce, counter)
	return a.Seal(nil, nonce, data, nil)
}
func peerOpen(r io.Reader, a cipher.AEAD, counter uint64, size int) ([]byte, error) {
	data := make([]byte, size+16)
	if _, e := io.ReadFull(r, data); e != nil {
		return nil, e
	}
	nonce := make([]byte, 12)
	binary.LittleEndian.PutUint64(nonce, counter)
	return a.Open(nil, nonce, data, nil)
}
func peerResponse(salt []byte, firstHeader func([]byte), frames int) []byte {
	serverSalt := bytes.Repeat([]byte{0x5a}, 16)
	a := peerCipher(serverSalt)
	h := make([]byte, 29)
	h[0] = 1
	copy(h[9:25], salt)
	binary.BigEndian.PutUint16(h[25:], 4)
	binary.BigEndian.PutUint16(h[27:], 1)
	if firstHeader != nil {
		firstHeader(h)
	}
	wire := append(serverSalt, peerSeal(a, 0, h)...)
	wire = append(wire, peerSeal(a, 1, []byte{'o', 'k', '!', 0})...)
	for i := 0; i < frames; i++ {
		wire = append(wire, peerSeal(a, uint64(2+i*2), []byte{0, 2, 0, 1})...)
		wire = append(wire, peerSeal(a, uint64(3+i*2), []byte{'x', 0})...)
	}
	return wire
}

type coreMemoryConn struct {
	net.Conn
	input        *bytes.Reader
	output       bytes.Buffer
	limit        int
	closed       bool
	writeFailure error
}

func (c *coreMemoryConn) Read(p []byte) (int, error) {
	if c.closed {
		return 0, net.ErrClosed
	}
	return c.input.Read(p[:min(len(p), 3)])
}
func (c *coreMemoryConn) Write(p []byte) (int, error) {
	if c.closed {
		return 0, net.ErrClosed
	}
	if c.writeFailure != nil {
		return 0, c.writeFailure
	}
	return c.output.Write(p[:min(len(p), c.limit)])
}
func (c *coreMemoryConn) Close() error { c.closed = true; return nil }
func memoryCore(t *testing.T) (*coreConn, *coreMemoryConn) {
	t.Helper()
	raw := &coreMemoryConn{input: bytes.NewReader(nil), limit: 7}
	conn, e := newCoreConn(raw, "aes-128-gcm", "test-password", "test-node", M.ParseSocksaddr("example.com:443"))
	if e != nil {
		t.Fatal(e)
	}
	return conn.(*coreConn), raw
}

func TestCoreWireAndNonceCarry(t *testing.T) {
	c, raw := memoryCore(t)
	data := bytes.Repeat([]byte("payload"), 6000)
	n, e := c.Write(data)
	if e != nil || n != len(data) {
		t.Fatalf("write %d %v", n, e)
	}
	// Another 130 frames exercises carry into nonce[1].
	for range 130 {
		if _, e = c.Write([]byte("a")); e != nil {
			t.Fatal(e)
		}
	}
	r := bytes.NewReader(raw.output.Bytes())
	salt := make([]byte, 16)
	io.ReadFull(r, salt)
	auth := make([]byte, 16)
	io.ReadFull(r, auth)
	node := peerKey("de6dbaddc4e0dbaaeb65778b44e6fdac", []byte("test-node"))
	block, _ := aes.NewCipher(peerKey("e14d197ae519bdd2d9a75ae94df7e208", append(node, salt...)))
	sum := blake3.Sum512(peerKey("de6dbaddc4e0dbaaeb65778b44e6fdac", []byte("test-password")))
	wantAuth := make([]byte, 16)
	block.Encrypt(wantAuth, sum[:16])
	if !bytes.Equal(auth, wantAuth) {
		t.Fatal("bad authentication hash")
	}
	a := peerCipher(salt)
	header, e := peerOpen(r, a, 0, 12)
	if e != nil {
		t.Fatal(e)
	}
	if header[0] != 0 || header[1] != 1 || abs(time.Now().UnixMilli()-int64(binary.BigEndian.Uint64(header[2:10]))) > 2000 {
		t.Fatal("bad first header")
	}
	payload, e := peerOpen(r, a, 1, int(binary.BigEndian.Uint16(header[10:])))
	if e != nil {
		t.Fatal(e)
	}
	addr, e := M.SocksaddrSerializer.ReadAddrPort(bytes.NewReader(payload))
	if e != nil || addr.String() != "example.com:443" {
		t.Fatalf("address %v %v", addr, e)
	}
	offset := 1 + 1 + len("example.com") + 2
	pad := int(binary.BigEndian.Uint16(payload[offset:]))
	if pad < 1 || pad > 900 {
		t.Fatal("bad initial padding")
	}
	got := append([]byte(nil), payload[offset+2+pad:]...)
	counter := uint64(2)
	for r.Len() > 0 {
		h, e := peerOpen(r, a, counter, 4)
		if e != nil {
			t.Fatal(e)
		}
		counter++
		total, pad := int(binary.BigEndian.Uint16(h)), int(binary.BigEndian.Uint16(h[2:]))
		if pad < 1 || pad > 900 || total-pad > 0x3fff {
			t.Fatal("invalid chunk dimensions")
		}
		body, e := peerOpen(r, a, counter, total)
		if e != nil {
			t.Fatal(e)
		}
		counter++
		got = append(got, body[:total-pad]...)
	}
	if !bytes.Equal(got, append(data, bytes.Repeat([]byte{'a'}, 130)...)) {
		t.Fatal("outbound data changed")
	}
	raw.input = bytes.NewReader(peerResponse(salt, nil, 130))
	got, e = io.ReadAll(c)
	if e != nil {
		t.Fatal(e)
	}
	if string(got) != "ok!"+strings.Repeat("x", 130) {
		t.Fatal("inbound data changed")
	}
}
func abs(x int64) int64 {
	if x < 0 {
		return -x
	}
	return x
}

func TestCoreRejectsMalformedResponse(t *testing.T) {
	for _, kind := range []string{"salt", "type", "length", "header-auth", "payload-auth", "truncate-salt", "truncate-header", "missing-header", "missing-data", "truncate-data", "chunk-length", "chunk-auth"} {
		t.Run(kind, func(t *testing.T) {
			c, raw := memoryCore(t)
			wire := peerResponse(c.salt, func(h []byte) {
				switch kind {
				case "salt":
					h[9] ^= 1
				case "type":
					h[0] = 2
				case "length":
					h[27] = 255
				}
			}, 0)
			switch kind {
			case "header-auth":
				wire[20] ^= 1
			case "payload-auth":
				wire[len(wire)-1] ^= 1
			case "truncate-salt":
				wire = wire[:8]
			case "missing-header":
				wire = wire[:16]
			case "missing-data":
				wire = wire[:16+29+16]
			case "truncate-header":
				wire = wire[:20]
			case "truncate-data":
				wire = wire[:len(wire)-1]
			case "chunk-length", "chunk-auth":
				a := peerCipher(bytes.Repeat([]byte{0x5a}, 16))
				h := []byte{0, 1, 0, 2}
				if kind == "chunk-auth" {
					h = []byte{0, 1, 0, 0}
				}
				chunk := peerSeal(a, 2, h)
				if kind == "chunk-auth" {
					chunk[0] ^= 1
				}
				wire = append(wire, chunk...)
			}
			raw.input = bytes.NewReader(wire)
			_, e := io.ReadAll(c)
			if e == nil {
				t.Fatal("invalid response accepted")
			}
			if !raw.closed {
				t.Fatal("malformed stream left open")
			}
			if _, e = c.Write([]byte("must not send")); e == nil {
				t.Fatal("poisoned stream reused")
			}
		})
	}
}

func TestCoreWriteErrorsAndClose(t *testing.T) {
	for _, kind := range []string{"zero-write", "failure", "closed"} {
		t.Run(kind, func(t *testing.T) {
			c, raw := memoryCore(t)
			switch kind {
			case "zero-write":
				raw.limit = 0
			case "failure":
				raw.writeFailure = io.ErrClosedPipe
			case "closed":
				c.Close()
			}
			if n, e := c.Write([]byte("data")); e == nil || n != 0 {
				t.Fatalf("write returned %d %v", n, e)
			}
			if !raw.closed {
				t.Fatal("failed stream left open")
			}
		})
	}
}
func TestCoreConcurrentReadWriteAndClose(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	conn, e := newCoreConn(a, "aes-128-gcm", "test-password", "test-node", M.ParseSocksaddr("127.0.0.1:80"))
	if e != nil {
		t.Fatal(e)
	}
	c := conn.(*coreConn)
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	done := make(chan error, 2)
	go func() { _, e := c.Write(bytes.Repeat([]byte{7}, 50000)); done <- e }()
	go func() {
		p := make([]byte, 3)
		_, e := io.ReadFull(c, p)
		if e == nil && string(p) != "ok!" {
			e = fmt.Errorf("bad reply")
		}
		done <- e
	}()
	// Reading and writing on the peer simultaneously would deadlock if core used one mutex.
	go func() { io.Copy(io.Discard, b) }()
	if _, e = b.Write(peerResponse(c.salt, nil, 0)); e != nil {
		t.Fatal(e)
	}
	for range 2 {
		if e := <-done; e != nil {
			t.Fatal(e)
		}
	}
	blocked := make(chan error, 1)
	go func() { _, e := c.Read(make([]byte, 1)); blocked <- e }()
	c.Close()
	if e := <-blocked; e == nil {
		t.Fatal("close failed to interrupt read")
	}
}
func TestCoreDeadlineBeforeFirstWrite(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	c, e := newCoreConn(a, "aes-128-gcm", "test-password", "test-node", M.ParseSocksaddr("example.com:80"))
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	c.SetWriteDeadline(time.Now().Add(-time.Second))
	if n, e := c.Write([]byte("data")); n != 0 || e == nil {
		t.Fatalf("expired write: %d %v", n, e)
	}
}

func TestCoreEmptyHandshakeAndAddresses(t *testing.T) {
	for _, destination := range []string{"example.com:443", "192.0.2.1:80", "[2001:db8::1]:53"} {
		t.Run(destination, func(t *testing.T) {
			raw := &coreMemoryConn{input: bytes.NewReader(nil), limit: 3}
			conn, err := newCoreConn(raw, "aes-128-gcm", "test-password", "test-node", M.ParseSocksaddr(destination))
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			if n, e := conn.Write(nil); e != nil || n != 0 {
				t.Fatalf("empty handshake %d %v", n, e)
			}
			size := raw.output.Len()
			if _, e := conn.Write(nil); e != nil || raw.output.Len() != size {
				t.Fatal("empty write sent another handshake")
			}
			r := bytes.NewReader(raw.output.Bytes()[32:])
			a := peerCipher(raw.output.Bytes()[:16])
			h, e := peerOpen(r, a, 0, 12)
			if e != nil {
				t.Fatal(e)
			}
			p, e := peerOpen(r, a, 1, int(binary.BigEndian.Uint16(h[10:])))
			if e != nil {
				t.Fatal(e)
			}
			payload := bytes.NewReader(p)
			addr, e := M.SocksaddrSerializer.ReadAddrPort(payload)
			if e != nil || addr.String() != destination {
				t.Fatalf("destination %v %v", addr, e)
			}
			var pad uint16
			if e = binary.Read(payload, binary.BigEndian, &pad); e != nil || payload.Len() != int(pad) {
				t.Fatal("empty handshake includes unexpected data")
			}
		})
	}
}
func TestCoreInvalidParameters(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	for _, args := range []struct {
		password, node string
		dest           M.Socksaddr
	}{
		{"", "node", M.ParseSocksaddr("example.com:80")},
		{"password", "", M.ParseSocksaddr("example.com:80")},
		{"password", "node", M.Socksaddr{}},
		{"password", "node", M.Socksaddr{Fqdn: strings.Repeat("a", 256), Port: 80}},
	} {
		if _, e := newCoreConn(a, "aes-128-gcm", args.password, args.node, args.dest); e == nil {
			t.Fatal("invalid core parameters accepted")
		}
	}
	for _, method := range []string{"aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305"} {
		if _, e := newCoreConn(a, method, "password", "node", M.ParseSocksaddr("example.com:80")); e == nil {
			t.Fatal("unverified method accepted")
		}
	}
}
