package libcore

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/sagernet/sing-box/transport/trojan"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func TestAnyBoxTrojanR8FragmentedAuthentication(t *testing.T) {
	key := trojan.Key("r8-fragmented-secret")
	destination := M.SocksaddrFrom(netip.MustParseAddr("127.0.0.1"), 443)
	var handshake bytes.Buffer
	handshake.Write(key[:])
	handshake.Write(trojan.CRLF)
	handshake.WriteByte(trojan.CommandTCP)
	if err := M.SocksaddrSerializer.WriteAddrPort(&handshake, destination); err != nil {
		t.Fatal(err)
	}
	handshake.Write(trojan.CRLF)

	handler := &anyBoxTrojanR8Handler{}
	service := trojan.NewService[int](handler, nil, nil)
	if err := service.UpdateUsers([]int{0}, []string{"r8-fragmented-secret"}); err != nil {
		t.Fatal(err)
	}
	conn := &anyBoxTrojanR8Conn{reader: bytes.NewReader(handshake.Bytes()), chunkSize: 3}
	if err := service.NewConnection(context.Background(), conn, M.Socksaddr{}, nil); err != nil {
		t.Fatalf("fragmented valid key rejected: %v", err)
	}
	if conn.readCalls < 2 {
		t.Fatalf("fixture did not produce a short read: %d reads", conn.readCalls)
	}
	if handler.tcpCalls != 1 {
		t.Fatalf("TCP handler calls = %d, want 1", handler.tcpCalls)
	}
	if handler.destination != destination {
		t.Fatalf("destination = %v, want %v", handler.destination, destination)
	}
}

func TestAnyBoxTrojanR8FallbackKeepsPartialPrefix(t *testing.T) {
	key := trojan.Key("r8-fallback-secret")
	prefix := append([]byte(nil), key[:19]...)
	fallback := &anyBoxTrojanR8Fallback{}
	service := trojan.NewService[int](nil, fallback, nil)
	conn := &anyBoxTrojanR8Conn{reader: bytes.NewReader(prefix), chunkSize: 2}
	if err := service.NewConnection(context.Background(), conn, M.Socksaddr{}, nil); err != nil {
		t.Fatalf("partial key fallback failed: %v", err)
	}
	if fallback.calls != 1 {
		t.Fatalf("fallback calls = %d, want 1", fallback.calls)
	}
	if !bytes.Equal(fallback.cached, prefix) {
		t.Fatalf("fallback prefix = %x, want %x", fallback.cached, prefix)
	}
}

func TestAnyBoxTrojanR8EmptyTruncationFails(t *testing.T) {
	service := trojan.NewService[int](nil, nil, nil)
	err := service.NewConnection(context.Background(), &anyBoxTrojanR8Conn{
		reader:    bytes.NewReader(nil),
		chunkSize: 1,
	}, M.Socksaddr{}, nil)
	if err == nil {
		t.Fatal("empty authentication stream unexpectedly succeeded")
	}
}

type anyBoxTrojanR8Handler struct {
	tcpCalls    int
	destination M.Socksaddr
}

func (h *anyBoxTrojanR8Handler) NewConnectionEx(_ context.Context, _ net.Conn, _ M.Socksaddr, destination M.Socksaddr, _ N.CloseHandlerFunc) {
	h.tcpCalls++
	h.destination = destination
}

func (*anyBoxTrojanR8Handler) NewPacketConnectionEx(_ context.Context, _ N.PacketConn, _ M.Socksaddr, _ M.Socksaddr, _ N.CloseHandlerFunc) {
}

type anyBoxTrojanR8Fallback struct {
	calls  int
	cached []byte
}

func (h *anyBoxTrojanR8Fallback) NewConnectionEx(_ context.Context, conn net.Conn, _ M.Socksaddr, _ M.Socksaddr, _ N.CloseHandlerFunc) {
	h.calls++
	cached, ok := conn.(N.CachedReader)
	if !ok {
		return
	}
	buffer := cached.ReadCached()
	if buffer != nil {
		h.cached = append([]byte(nil), buffer.Bytes()...)
		buffer.Release()
	}
}

type anyBoxTrojanR8Conn struct {
	reader    *bytes.Reader
	chunkSize int
	readCalls int
}

func (c *anyBoxTrojanR8Conn) Read(p []byte) (int, error) {
	c.readCalls++
	if c.reader.Len() == 0 {
		return 0, io.EOF
	}
	if len(p) > c.chunkSize {
		p = p[:c.chunkSize]
	}
	return c.reader.Read(p)
}

func (*anyBoxTrojanR8Conn) Write(p []byte) (int, error)        { return len(p), nil }
func (*anyBoxTrojanR8Conn) Close() error                       { return nil }
func (*anyBoxTrojanR8Conn) LocalAddr() net.Addr                { return anyBoxTrojanR8Addr("local") }
func (*anyBoxTrojanR8Conn) RemoteAddr() net.Addr               { return anyBoxTrojanR8Addr("remote") }
func (*anyBoxTrojanR8Conn) SetDeadline(_ time.Time) error      { return nil }
func (*anyBoxTrojanR8Conn) SetReadDeadline(_ time.Time) error  { return nil }
func (*anyBoxTrojanR8Conn) SetWriteDeadline(_ time.Time) error { return nil }

type anyBoxTrojanR8Addr string

func (a anyBoxTrojanR8Addr) Network() string { return "anybox-r8" }
func (a anyBoxTrojanR8Addr) String() string  { return string(a) }
