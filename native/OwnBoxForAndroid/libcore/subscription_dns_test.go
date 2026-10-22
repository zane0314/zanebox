package libcore

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	mdns "github.com/miekg/dns"
	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
)

func subscriptionDNSCertificate(t *testing.T) (tls.Certificate, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "dns.test"}, DNSNames: []string{"dns.test"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IsCA: true, BasicConstraintsValid: true}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	cert, err := tls.X509KeyPair(certPEM, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}))
	if err != nil {
		t.Fatal(err)
	}
	return cert, string(certPEM)
}
func subscriptionDNSAnswer(request *mdns.Msg) *mdns.Msg {
	response := new(mdns.Msg)
	response.SetReply(request)
	for _, question := range request.Question {
		if question.Qtype == mdns.TypeA {
			response.Answer = append(response.Answer, &mdns.A{Hdr: mdns.RR_Header{Name: question.Name, Rrtype: mdns.TypeA, Class: mdns.ClassINET, Ttl: 60}, A: net.ParseIP("192.0.2.123")})
		}
		if question.Qtype == mdns.TypeAAAA {
			response.Answer = append(response.Answer, &mdns.AAAA{Hdr: mdns.RR_Header{Name: question.Name, Rrtype: mdns.TypeAAAA, Class: mdns.ClassINET, Ttl: 60}, AAAA: net.ParseIP("2001:db8::123")})
		}
	}
	return response
}
func subscriptionDNSServer(t *testing.T, kind string, cert tls.Certificate) (string, *atomic.Bool) {
	t.Helper()
	verified := new(atomic.Bool)
	tlsConfig := &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13, GetConfigForClient: func(hello *tls.ClientHelloInfo) (*tls.Config, error) {
		if hello.ServerName == "dns.test" {
			verified.Store(true)
		}
		return nil, nil
	}}
	handler := mdns.HandlerFunc(func(w mdns.ResponseWriter, r *mdns.Msg) { _ = w.WriteMsg(subscriptionDNSAnswer(r)) })
	if kind == "udp" || kind == "tcp" || kind == "tls" {
		ready := make(chan struct{})
		server := &mdns.Server{Handler: handler, NotifyStartedFunc: func() { close(ready) }}
		var address string
		if kind == "udp" {
			conn, err := net.ListenPacket("udp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			server.PacketConn = conn
			address = conn.LocalAddr().String()
		} else {
			listener, err := net.Listen("tcp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			address = listener.Addr().String()
			if kind == "tls" {
				listener = tls.NewListener(listener, tlsConfig)
				server.Net = "tcp-tls"
			}
			server.Listener = listener
		}
		go func() { _ = server.ActivateAndServe() }()
		<-ready
		t.Cleanup(func() { _ = server.Shutdown() })
		return address, verified
	}
	httpHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "dns.test" && !strings.HasPrefix(r.Host, "dns.test:") {
			http.Error(w, "wrong Host", 400)
			return
		}
		if r.TLS == nil || r.TLS.ServerName != "dns.test" {
			http.Error(w, "wrong SNI", 400)
			return
		}
		verified.Store(true)
		body, err := io.ReadAll(r.Body)
		if err != nil {
			return
		}
		request := new(mdns.Msg)
		if request.Unpack(body) != nil {
			http.Error(w, "bad DNS", 400)
			return
		}
		response, _ := subscriptionDNSAnswer(request).Pack()
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(response)
	})
	if kind == "https" {
		server := httptest.NewUnstartedServer(httpHandler)
		server.TLS = tlsConfig
		server.EnableHTTP2 = true
		server.StartTLS()
		t.Cleanup(server.Close)
		return server.Listener.Addr().String(), verified
	}
	if kind == "h3" {
		conn, err := net.ListenPacket("udp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		server := &http3.Server{Handler: httpHandler, TLSConfig: tlsConfig}
		go func() { _ = server.Serve(conn) }()
		t.Cleanup(func() { _ = server.Close(); _ = conn.Close() })
		return conn.LocalAddr().String(), verified
	}
	tlsConfig.NextProtos = []string{"doq"}
	listener, err := quic.ListenAddr("127.0.0.1:0", tlsConfig, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept(context.Background())
			if err != nil {
				return
			}
			go func() {
				defer conn.CloseWithError(0, "")
				for {
					stream, err := conn.AcceptStream(context.Background())
					if err != nil {
						return
					}
					go func() {
						defer stream.Close()
						var size [2]byte
						if _, err := io.ReadFull(stream, size[:]); err != nil {
							return
						}
						body := make([]byte, binary.BigEndian.Uint16(size[:]))
						if _, err := io.ReadFull(stream, body); err != nil {
							return
						}
						request := new(mdns.Msg)
						if request.Unpack(body) != nil {
							return
						}
						answer, _ := subscriptionDNSAnswer(request).Pack()
						binary.BigEndian.PutUint16(size[:], uint16(len(answer)))
						_, _ = stream.Write(append(size[:], answer...))
					}()
				}
			}()
		}
	}()
	return listener.Addr().String(), verified
}

type subscriptionDNSPlatformProbe struct {
	calls atomic.Int32
	err   error
}

func (p *subscriptionDNSPlatformProbe) AutoDetectInterfaceControl(fd int32) error {
	p.calls.Add(1)
	if fd < 0 {
		return errors.New("invalid socket fd")
	}
	return p.err
}

func TestSubscriptionDNSQueriesEveryTransport(t *testing.T) {
	cert, ca := subscriptionDNSCertificate(t)
	for _, kind := range []string{"udp", "tcp", "tls", "https", "quic", "h3"} {
		t.Run(kind, func(t *testing.T) {
			address, verified := subscriptionDNSServer(t, kind, cert)
			host, port, _ := net.SplitHostPort(address)
			number, _ := strconv.Atoi(port)
			options := map[string]any{"type": kind, "server": host, "server_port": number}
			if kind != "udp" && kind != "tcp" {
				options["tls"] = map[string]any{"server_name": "dns.test", "certificate": ca}
			}
			body, _ := json.Marshal(options)
			platform := new(subscriptionDNSPlatformProbe)
			query := NewSubscriptionDNSQuery("", platform)
			defer query.Cancel()
			for _, family := range []string{"ipv4_only", "ipv6_only", "prefer_ipv6"} {
				result, err := query.Lookup(string(body), "node.unresolvable.invalid", family)
				if err != nil {
					t.Fatalf("%s: %v", family, err)
				}
				expected := map[string]string{"ipv4_only": "192.0.2.123", "ipv6_only": "2001:db8::123", "prefer_ipv6": "2001:db8::123\n192.0.2.123"}[family]
				if result != expected {
					t.Fatalf("%s got %q", family, result)
				}
			}
			if platform.calls.Load() == 0 {
				t.Fatal("network binding callback was not called")
			}
			if kind != "udp" && kind != "tcp" && !verified.Load() {
				t.Fatal("original SNI/Host was not preserved")
			}
		})
	}
}
func TestSubscriptionDNSCancelAndBudget(t *testing.T) {
	entered := make(chan struct{}, 2)
	conn, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	go func() {
		buffer := make([]byte, 2048)
		for {
			_, _, err := conn.ReadFrom(buffer)
			if err != nil {
				return
			}
			select {
			case entered <- struct{}{}:
			default:
			}
		}
	}()
	_, port, _ := net.SplitHostPort(conn.LocalAddr().String())
	config := `{"type":"udp","server":"127.0.0.1","server_port":` + port + `}`
	q := NewSubscriptionDNSQuery("", nil)
	done := make(chan error, 1)
	go func() { _, err := q.Lookup(config, "localhost", "ipv4_only"); done <- err }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("query did not enter custom resolver")
	}
	q.Cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("cancel got %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancel did not stop query")
	}
	q = NewSubscriptionDNSQuery("", nil)
	defer q.Cancel()
	start := time.Now()
	result, err := q.Lookup(config, "localhost", "ipv4_only")
	if err == nil || result != "" {
		t.Fatalf("failed custom resolver fell back to system: %q %v", result, err)
	}
	if elapsed := time.Since(start); elapsed < 9*time.Second || elapsed > 12*time.Second {
		t.Fatalf("10s budget: %s", elapsed)
	}
}
func TestSubscriptionDNSProtectRejectionAndBootstrapBoundary(t *testing.T) {
	dir, err := os.MkdirTemp("/tmp", "links-dns-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	path := filepath.Join(dir, "protect")
	var called atomic.Bool
	server := serveProtect(path, func(int) error { called.Store(true); return errors.New("protect rejected") })
	if server == nil {
		t.Fatal("protect server")
	}
	defer server.Close()
	query := NewSubscriptionDNSQuery(path, nil)
	defer query.Cancel()
	_, err = query.Lookup(`{"type":"tcp","server":"127.0.0.1","server_port":53}`, "localhost", "ipv4_only")
	if err == nil || !called.Load() {
		t.Fatalf("protect failure not enforced: %v", err)
	}
	binding := &subscriptionDNSPlatformProbe{err: errors.New("binding failed")}
	boundQuery := NewSubscriptionDNSQuery("", binding)
	defer boundQuery.Cancel()
	if result, err := boundQuery.Lookup(`{"type":"udp","server":"127.0.0.1","server_port":53}`, "localhost", "ipv4_only"); err == nil || result != "" || binding.calls.Load() == 0 {
		t.Fatalf("binding failure ignored: %q %v", result, err)
	}
	_, err = query.Lookup(`{"type":"udp","server":"resolver.invalid"}`, "localhost", "ipv4_only")
	if err == nil || !strings.Contains(err.Error(), "bootstrapped") {
		t.Fatalf("unexpected server hostname lookup: %v", err)
	}
}
