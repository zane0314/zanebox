//go:build with_quic

// NATIVE-R15 local lifecycle check. This test is part of libcore and is
// included in the native input hash by design.
package libcore

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/tls"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/sip003"
	"github.com/sagernet/sing-box/transport/v2ray"
	"github.com/sagernet/sing-box/transport/v2rayquic"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type anyboxLocalQUICDialer struct {
	access sync.Mutex
	conns  []*net.UDPConn
}

func (d *anyboxLocalQUICDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if N.NetworkName(network) != N.NetworkUDP {
		return nil, fmt.Errorf("test dialer received %q, want udp", network)
	}
	conn, err := (&net.Dialer{}).DialContext(ctx, "udp", destination.String())
	if err != nil {
		return nil, err
	}
	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		_ = conn.Close()
		return nil, fmt.Errorf("test dialer returned %T, want *net.UDPConn", conn)
	}
	d.access.Lock()
	d.conns = append(d.conns, udpConn)
	d.access.Unlock()
	return udpConn, nil
}

func (d *anyboxLocalQUICDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("test dialer ListenPacket is unused")
}

func (d *anyboxLocalQUICDialer) conn(index int) *net.UDPConn {
	d.access.Lock()
	defer d.access.Unlock()
	if index < 0 || index >= len(d.conns) {
		return nil
	}
	return d.conns[index]
}

type anyboxQUICSession struct {
	ctx    context.Context
	done   chan struct{}
	stream net.Conn
}

type anyboxQUICLifecycleHandler struct {
	sessions chan *anyboxQUICSession
}

func (h *anyboxQUICLifecycleHandler) NewConnectionEx(ctx context.Context, conn net.Conn, _ M.Socksaddr, _ M.Socksaddr, _ N.CloseHandlerFunc) {
	session := &anyboxQUICSession{
		ctx:    ctx,
		done:   make(chan struct{}),
		stream: conn,
	}
	h.sessions <- session
	go func() {
		defer close(session.done)
		defer conn.Close()
		payload := make([]byte, len("anybox-quic-ping"))
		if _, err := io.ReadFull(conn, payload); err == nil {
			_, _ = conn.Write(payload)
		}
		<-ctx.Done()
	}()
}

type anyboxLocalCertificate struct {
	leafPEM string
	keyPEM  string
	rootRaw string
}

func anyboxNewLocalCertificate(t *testing.T) anyboxLocalCertificate {
	t.Helper()
	serial := func(value int64) *big.Int { return big.NewInt(value) }
	rootKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	rootTemplate := &x509.Certificate{
		SerialNumber:          serial(1),
		Subject:               pkix.Name{CommonName: "AnyBox test root"},
		NotBefore:             time.Now().Add(-time.Minute),
		NotAfter:              time.Now().Add(time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign | x509.KeyUsageDigitalSignature,
	}
	rootDER, err := x509.CreateCertificate(rand.Reader, rootTemplate, rootTemplate, &rootKey.PublicKey, rootKey)
	if err != nil {
		t.Fatal(err)
	}
	leafKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	leafTemplate := &x509.Certificate{
		SerialNumber: serial(2),
		Subject:      pkix.Name{CommonName: "localhost"},
		DNSNames:     []string{"localhost"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
		NotBefore:    time.Now().Add(-time.Minute),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	leafDER, err := x509.CreateCertificate(rand.Reader, leafTemplate, rootTemplate, &leafKey.PublicKey, rootKey)
	if err != nil {
		t.Fatal(err)
	}
	rootPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: rootDER})
	leafPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: leafDER})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(leafKey)})
	rootBlock, _ := pem.Decode(rootPEM)
	if rootBlock == nil {
		t.Fatal("generated root certificate did not PEM decode")
	}
	return anyboxLocalCertificate{
		leafPEM: string(leafPEM),
		keyPEM:  string(keyPEM),
		// v2ray-plugin's certRaw option is inserted between PEM markers.
		rootRaw: base64.StdEncoding.EncodeToString(rootBlock.Bytes),
	}
}

func anyboxWaitSession(t *testing.T, sessions <-chan *anyboxQUICSession) *anyboxQUICSession {
	t.Helper()
	select {
	case session := <-sessions:
		return session
	case <-time.After(5 * time.Second):
		t.Fatal("local QUIC server did not accept a v2ray-plugin session")
		return nil
	}
}

func anyboxRoundTrip(t *testing.T, conn net.Conn) {
	t.Helper()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	const payload = "anybox-quic-ping"
	if _, err := io.WriteString(conn, payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != payload {
		t.Fatalf("local QUIC echo mismatch: %q", got)
	}
}

func anyboxWaitDone(t *testing.T, done <-chan struct{}, what string) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatalf("%s was not released", what)
	}
}

func anyboxWaitUDPClosed(t *testing.T, conn *net.UDPConn) {
	t.Helper()
	if conn == nil {
		t.Fatal("missing client UDP socket")
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		_ = conn.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
		_, err := conn.Read(make([]byte, 1))
		if errors.Is(err, net.ErrClosed) || strings.Contains(errString(err), "closed network connection") {
			return
		}
		if ne, ok := err.(net.Error); ok && ne.Timeout() {
			continue
		}
		if err != nil {
			t.Fatalf("client UDP socket failed without closing: %v", err)
		}
	}
	t.Fatal("client UDP socket remained open after plugin Close")
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func TestAnyBoxV2RayPluginQUICCloseRedial(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	cert := anyboxNewLocalCertificate(t)
	serverTLS, err := tls.NewServerWithOptions(tls.ServerOptions{
		Context: ctx,
		Logger:  logger.NOP(),
		Options: option.InboundTLSOptions{
			Enabled:     true,
			ServerName:  "localhost",
			ALPN:        []string{"h3"},
			Certificate: []string{cert.leafPEM},
			Key:         []string{cert.keyPEM},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if serverTLS == nil {
		t.Fatal("local QUIC TLS server was not created")
	}
	udpListener, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer udpListener.Close()
	serverAddr := M.SocksaddrFromNet(udpListener.LocalAddr())
	handler := &anyboxQUICLifecycleHandler{sessions: make(chan *anyboxQUICSession, 4)}
	v2ray.RegisterQUICConstructor(v2rayquic.NewServer, v2rayquic.NewClient)
	server, err := v2ray.NewServerTransport(ctx, logger.NOP(), option.V2RayTransportOptions{
		Type: C.V2RayTransportTypeQUIC,
	}, serverTLS, handler)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	if err := server.ServePacket(udpListener); err != nil {
		t.Fatal(err)
	}

	dialer := &anyboxLocalQUICDialer{}
	plugin, err := sip003.CreatePlugin(ctx, "v2ray-plugin", "mode=quic;tls;host=localhost;certRaw="+cert.rootRaw, nil, dialer, serverAddr)
	if err != nil {
		t.Fatal(err)
	}
	closer, ok := plugin.(interface{ Close() error })
	if !ok {
		t.Fatal("real v2ray-plugin transport does not expose Close")
	}

	firstConn, err := plugin.DialContext(ctx)
	if err != nil {
		t.Fatal(err)
	}
	anyboxRoundTrip(t, firstConn)
	firstSession := anyboxWaitSession(t, handler.sessions)
	firstUDP := dialer.conn(0)
	if firstUDP == nil {
		t.Fatal("first QUIC Dial did not use a tracked UDP socket")
	}
	if err := closer.Close(); err != nil {
		t.Fatal(err)
	}
	anyboxWaitUDPClosed(t, firstUDP)
	anyboxWaitDone(t, firstSession.done, "first QUIC session")
	_ = firstConn.Close()

	secondConn, err := plugin.DialContext(ctx)
	if err != nil {
		t.Fatal(err)
	}
	anyboxRoundTrip(t, secondConn)
	secondSession := anyboxWaitSession(t, handler.sessions)
	secondUDP := dialer.conn(1)
	if secondUDP == nil || secondUDP == firstUDP {
		t.Fatal("second Dial did not create a new UDP socket")
	}
	if secondSession == firstSession {
		t.Fatal("second Dial reused the closed QUIC session")
	}
	if err := closer.Close(); err != nil {
		t.Fatal(err)
	}
	anyboxWaitUDPClosed(t, secondUDP)
	anyboxWaitDone(t, secondSession.done, "second QUIC session")
	_ = secondConn.Close()
}
