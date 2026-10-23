package ninja

import (
	"bytes"
	"fmt"
	"io"
	"net"

	M "github.com/sagernet/sing/common/metadata"
)

type corePacketConn struct {
	*coreConn
	destination M.Socksaddr
}

var (
	_ net.PacketConn = (*corePacketConn)(nil)
	_ net.Conn       = (*corePacketConn)(nil)
)

func newCorePacketConn(conn net.Conn, method, password, nodePassword string, destination M.Socksaddr) (net.PacketConn, error) {
	stream, err := newCoreConn(conn, method, password, nodePassword, destination)
	if err != nil {
		return nil, err
	}
	c := stream.(*coreConn)
	c.network = 2
	return &corePacketConn{coreConn: c, destination: destination.Unwrap()}, nil
}

func (c *corePacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	// The original writer ignores per-packet addresses and fixes the target in its first header.
	if addr == nil || M.SocksaddrFromNet(addr).Unwrap() != c.destination {
		return 0, fmt.Errorf("ninja: UDP destination differs from connection target")
	}
	return c.coreConn.Write(p)
}
func (c *corePacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	c.readMu.Lock()
	defer c.readMu.Unlock()
	if c.closed.Load() {
		return 0, nil, net.ErrClosed
	}
	payload, err := c.readFrame()
	if err != nil {
		c.Close()
		return 0, nil, err
	}
	reader := bytes.NewReader(payload)
	source, err := M.SocksaddrSerializer.ReadAddrPort(reader)
	if err == nil && !source.IsValid() {
		err = fmt.Errorf("empty source address")
	}
	if err != nil {
		if err == io.EOF {
			err = io.ErrUnexpectedEOF
		}
		c.Close()
		return 0, nil, fmt.Errorf("ninja: invalid UDP response address: %w", err)
	}
	data := payload[len(payload)-reader.Len():]
	n := copy(p, data)
	var address net.Addr = source
	if source.IsIP() {
		address = source.UDPAddr()
	}
	if n < len(data) {
		return n, address, io.ErrShortBuffer
	}
	return n, address, nil
}
func (c *corePacketConn) Write(p []byte) (int, error) { return c.WriteTo(p, c.destination) }
func (c *corePacketConn) Read(p []byte) (int, error)  { n, _, err := c.ReadFrom(p); return n, err }
