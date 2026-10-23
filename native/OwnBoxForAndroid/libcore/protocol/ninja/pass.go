package ninja

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	randv2 "math/rand/v2"
	"net"
	"sync"
	"sync/atomic"

	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/sha3"
)

type PassOptions struct {
	Method           string `json:"method"`
	Password         string `json:"password"`
	PaddingMin       int    `json:"padding_min"`
	PaddingMode      string `json:"padding_mode"`
	PaddingRandomMax int    `json:"padding_random_max"`
}

var passInfo, _ = hex.DecodeString("d8b8c066c10e8b0d1b1ba3ad1f7de1525625d03f9d9f682f1bd6097b13a22b38")

type passDirection struct {
	aead  cipher.AEAD
	nonce [12]byte
	shake sha3.ShakeHash
	err   error
}

type passConn struct {
	net.Conn
	options         PassOptions
	master, verify  [16]byte
	writer, reader  passDirection
	writeMu, readMu sync.Mutex
	pending         []byte
	closed          atomic.Bool
}

func passHKDF(master, salt, info []byte) (key [16]byte) {
	// A fixed 16-byte HKDF read cannot exceed SHA3-512's expansion limit.
	io.ReadFull(hkdf.New(sha3.New512, master, salt, info), key[:])
	return
}

func passState(master []byte, nonce [12]byte) passDirection {
	key := passHKDF(master, nonce[:], passInfo)
	block, _ := aes.NewCipher(key[:])
	aead, _ := cipher.NewGCM(block)
	shake := sha3.NewShake128()
	shake.Write(nonce[:])
	shake.Write(key[:])
	return passDirection{aead: aead, nonce: nonce, shake: shake}
}

func normalizePassOptions(options PassOptions) (PassOptions, error) {
	if options.Method != "aes-128-gcm" {
		return PassOptions{}, fmt.Errorf("ninja: unsupported pass method %q", options.Method)
	}
	if options.Password == "" {
		return PassOptions{}, fmt.Errorf("ninja: empty pass password")
	}
	if options.PaddingMin < 0 || options.PaddingMin > 65535 || options.PaddingRandomMax < 0 || options.PaddingRandomMax > 65535 {
		return PassOptions{}, fmt.Errorf("ninja: pass padding out of range")
	}
	if options.PaddingRandomMax == 0 {
		options.PaddingRandomMax = 128
	}
	if options.PaddingMode == "" {
		options.PaddingMode = "random"
	}
	switch options.PaddingMode {
	case "min":
	case "min_random", "random":
		if options.PaddingRandomMax > 65535-17 || (options.PaddingMode == "min_random" && options.PaddingMin+options.PaddingRandomMax > 65535) {
			return PassOptions{}, fmt.Errorf("ninja: pass padding leaves insufficient frame space")
		}
	default:
		return PassOptions{}, fmt.Errorf("ninja: unsupported pass padding mode %q", options.PaddingMode)
	}
	return options, nil
}

func newPassConn(conn net.Conn, options PassOptions) (net.Conn, error) {
	options, err := normalizePassOptions(options)
	if err != nil {
		return nil, err
	}
	sum := sha3.Sum512([]byte(options.Password))
	c := &passConn{Conn: conn, options: options}
	copy(c.master[:], sum[:16])
	c.verify = passHKDF(c.master[:], passInfo, []byte(options.Password))
	return c, nil
}

func (c *passConn) paddingLen(dataLen int) int {
	n := 0
	if c.options.PaddingMode != "random" {
		n = max(c.options.PaddingMin-dataLen, 0)
	}
	if c.options.PaddingMode != "min" {
		n += randv2.IntN(c.options.PaddingRandomMax + 1)
	}
	return n
}

func (d *passDirection) mask(header []byte) {
	var mask [4]byte
	d.shake.Read(mask[:])
	for i := range mask {
		header[i] ^= mask[i]
	}
}

func (d *passDirection) seal(dst, plain []byte) []byte {
	dst = d.aead.Seal(dst, d.nonce[:], plain, nil)
	coreIncrement(&d.nonce)
	return dst
}

func (c *passConn) Write(p []byte) (int, error) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if c.closed.Load() {
		return 0, net.ErrClosed
	}
	if c.writer.err != nil {
		return 0, c.writer.err
	}
	first := c.writer.aead == nil
	if !first && len(p) == 0 {
		return 0, nil
	}
	var prefix []byte
	if first {
		var nonce [12]byte
		if _, err := rand.Read(nonce[:]); err != nil {
			c.writer.err = err
			return 0, err
		}
		c.writer = passState(c.master[:], nonce)
		prefix = append(prefix, c.verify[:]...)
		prefix = append(prefix, 1)
	}
	written := 0
	limit := 65535
	if c.options.PaddingMode != "min" {
		limit -= c.options.PaddingRandomMax
	}
	for first || len(p) > 0 {
		n := min(len(p), limit-len(prefix))
		dataLen := len(prefix) + n
		pad := c.paddingLen(dataLen)
		payload := make([]byte, dataLen+pad)
		copy(payload, prefix)
		copy(payload[len(prefix):], p[:n])
		if _, err := rand.Read(payload[dataLen:]); err != nil {
			c.writer.err = err
			return written, err
		}
		var header [4]byte
		binary.BigEndian.PutUint16(header[:2], uint16(dataLen))
		binary.BigEndian.PutUint16(header[2:], uint16(pad))
		c.writer.mask(header[:])
		var wire []byte
		if first {
			wire = append(wire, c.writer.nonce[:]...)
		}
		wire = c.writer.seal(wire, header[:])
		wire = c.writer.seal(wire, payload)
		for len(wire) > 0 {
			count, err := c.Conn.Write(wire)
			if count < 0 || count > len(wire) {
				count = 0
				err = io.ErrShortWrite
			}
			wire = wire[count:]
			if count == 0 && err == nil {
				err = io.ErrShortWrite
			}
			if err != nil {
				c.writer.err = err
				return written, err
			}
		}
		first = false
		prefix = nil
		written += n
		p = p[n:]
	}
	return written, nil
}

func (c *passConn) open(size int, allowEOF bool) ([]byte, error) {
	data := make([]byte, size+c.reader.aead.Overhead())
	if _, err := io.ReadFull(c.Conn, data); err != nil {
		if err == io.EOF && !allowEOF {
			err = io.ErrUnexpectedEOF
		}
		return nil, err
	}
	plain, err := c.reader.aead.Open(data[:0], c.reader.nonce[:], data, nil)
	if err != nil {
		return nil, fmt.Errorf("ninja: pass authentication: %w", err)
	}
	coreIncrement(&c.reader.nonce)
	return plain, nil
}

func (c *passConn) readFrame() ([]byte, error) {
	first := c.reader.aead == nil
	if first {
		var nonce [12]byte
		if _, err := io.ReadFull(c.Conn, nonce[:]); err != nil {
			return nil, err
		}
		c.reader = passState(c.master[:], nonce)
	}
	header, err := c.open(4, !first)
	if err != nil {
		return nil, err
	}
	c.reader.mask(header)
	dataLen, pad := int(binary.BigEndian.Uint16(header[:2])), int(binary.BigEndian.Uint16(header[2:]))
	if dataLen+pad > 65535 {
		return nil, fmt.Errorf("ninja: invalid pass lengths data=%d padding=%d", dataLen, pad)
	}
	payload, err := c.open(dataLen+pad, false)
	if err != nil {
		return nil, err
	}
	return payload[:dataLen], nil
}

func (c *passConn) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	c.readMu.Lock()
	defer c.readMu.Unlock()
	if c.closed.Load() {
		return 0, net.ErrClosed
	}
	if c.reader.err != nil {
		return 0, c.reader.err
	}
	for len(c.pending) == 0 {
		payload, err := c.readFrame()
		if err != nil {
			c.reader.err = err
			return 0, err
		}
		c.pending = payload
	}
	n := copy(p, c.pending)
	c.pending = c.pending[n:]
	return n, nil
}

func (c *passConn) Close() error {
	if c.closed.CompareAndSwap(false, true) {
		return c.Conn.Close()
	}
	return nil
}
