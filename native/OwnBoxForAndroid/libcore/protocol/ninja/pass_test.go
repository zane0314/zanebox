package ninja

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"golang.org/x/crypto/sha3"
	"io"
	"net"
	"testing"
	"time"
)

func TestPassRejectsUnsupportedMethod(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	if _, e := newPassConn(a, PassOptions{Method: "aes-256-gcm", Password: "test-password"}); e == nil {
		t.Fatal("unverified method accepted")
	}
}

func passTestOptions() PassOptions {
	return PassOptions{Method: "aes-128-gcm", Password: "test-password", PaddingMin: 16, PaddingMode: "min_random", PaddingRandomMax: 32}
}
func passMemory(t *testing.T) (*passConn, *coreMemoryConn) {
	t.Helper()
	raw := &coreMemoryConn{input: bytes.NewReader(nil), limit: 7}
	c, e := newPassConn(raw, passTestOptions())
	if e != nil {
		t.Fatal(e)
	}
	return c.(*passConn), raw
}
func TestPassFixedVector(t *testing.T) {
	c, _ := passMemory(t)
	if hex.EncodeToString(c.master[:]) != "62b94c32bb9cd849b6f30e9f595f1847" {
		t.Fatal("master vector mismatch")
	}
	if hex.EncodeToString(c.verify[:]) != "5d5c324caa426d0456bd4f1fa90a0fa0" {
		t.Fatal("verify vector mismatch")
	}
	var nonce [12]byte
	for i := range nonce {
		nonce[i] = byte(i)
	}
	key := passHKDF(c.master[:], nonce[:], passInfo)
	if hex.EncodeToString(key[:]) != "2a900d1d455b6b0abc9e5a414e169acc" {
		t.Fatal("key vector mismatch")
	}
	d := passState(c.master[:], nonce)
	var mask [4]byte
	d.mask(mask[:])
	if hex.EncodeToString(mask[:]) != "f6101f2c" {
		t.Fatalf("shake vector mismatch: %x", mask)
	}
}

// A separate HMAC/HKDF implementation keeps the peer independent of passState.
func passPeerKey(password string, salt, info []byte) []byte {
	sum := sha3.Sum512([]byte(password))
	extract := hmac.New(sha3.New512, salt)
	extract.Write(sum[:16])
	expand := hmac.New(sha3.New512, extract.Sum(nil))
	expand.Write(info)
	expand.Write([]byte{1})
	return expand.Sum(nil)[:16]
}

type passPeer struct {
	a     cipher.AEAD
	shake sha3.ShakeHash
	nonce [12]byte
}

func newPassPeer(password string, nonce []byte) *passPeer {
	info, _ := hex.DecodeString("d8b8c066c10e8b0d1b1ba3ad1f7de1525625d03f9d9f682f1bd6097b13a22b38")
	key := passPeerKey(password, nonce, info)
	block, _ := aes.NewCipher(key)
	a, _ := cipher.NewGCM(block)
	shake := sha3.NewShake128()
	shake.Write(append(append([]byte(nil), nonce...), key...))
	p := &passPeer{a: a, shake: shake}
	copy(p.nonce[:], nonce)
	return p
}
func (p *passPeer) next() {
	carry := uint16(1)
	for i := range p.nonce {
		v := uint16(p.nonce[i]) + carry
		p.nonce[i] = byte(v)
		carry = v >> 8
	}
}
func (p *passPeer) header(dataLen, pad int) []byte {
	h := make([]byte, 4)
	binary.BigEndian.PutUint16(h, uint16(dataLen))
	binary.BigEndian.PutUint16(h[2:], uint16(pad))
	mask := make([]byte, 4)
	p.shake.Read(mask)
	for i := range h {
		h[i] ^= mask[i]
	}
	return h
}
func (p *passPeer) seal(data []byte) []byte {
	b := p.a.Seal(nil, p.nonce[:], data, nil)
	p.next()
	return b
}
func (p *passPeer) frame(data []byte, pad int) []byte {
	wire := p.seal(p.header(len(data), pad))
	body := append(append([]byte(nil), data...), bytes.Repeat([]byte{0xa5}, pad)...)
	return append(wire, p.seal(body)...)
}
func (p *passPeer) open(r io.Reader, size int) ([]byte, error) {
	b := make([]byte, size+16)
	if _, e := io.ReadFull(r, b); e != nil {
		return nil, e
	}
	b, e := p.a.Open(nil, p.nonce[:], b, nil)
	p.next()
	return b, e
}
func (p *passPeer) read(r io.Reader) ([]byte, int, error) {
	h, e := p.open(r, 4)
	if e != nil {
		return nil, 0, e
	}
	mask := make([]byte, 4)
	p.shake.Read(mask)
	for i := range h {
		h[i] ^= mask[i]
	}
	dataLen, pad := int(binary.BigEndian.Uint16(h)), int(binary.BigEndian.Uint16(h[2:]))
	if dataLen+pad > 65535 {
		return nil, 0, fmt.Errorf("oversized frame")
	}
	data, e := p.open(r, dataLen+pad)
	if e != nil {
		return nil, 0, e
	}
	return data[:dataLen], pad, nil
}
func passPeerWire(data []byte, frames int) []byte {
	nonce := []byte{0xfe, 0xff, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}
	p := newPassPeer("test-password", nonce)
	wire := append(append([]byte(nil), nonce...), p.frame(data, 31)...)
	for range frames {
		wire = append(wire, p.frame([]byte{'x'}, 3)...)
	}
	return wire
}
func TestPassWirePrefixChunkingAndNonce(t *testing.T) {
	c, raw := passMemory(t)
	data := bytes.Repeat([]byte("business data"), 16000)
	if n, e := c.Write(data); e != nil || n != len(data) {
		t.Fatalf("write: %d %v", n, e)
	}
	for range 130 {
		if n, e := c.Write([]byte{'x'}); n != 1 || e != nil {
			t.Fatalf("write %d %v", n, e)
		}
	}
	r := bytes.NewReader(raw.output.Bytes())
	nonce := make([]byte, 12)
	io.ReadFull(r, nonce)
	p := newPassPeer("test-password", nonce)
	var got []byte
	frames := 0
	for r.Len() > 0 {
		frame, pad, e := p.read(r)
		if e != nil {
			t.Fatal(e)
		}
		if pad < max(16-len(frame), 0) || pad > max(16-len(frame), 0)+32 {
			t.Fatal("padding outside configured range")
		}
		got = append(got, frame...)
		frames++
	}
	token, _ := hex.DecodeString("5d5c324caa426d0456bd4f1fa90a0fa0")
	expected := append(token, 1)
	expected = append(expected, data...)
	expected = append(expected, bytes.Repeat([]byte{'x'}, 130)...)
	if frames < 134 || !bytes.Equal(got, expected) {
		t.Fatal("prefix, chunking or body changed")
	}
	raw.input = bytes.NewReader(passPeerWire(data[:40000], 130))
	got, e := io.ReadAll(c)
	if e != nil {
		t.Fatal(e)
	}
	if !bytes.Equal(got, append(append([]byte(nil), data[:40000]...), bytes.Repeat([]byte{'x'}, 130)...)) {
		t.Fatal("response or padding changed")
	}
}
func TestPassEmptyHandshake(t *testing.T) {
	c, raw := passMemory(t)
	if n, e := c.Write(nil); n != 0 || e != nil {
		t.Fatalf("empty write %d %v", n, e)
	}
	size := raw.output.Len()
	if _, e := c.Write(nil); e != nil || raw.output.Len() != size {
		t.Fatal("empty call rewrote prefix")
	}
	if n, e := c.Read(nil); n != 0 || e != nil {
		t.Fatal("empty read")
	}
	p := newPassPeer("test-password", raw.output.Bytes()[:12])
	frame, _, e := p.read(bytes.NewReader(raw.output.Bytes()[12:]))
	if e != nil {
		t.Fatal(e)
	}
	if len(frame) != 17 || !bytes.Equal(frame[:16], c.verify[:]) || frame[16] != 1 {
		t.Fatal("invalid empty prefix")
	}
	// Padding-only inbound records must be consumed without returning spurious EOF.
	nonce := make([]byte, 12)
	p = newPassPeer("test-password", nonce)
	wire := append(nonce, p.frame(nil, 5)...)
	wire = append(wire, p.frame([]byte("response"), 0)...)
	raw.input = bytes.NewReader(wire)
	got, e := io.ReadAll(c)
	if e != nil || string(got) != "response" {
		t.Fatalf("empty frame %q %v", got, e)
	}
}
func TestPassMalformedResponse(t *testing.T) {
	for _, kind := range []string{"nonce-truncated", "header-missing", "body-missing", "header-truncated", "body-truncated", "header-auth", "body-auth", "length", "wrong-password", "second-header"} {
		t.Run(kind, func(t *testing.T) {
			c, raw := passMemory(t)
			wire := passPeerWire([]byte("response"), 0)
			switch kind {
			case "nonce-truncated":
				wire = wire[:5]
			case "header-missing":
				wire = wire[:12]
			case "body-missing":
				wire = wire[:32]
			case "header-truncated":
				wire = wire[:25]
			case "body-truncated":
				wire = wire[:len(wire)-1]
			case "header-auth":
				wire[13] ^= 1
			case "body-auth":
				wire[len(wire)-1] ^= 1
			case "length":
				nonce := make([]byte, 12)
				p := newPassPeer("test-password", nonce)
				wire = append(nonce, p.seal(p.header(65535, 1))...)
			case "wrong-password":
				c.master[0] ^= 1
			case "second-header":
				wire = passPeerWire([]byte("response"), 1)
				wire[len(wire)-22] ^= 1
			}
			raw.input = bytes.NewReader(wire)
			if _, e := io.ReadAll(c); e == nil {
				t.Fatal("malformed response accepted")
			}
			remaining := raw.input.Len()
			if _, e := c.Read(make([]byte, 1)); e == nil || raw.input.Len() != remaining {
				t.Fatal("poisoned read direction reused")
			}
		})
	}
}
func TestPassWriteErrorsPoisonDirection(t *testing.T) {
	for _, kind := range []string{"zero-write", "error", "closed"} {
		t.Run(kind, func(t *testing.T) {
			c, raw := passMemory(t)
			switch kind {
			case "zero-write":
				raw.limit = 0
			case "error":
				raw.writeFailure = io.ErrClosedPipe
			case "closed":
				c.Close()
			}
			if n, e := c.Write([]byte("payload")); e == nil || n != 0 {
				t.Fatalf("failed write %d %v", n, e)
			}
			size := raw.output.Len()
			raw.limit = 7
			raw.writeFailure = nil
			if _, e := c.Write([]byte("again")); e == nil || raw.output.Len() != size {
				t.Fatal("poisoned write direction reused")
			}
		})
	}
}
func TestPassConcurrentReadWrite(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	conn, e := newPassConn(a, passTestOptions())
	if e != nil {
		t.Fatal(e)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	done := make(chan error, 2)
	go func() { _, e := conn.Write(bytes.Repeat([]byte{'a'}, 200000)); done <- e }()
	go func() {
		p := make([]byte, 8)
		_, e := io.ReadFull(conn, p)
		if e == nil && string(p) != "response" {
			e = fmt.Errorf("bad reply")
		}
		done <- e
	}()
	go io.Copy(io.Discard, b)
	if _, e = b.Write(passPeerWire([]byte("response"), 0)); e != nil {
		t.Fatal(e)
	}
	for range 2 {
		if e := <-done; e != nil {
			t.Fatal(e)
		}
	}
}
func TestPassCloseInterruptsReadWrite(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	conn, e := newPassConn(a, passTestOptions())
	if e != nil {
		t.Fatal(e)
	}
	done := make(chan error, 2)
	go func() { _, e := conn.Write([]byte("payload")); done <- e }()
	go func() { _, e := conn.Read(make([]byte, 1)); done <- e }()
	if e = conn.Close(); e != nil {
		t.Fatal(e)
	}
	for range 2 {
		select {
		case e := <-done:
			if e == nil {
				t.Fatal("close did not interrupt operation")
			}
		case <-time.After(2 * time.Second):
			t.Fatal("close blocked")
		}
	}
}
func TestPassExpiredWriteDeadline(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	conn, e := newPassConn(a, passTestOptions())
	if e != nil {
		t.Fatal(e)
	}
	defer conn.Close()
	conn.SetWriteDeadline(time.Now().Add(-time.Second))
	if n, e := conn.Write([]byte("payload")); n != 0 || e == nil {
		t.Fatalf("expired write %d %v", n, e)
	}
}
func TestPassOptionsAndPadding(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	for _, change := range []func(*PassOptions){
		func(o *PassOptions) { o.Password = "" }, func(o *PassOptions) { o.PaddingMin = -1 }, func(o *PassOptions) { o.PaddingMin = 65536 }, func(o *PassOptions) { o.PaddingRandomMax = -1 }, func(o *PassOptions) { o.PaddingRandomMax = 65536 }, func(o *PassOptions) { o.PaddingRandomMax = 65535 }, func(o *PassOptions) { o.PaddingMin = 65535 }, func(o *PassOptions) { o.PaddingMode = "invalid" },
	} {
		o := passTestOptions()
		change(&o)
		if _, e := newPassConn(a, o); e == nil {
			t.Fatal("invalid options accepted")
		}
	}
	for _, mode := range []string{"min", "min_random", "random", ""} {
		o := passTestOptions()
		o.PaddingMode = mode
		o.PaddingRandomMax = 0
		conn, e := newPassConn(a, o)
		if e != nil {
			t.Fatal(e)
		}
		c := conn.(*passConn)
		if c.options.PaddingRandomMax != 128 {
			t.Fatal("default random max changed")
		}
		for _, dataLen := range []int{0, 1, 16, 17, 1000} {
			for range 10 {
				n := c.paddingLen(dataLen)
				low := 0
				if mode == "min" || mode == "min_random" {
					low = max(16-dataLen, 0)
				}
				high := low
				if mode != "min" {
					high += 128
				}
				if n < low || n > high {
					t.Fatalf("%s padding %d for %d", mode, n, dataLen)
				}
			}
		}
	}
}

func TestPassFailureIsolation(t *testing.T) {
	t.Run("read", func(t *testing.T) {
		c, raw := passMemory(t)
		wire := passPeerWire([]byte("response"), 0)
		wire[13] ^= 1
		raw.input = bytes.NewReader(wire)
		if _, e := c.Read(make([]byte, 1)); e == nil {
			t.Fatal("bad authentication accepted")
		}
		if n, e := c.Write([]byte("outbound")); n != 8 || e != nil {
			t.Fatalf("read failure poisoned writer: %d %v", n, e)
		}
	})
	t.Run("write", func(t *testing.T) {
		c, raw := passMemory(t)
		raw.writeFailure = io.ErrClosedPipe
		if _, e := c.Write([]byte("outbound")); e == nil {
			t.Fatal("write failure lost")
		}
		raw.input = bytes.NewReader(passPeerWire([]byte("response"), 0))
		got, e := io.ReadAll(c)
		if e != nil || string(got) != "response" {
			t.Fatalf("write failure poisoned reader: %q %v", got, e)
		}
	})
}
