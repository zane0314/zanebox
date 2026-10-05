package vless

import (
	"context"
	"errors"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type anyBoxR1Conn struct {
	closed atomic.Int32
}

func (c *anyBoxR1Conn) Read([]byte) (int, error)    { return 0, io.EOF }
func (c *anyBoxR1Conn) Write(p []byte) (int, error) { return len(p), nil }
func (c *anyBoxR1Conn) Close() error {
	c.closed.Add(1)
	return nil
}
func (c *anyBoxR1Conn) LocalAddr() net.Addr                { return anyBoxR1Addr("local") }
func (c *anyBoxR1Conn) RemoteAddr() net.Addr               { return anyBoxR1Addr("remote") }
func (c *anyBoxR1Conn) SetDeadline(_ time.Time) error      { return nil }
func (c *anyBoxR1Conn) SetReadDeadline(_ time.Time) error  { return nil }
func (c *anyBoxR1Conn) SetWriteDeadline(_ time.Time) error { return nil }

type anyBoxR1Addr string

func (a anyBoxR1Addr) Network() string { return "anybox-r1" }
func (a anyBoxR1Addr) String() string  { return string(a) }

type anyBoxR1Dialer struct {
	conn net.Conn
}

func (d *anyBoxR1Dialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return d.conn, nil
}

func (d *anyBoxR1Dialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unexpected ListenPacket in packetaddr regression")
}

func TestAnyBoxR1PacketAddrDomainClosesDialedConn(t *testing.T) {
	destination := M.Socksaddr{Fqdn: "bad.example", Port: 443}
	for _, name := range []string{"dial-context", "listen-packet"} {
		t.Run(name, func(t *testing.T) {
			conn := new(anyBoxR1Conn)
			outbound := &Outbound{
				logger:     log.NewNOPFactory().Logger(),
				dialer:     &anyBoxR1Dialer{conn: conn},
				packetAddr: true,
			}
			var err error
			if name == "dial-context" {
				_, err = outbound.DialContext(context.Background(), N.NetworkUDP, destination)
			} else {
				_, err = outbound.ListenPacket(context.Background(), destination)
			}
			if err == nil {
				t.Fatal("packetaddr domain was accepted")
			}
			if got := conn.closed.Load(); got != 1 {
				t.Fatalf("dialed TCP close count = %d, want 1", got)
			}
		})
	}
}
