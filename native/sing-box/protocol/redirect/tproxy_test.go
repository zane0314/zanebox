package redirect

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/listener"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
)

type r6PacketListener struct {
	access sync.Mutex
	conns  []*net.UDPConn
}

func (l *r6PacketListener) ListenPacket(_ net.ListenConfig, _ context.Context, _ string, _ string) (net.PacketConn, error) {
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero})
	if err != nil {
		return nil, err
	}
	l.access.Lock()
	l.conns = append(l.conns, conn)
	l.access.Unlock()
	return conn, nil
}

func (l *r6PacketListener) conn(index int) *net.UDPConn {
	l.access.Lock()
	defer l.access.Unlock()
	return l.conns[index]
}

func r6Destination() M.Socksaddr {
	return M.Socksaddr{Addr: netip.MustParseAddr("198.51.100.1"), Port: 53}
}

func r6Receiver(t *testing.T) (*net.UDPConn, netip.AddrPort) {
	t.Helper()
	receiver, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	return receiver, receiver.LocalAddr().(*net.UDPAddr).AddrPort()
}

func TestTProxyPacketWriterLocalUDPAndClose(t *testing.T) {
	receiver, source := r6Receiver(t)
	defer receiver.Close()
	listener := new(r6PacketListener)
	destination := r6Destination()
	writer := &tproxyPacketWriter{
		ctx:         context.Background(),
		listener:    listener,
		source:      source,
		destination: destination,
	}

	if err := writer.WritePacket(buf.As([]byte("r6-local")), destination); err != nil {
		t.Fatal(err)
	}
	_ = receiver.SetReadDeadline(time.Now().Add(time.Second))
	packet := make([]byte, 64)
	n, _, err := receiver.ReadFromUDP(packet)
	if err != nil {
		t.Fatal(err)
	}
	if string(packet[:n]) != "r6-local" {
		t.Fatalf("unexpected packet %q", packet[:n])
	}

	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := writer.WritePacket(buf.As([]byte("after-close")), destination); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("write after close error = %v, want net.ErrClosed", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestTProxyPacketWriterOnCloseUsesLifecycleClose(t *testing.T) {
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	tproxy := &TProxy{ctx: context.Background(), listener: &listener.Listener{}}
	source := M.Socksaddr{Addr: netip.MustParseAddr("127.0.0.1"), Port: 40000}
	_, _, packetWriter, onClose := tproxy.preparePacketConnection(source, r6Destination(), nil)
	writer := packetWriter.(*tproxyPacketWriter)
	writer.conn = conn
	onClose(nil)
	if _, err := conn.WriteToUDPAddrPort([]byte("after-close"), netip.MustParseAddrPort("127.0.0.1:1")); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("onClose left socket open: %v", err)
	}
}

func TestTProxyPacketWriterWriteErrorClosesCurrentSocket(t *testing.T) {
	listener := new(r6PacketListener)
	destination := r6Destination()
	writer := &tproxyPacketWriter{
		ctx:         context.Background(),
		listener:    listener,
		destination: destination,
	}

	if err := writer.WritePacket(buf.As([]byte("bad-source")), destination); err == nil {
		t.Fatal("invalid source unexpectedly wrote a packet")
	}
	conn := listener.conn(0)
	if _, err := conn.WriteToUDPAddrPort([]byte("after-error"), netip.MustParseAddrPort("127.0.0.1:1")); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("failed-write socket error = %v, want net.ErrClosed", err)
	}
}

func TestTProxyPacketWriterWriteErrorClosesTemporarySocket(t *testing.T) {
	listener := new(r6PacketListener)
	writer := &tproxyPacketWriter{
		ctx:         context.Background(),
		listener:    listener,
		source:      netip.AddrPort{},
		destination: r6Destination(),
	}

	temporaryDestination := M.Socksaddr{Addr: netip.MustParseAddr("198.51.100.2"), Port: 53}
	if err := writer.WritePacket(buf.As([]byte("bad-temporary")), temporaryDestination); err == nil {
		t.Fatal("invalid source unexpectedly wrote a packet")
	}
	conn := listener.conn(0)
	if _, err := conn.WriteToUDPAddrPort([]byte("after-error"), netip.MustParseAddrPort("127.0.0.1:1")); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("failed temporary-write socket error = %v, want net.ErrClosed", err)
	}
}

func TestTProxyPacketWriterFailedOldSocketDoesNotCloseReplacement(t *testing.T) {
	oldConn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero})
	if err != nil {
		t.Fatal(err)
	}
	defer oldConn.Close()
	newConn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero})
	if err != nil {
		t.Fatal(err)
	}
	defer newConn.Close()

	writer := &tproxyPacketWriter{conn: newConn}
	if err := writer.closeConnIfCurrent(oldConn); err != nil {
		t.Fatal(err)
	}
	if _, err := newConn.WriteToUDPAddrPort([]byte("replacement"), netip.MustParseAddrPort("127.0.0.1:1")); err != nil {
		t.Fatalf("replacement socket was closed: %v", err)
	}
}

func TestTProxyPacketWriterCloseWriteRace(t *testing.T) {
	receiver, source := r6Receiver(t)
	defer receiver.Close()
	listener := new(r6PacketListener)
	destination := r6Destination()
	writer := &tproxyPacketWriter{
		ctx:         context.Background(),
		listener:    listener,
		source:      source,
		destination: destination,
	}
	start := make(chan struct{})
	var group sync.WaitGroup
	for i := 0; i < 8; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			<-start
			for j := 0; j < 200; j++ {
				err := writer.WritePacket(buf.As([]byte("race")), destination)
				if err != nil && !errors.Is(err, net.ErrClosed) {
					t.Errorf("write failed: %v", err)
				}
			}
		}()
	}
	group.Add(1)
	go func() {
		defer group.Done()
		<-start
		for i := 0; i < 200; i++ {
			if err := writer.Close(); err != nil {
				t.Errorf("close failed: %v", err)
			}
		}
	}()
	close(start)
	group.Wait()
}
