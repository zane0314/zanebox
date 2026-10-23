package ninja

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/subtle"
	"encoding/binary"
	"fmt"
	"io"
	randv2 "math/rand/v2"
	"net"
	"sync"
	"sync/atomic"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	"lukechampine.com/blake3"
)

const (
	corePSKContext    = "de6dbaddc4e0dbaaeb65778b44e6fdac"
	coreSubkeyContext = "52386811679ea908c4a2b02f367c472c"
	coreAESContext    = "e14d197ae519bdd2d9a75ae94df7e208"
)

type coreConn struct {
	net.Conn
	psk, salt, address    []byte
	auth                  [16]byte
	writer, reader        cipher.AEAD
	writeNonce, readNonce [12]byte
	writeMu, readMu       sync.Mutex
	wroteHeader           bool
	pending               []byte
	closed                atomic.Bool
	network               byte
}

func coreDerive(context string, material []byte) []byte {
	key := make([]byte, 16)
	blake3.DeriveKey(key, context, material)
	return key
}

func coreAEAD(psk, salt []byte) cipher.AEAD {
	key := coreDerive(coreSubkeyContext, append(append([]byte(nil), psk...), salt...))
	block, _ := aes.NewCipher(key)
	aead, _ := cipher.NewGCM(block)
	return aead
}

func newCoreConn(conn net.Conn, method, password, nodePassword string, destination M.Socksaddr) (net.Conn, error) {
	// shortcut: only AES-128's fixed response header is verified against the original core; verify other methods before adding them.
	if method != "aes-128-gcm" {
		return nil, fmt.Errorf("ninja: unsupported core method %q", method)
	}
	if password == "" || nodePassword == "" {
		return nil, fmt.Errorf("ninja: empty core password")
	}
	if !destination.IsValid() {
		return nil, fmt.Errorf("ninja: invalid destination")
	}
	var address bytes.Buffer
	if err := M.SocksaddrSerializer.WriteAddrPort(&address, destination); err != nil {
		return nil, err
	}
	c := &coreConn{Conn: conn, network: 1, psk: coreDerive(corePSKContext, []byte(password)), salt: make([]byte, 16), address: address.Bytes()}
	if _, err := rand.Read(c.salt); err != nil {
		return nil, err
	}
	nodePSK := coreDerive(corePSKContext, []byte(nodePassword))
	block, _ := aes.NewCipher(coreDerive(coreAESContext, append(nodePSK, c.salt...)))
	check := blake3.Sum512(c.psk)
	block.Encrypt(c.auth[:], check[:16])
	c.writer = coreAEAD(c.psk, c.salt)
	return c, nil
}

func coreIncrement(nonce *[12]byte) {
	for i := range nonce {
		nonce[i]++
		if nonce[i] != 0 {
			break
		}
	}
}

func (c *coreConn) seal(dst, plain []byte) []byte {
	dst = c.writer.Seal(dst, c.writeNonce[:], plain, nil)
	coreIncrement(&c.writeNonce)
	return dst
}

func (c *coreConn) Write(p []byte) (int, error) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if c.closed.Load() {
		return 0, net.ErrClosed
	}
	packet := c.network == 2
	if packet {
		limit := 65534
		if !c.wroteHeader {
			limit = 65535 - len(c.address) - 3
		}
		if len(p) > limit {
			return 0, fmt.Errorf("ninja: UDP datagram exceeds frame size")
		}
	}
	written := 0
	for !c.wroteHeader || len(p) > 0 || packet {
		var header, payload, wire []byte
		var n int
		pad := 1 + randv2.IntN(900)
		if !c.wroteHeader {
			// Match planFirstBlock's 877-byte address/data/padding budget.
			pad = 1 + randv2.IntN(min(900, 877-len(c.address)))
			n = min(len(p), 877-len(c.address)-pad)
			if packet {
				pad = 1 + randv2.IntN(max(1, min(900, 877-len(c.address)-len(p))))
				n = len(p)
			}
			payload = append(payload, c.address...)
			payload = binary.BigEndian.AppendUint16(payload, uint16(pad))
			start := len(payload)
			payload = append(payload, make([]byte, pad)...)
			if _, err := rand.Read(payload[start:]); err != nil {
				c.Close()
				return written, err
			}
			payload = append(payload, p[:n]...)
			header = make([]byte, 12)
			header[1] = c.network
			binary.BigEndian.PutUint64(header[2:10], uint64(time.Now().UnixMilli()))
			binary.BigEndian.PutUint16(header[10:12], uint16(len(payload)))
			wire = append(wire, c.salt...)
			wire = append(wire, c.auth[:]...)
		} else {
			n = min(len(p), 0x3fff)
			if packet {
				n = len(p)
				pad = min(pad, 65535-n)
			}
			header = make([]byte, 4)
			binary.BigEndian.PutUint16(header, uint16(n+pad))
			binary.BigEndian.PutUint16(header[2:], uint16(pad))
			payload = make([]byte, n+pad)
			copy(payload, p[:n])
			if _, err := rand.Read(payload[n:]); err != nil {
				c.Close()
				return written, err
			}
		}
		wire = c.seal(wire, header)
		wire = c.seal(wire, payload)
		for len(wire) > 0 {
			count, err := c.Conn.Write(wire)
			if count < 0 || count > len(wire) {
				err = io.ErrShortWrite
				count = 0
			}
			wire = wire[count:]
			if err == nil && count == 0 {
				err = io.ErrShortWrite
			}
			if err != nil {
				c.Close()
				return written, err
			}
		}
		c.wroteHeader = true
		written += n
		p = p[n:]
		if packet {
			break
		}
	}
	return written, nil
}

func (c *coreConn) open(size int, allowEOF bool) ([]byte, error) {
	ciphertext := make([]byte, size+c.reader.Overhead())
	if _, err := io.ReadFull(c.Conn, ciphertext); err != nil {
		if err == io.EOF && !allowEOF {
			err = io.ErrUnexpectedEOF
		}
		return nil, err
	}
	plain, err := c.reader.Open(ciphertext[:0], c.readNonce[:], ciphertext, nil)
	if err != nil {
		return nil, fmt.Errorf("ninja: core authentication: %w", err)
	}
	coreIncrement(&c.readNonce)
	return plain, nil
}

func (c *coreConn) readFrame() ([]byte, error) {
	first := c.reader == nil
	if first {
		salt := make([]byte, 16)
		if _, err := io.ReadFull(c.Conn, salt); err != nil {
			return nil, err
		}
		c.reader = coreAEAD(c.psk, salt)
	}
	size := 4
	if first {
		size = 29
	}
	header, err := c.open(size, !first)
	if err != nil {
		return nil, err
	}
	if first {
		if header[0] != 1 {
			return nil, fmt.Errorf("ninja: invalid response type")
		}
		if subtle.ConstantTimeCompare(header[9:25], c.salt) != 1 {
			return nil, fmt.Errorf("ninja: invalid writer salt")
		}
		header = header[25:]
	}
	total, pad := int(binary.BigEndian.Uint16(header)), int(binary.BigEndian.Uint16(header[2:]))
	if pad > total {
		return nil, fmt.Errorf("ninja: invalid core lengths total=%d padding=%d", total, pad)
	}
	payload, err := c.open(total, false)
	if err != nil {
		return nil, err
	}
	return payload[:total-pad], nil
}

func (c *coreConn) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	c.readMu.Lock()
	defer c.readMu.Unlock()
	if c.closed.Load() {
		return 0, net.ErrClosed
	}
	for len(c.pending) == 0 {
		payload, err := c.readFrame()
		if err != nil {
			c.Close()
			return 0, err
		}
		c.pending = payload
	}
	n := copy(p, c.pending)
	c.pending = c.pending[n:]
	return n, nil
}

func (c *coreConn) Close() error {
	if c.closed.CompareAndSwap(false, true) {
		return c.Conn.Close()
	}
	return nil
}
