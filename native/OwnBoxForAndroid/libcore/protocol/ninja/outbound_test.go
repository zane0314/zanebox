package ninja

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	stdtls "crypto/tls"
	"encoding/binary"
	"encoding/pem"
	"fmt"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/transport/v2raywebsocket"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/ws"
	"io"
	"lukechampine.com/blake3"
	"net"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
)

func TestOutboundRequiresTLS(t *testing.T) {
	_, err := NewOutbound(context.Background(), nil, nil, "test", OutboundOptions{ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: 443}, Method: "aes-128-gcm", Password: "test-password", NodePassword: "test-node"})
	if err == nil {
		t.Fatal("plaintext outbound accepted")
	}
}

func validOutboundOptions() OutboundOptions {
	return OutboundOptions{ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: 443}, Method: "aes-128-gcm", Password: "test-password", NodePassword: "test-node", TLS: &option.OutboundTLSOptions{Enabled: true}}
}
func makeTestOutbound(t *testing.T, o OutboundOptions) *Outbound {
	t.Helper()
	h, e := NewOutbound(context.Background(), nil, logger.NOP(), "test", o)
	if e != nil {
		t.Fatal(e)
	}
	return h.(*Outbound)
}
func TestOutboundOptions(t *testing.T) {
	for name, mutate := range map[string]func(*OutboundOptions){
		"method":           func(o *OutboundOptions) { o.Method = "aes-256-gcm" },
		"password":         func(o *OutboundOptions) { o.Password = "" },
		"node-password":    func(o *OutboundOptions) { o.NodePassword = "" },
		"server":           func(o *OutboundOptions) { o.Server = "" },
		"bad-server":       func(o *OutboundOptions) { o.Server = "bad/host" },
		"server-with-port": func(o *OutboundOptions) { o.Server = "example.com:443" },
		"port":             func(o *OutboundOptions) { o.ServerPort = 0 },
		"tls-off":          func(o *OutboundOptions) { o.TLS.Enabled = false },
		"transport":        func(o *OutboundOptions) { o.Transport = &option.V2RayTransportOptions{Type: "http"} },
		"uot":              func(o *OutboundOptions) { o.UDPOverTCP = &option.UDPOverTCPOptions{Enabled: true, Version: 1} },
		"pass-missing":     func(o *OutboundOptions) { o.Pass = true },
		"pass-password":    func(o *OutboundOptions) { o.Pass = true; o.PassOptions = &PassOptions{Method: "aes-128-gcm"} },
		"pass-padding":     func(o *OutboundOptions) { o.Pass = true; p := passTestOptions(); p.PaddingMin = -1; o.PassOptions = &p },
	} {
		t.Run(name, func(t *testing.T) {
			o := validOutboundOptions()
			mutate(&o)
			if _, e := NewOutbound(context.Background(), nil, logger.NOP(), "test", o); e == nil {
				t.Fatal("invalid configuration accepted")
			}
		})
	}
	for _, version := range []uint8{0, 2} {
		o := validOutboundOptions()
		o.UDPOverTCP = &option.UDPOverTCPOptions{Enabled: true, Version: version}
		o.Pass = true
		p := passTestOptions()
		p.PaddingMode = ""
		p.PaddingRandomMax = 0
		o.PassOptions = &p
		h := makeTestOutbound(t, o)
		if !slices.Contains(h.Network(), "udp") || !h.udpEnabled || h.options.PassOptions.PaddingRandomMax != 128 {
			t.Fatal("options normalization failed")
		}
	}
	h := makeTestOutbound(t, validOutboundOptions())
	if slices.Contains(h.Network(), "udp") {
		t.Fatal("disabled UDP advertised")
	}
	for _, network := range []string{"udp", "bogus"} {
		if _, e := h.DialContext(context.Background(), network, M.ParseSocksaddr("192.0.2.1:53")); e == nil {
			t.Fatal("unsupported network accepted")
		}
	}
	if _, e := h.ListenPacket(context.Background(), M.ParseSocksaddr("192.0.2.1:53")); e == nil {
		t.Fatal("disabled UOT accepted")
	}
	registry := outbound.NewRegistry()
	RegisterOutbound(registry)
	raw, ok := registry.CreateOptions(Type)
	if !ok {
		t.Fatal("registration missing")
	}
	if _, ok = raw.(*OutboundOptions); !ok {
		t.Fatal("wrong registered option type")
	}
}

// These stream peers use the independent record builders from the protocol tests.
type passOutboundPeer struct {
	net.Conn
	input, output *passPeer
	pending       []byte
}

func (p *passOutboundPeer) Read(b []byte) (int, error) {
	for len(p.pending) == 0 {
		data, _, e := p.input.read(p.Conn)
		if e != nil {
			return 0, e
		}
		p.pending = data
	}
	n := copy(b, p.pending)
	p.pending = p.pending[n:]
	return n, nil
}
func (p *passOutboundPeer) Write(b []byte) (int, error) {
	var wire []byte
	if p.output == nil {
		nonce := bytes.Repeat([]byte{0x42}, 12)
		p.output = newPassPeer("outer-pass-password", nonce)
		wire = append(wire, nonce...)
	}
	wire = append(wire, p.output.frame(b, 9)...)
	_, e := io.Copy(p.Conn, bytes.NewReader(wire))
	if e != nil {
		return 0, e
	}
	return len(b), nil
}
func acceptPassPeer(c net.Conn) (net.Conn, error) {
	nonce := make([]byte, 12)
	if _, e := io.ReadFull(c, nonce); e != nil {
		return nil, e
	}
	p := &passOutboundPeer{Conn: c, input: newPassPeer("outer-pass-password", nonce)}
	prefix := make([]byte, 17)
	if _, e := io.ReadFull(p, prefix); e != nil {
		return nil, e
	}
	token := passPeerKey("outer-pass-password", passInfo, []byte("outer-pass-password"))
	if !bytes.Equal(prefix[:16], token) || prefix[16] != 1 {
		return nil, fmt.Errorf("invalid PASS password or prefix")
	}
	return p, nil
}

type coreOutboundPeer struct {
	net.Conn
	input                     cipher.AEAD
	readCounter, writeCounter uint64
	salt                      []byte
	pending                   []byte
}

func acceptCorePeer(conn net.Conn) (*coreOutboundPeer, M.Socksaddr, error) {
	return acceptCorePeerNetwork(conn, 1)
}
func acceptCorePeerNetwork(conn net.Conn, network byte) (*coreOutboundPeer, M.Socksaddr, error) {
	salt, auth := make([]byte, 16), make([]byte, 16)
	if _, e := io.ReadFull(conn, salt); e != nil {
		return nil, M.Socksaddr{}, e
	}
	if _, e := io.ReadFull(conn, auth); e != nil {
		return nil, M.Socksaddr{}, e
	}
	node := peerKey("de6dbaddc4e0dbaaeb65778b44e6fdac", []byte("test-node"))
	block, _ := aes.NewCipher(peerKey("e14d197ae519bdd2d9a75ae94df7e208", append(node, salt...)))
	sum := blake3.Sum512(peerKey("de6dbaddc4e0dbaaeb65778b44e6fdac", []byte("test-password")))
	want := make([]byte, 16)
	block.Encrypt(want, sum[:16])
	if !bytes.Equal(auth, want) {
		return nil, M.Socksaddr{}, fmt.Errorf("wrong node authentication")
	}
	a := peerCipher(salt)
	h, e := peerOpen(conn, a, 0, 12)
	if e != nil {
		return nil, M.Socksaddr{}, e
	}
	if h[0] != 0 || h[1] != network {
		return nil, M.Socksaddr{}, fmt.Errorf("wrong first frame type")
	}
	payload, e := peerOpen(conn, a, 1, int(binary.BigEndian.Uint16(h[10:])))
	if e != nil {
		return nil, M.Socksaddr{}, e
	}
	r := bytes.NewReader(payload)
	dest, e := M.SocksaddrSerializer.ReadAddrPort(r)
	if e != nil {
		return nil, dest, e
	}
	var pad uint16
	if e = binary.Read(r, binary.BigEndian, &pad); e != nil {
		return nil, dest, e
	}
	if int(pad) > r.Len() {
		return nil, dest, fmt.Errorf("bad initial padding")
	}
	r.Seek(int64(pad), io.SeekCurrent)
	firstData := make([]byte, r.Len())
	io.ReadFull(r, firstData)
	if network == 1 && len(firstData) != 0 {
		return nil, dest, fmt.Errorf("TCP first frame not empty")
	}
	return &coreOutboundPeer{Conn: conn, input: a, readCounter: 2, salt: salt, pending: firstData}, dest, nil
}
func (p *coreOutboundPeer) Read(b []byte) (int, error) {
	for len(p.pending) == 0 {
		h, e := peerOpen(p.Conn, p.input, p.readCounter, 4)
		if e != nil {
			return 0, e
		}
		p.readCounter++
		size, pad := int(binary.BigEndian.Uint16(h)), int(binary.BigEndian.Uint16(h[2:]))
		if pad > size {
			return 0, fmt.Errorf("bad chunk length")
		}
		body, e := peerOpen(p.Conn, p.input, p.readCounter, size)
		if e != nil {
			return 0, e
		}
		p.readCounter++
		p.pending = body[:size-pad]
	}
	n := copy(b, p.pending)
	p.pending = p.pending[n:]
	return n, nil
}
func (p *coreOutboundPeer) Write(b []byte) (int, error) {
	const pad = 3
	salt := bytes.Repeat([]byte{0x5a}, 16)
	a := peerCipher(salt)
	var h, wire []byte
	if p.writeCounter == 0 {
		h = make([]byte, 29)
		h[0] = 1
		copy(h[9:25], p.salt)
		binary.BigEndian.PutUint16(h[25:], uint16(len(b)+pad))
		binary.BigEndian.PutUint16(h[27:], pad)
		wire = append(wire, salt...)
	} else {
		h = []byte{0, 0, 0, pad}
		binary.BigEndian.PutUint16(h, uint16(len(b)+pad))
	}
	wire = append(wire, peerSeal(a, p.writeCounter, h)...)
	p.writeCounter++
	wire = append(wire, peerSeal(a, p.writeCounter, append(append([]byte(nil), b...), 0, 0, 0))...)
	p.writeCounter++
	_, e := io.Copy(p.Conn, bytes.NewReader(wire))
	if e != nil {
		return 0, e
	}
	return len(b), nil
}

func localTLSOptions(t *testing.T, address string, certificate []byte) OutboundOptions {
	t.Helper()
	o := validOutboundOptions()
	addr := M.ParseSocksaddr(address)
	o.Server = addr.AddrString()
	o.ServerPort = addr.Port
	o.TLS.Certificate = []string{string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certificate}))}
	return o
}
func localNinjaServer(t *testing.T, websocket bool, handle func(net.Conn) error) (OutboundOptions, <-chan error) {
	t.Helper()
	done := make(chan error, 1)
	if websocket {
		server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Host != "ninja.example" || r.URL.RequestURI() != "/ninja" {
				done <- fmt.Errorf("WS Host/path mismatch: host=%q uri=%q", r.Host, r.URL.RequestURI())
				http.Error(w, "bad request", 400)
				return
			}
			if r.TLS.NegotiatedProtocol != "http/1.1" {
				done <- fmt.Errorf("wrong WS ALPN")
				return
			}
			conn, _, _, e := ws.UpgradeHTTP(r, w)
			if e != nil {
				done <- e
				return
			}
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(5 * time.Second))
			done <- handle(v2raywebsocket.NewConn(conn, nil, ws.StateServerSide))
		}))
		server.StartTLS()
		t.Cleanup(server.Close)
		o := localTLSOptions(t, server.Listener.Addr().String(), server.Certificate().Raw)
		o.Transport = &option.V2RayTransportOptions{Type: "ws", WebsocketOptions: option.V2RayWebsocketOptions{Path: "/ninja", Headers: badoption.HTTPHeader{"Host": []string{"ninja.example"}}}}
		return o, done
	}
	seed := httptest.NewTLSServer(http.NotFoundHandler())
	certificate := seed.TLS.Certificates[0]
	der := seed.Certificate().Raw
	seed.Close()
	listener, e := stdtls.Listen("tcp", "127.0.0.1:0", &stdtls.Config{Certificates: []stdtls.Certificate{certificate}, NextProtos: []string{"http/1.1"}})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		conn, e := listener.Accept()
		if e != nil {
			done <- e
			return
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(5 * time.Second))
		tlsConn := conn.(*stdtls.Conn)
		if e = tlsConn.Handshake(); e != nil {
			done <- e
			return
		}
		if tlsConn.ConnectionState().NegotiatedProtocol != "http/1.1" {
			done <- fmt.Errorf("wrong TCP ALPN")
			return
		}
		done <- handle(conn)
	}()
	return localTLSOptions(t, listener.Addr().String(), der), done
}
func testDone(t *testing.T, done <-chan error) {
	t.Helper()
	select {
	case e := <-done:
		if e != nil {
			t.Fatal(e)
		}
	case <-time.After(6 * time.Second):
		t.Fatal("local peer did not finish")
	}
}
func TestOutboundLocalTLSServerFirst(t *testing.T) {
	for _, websocket := range []bool{false, true} {
		for _, pass := range []bool{false, true} {
			t.Run(fmt.Sprintf("ws=%v/pass=%v", websocket, pass), func(t *testing.T) {
				o, done := localNinjaServer(t, websocket, func(conn net.Conn) error {
					var e error
					if pass {
						conn, e = acceptPassPeer(conn)
						if e != nil {
							return e
						}
					}
					peer, dest, e := acceptCorePeer(conn)
					if e != nil {
						return e
					}
					if dest.String() != "example.com:443" {
						return fmt.Errorf("wrong destination %v", dest)
					}
					if _, e = peer.Write([]byte("server-first")); e != nil {
						return e
					}
					b := make([]byte, 5)
					if _, e = io.ReadFull(peer, b); e != nil {
						return e
					}
					if string(b) != "hello" {
						return fmt.Errorf("bad client data")
					}
					_, e = peer.Write([]byte("reply"))
					return e
				})
				if pass {
					p := passTestOptions()
					p.Password = "outer-pass-password"
					o.Pass = true
					o.PassOptions = &p
				}
				h := makeTestOutbound(t, o)
				ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
				conn, e := h.DialContext(ctx, "tcp", M.ParseSocksaddr("example.com:443"))
				if e != nil {
					cancel()
					select {
					case peerErr := <-done:
						t.Fatalf("dial: %v; peer: %v", e, peerErr)
					default:
						t.Fatal(e)
					}
				}
				defer conn.Close()
				// Cancelling a completed dial must leave a reusable connection alive.
				cancel()
				b := make([]byte, 12)
				if _, e = io.ReadFull(conn, b); e != nil || string(b) != "server-first" {
					t.Fatalf("server-first %q %v", b, e)
				}
				if _, e = conn.Write([]byte("hello")); e != nil {
					t.Fatal(e)
				}
				b = make([]byte, 5)
				if _, e = io.ReadFull(conn, b); e != nil || string(b) != "reply" {
					t.Fatalf("reply %q %v", b, e)
				}
				testDone(t, done)
			})
		}
	}
}
func TestOutboundNativeUDP(t *testing.T) {
	for _, transport := range []struct{ ws, pass bool }{{false, false}, {false, true}, {true, false}, {true, true}} {
		for _, packet := range []bool{false, true} {
			t.Run(fmt.Sprintf("ws=%v/pass=%v/packet=%v", transport.ws, transport.pass, packet), func(t *testing.T) {
				o, done := localNinjaServer(t, transport.ws, func(conn net.Conn) error {
					if transport.pass {
						var e error
						conn, e = acceptPassPeer(conn)
						if e != nil {
							return e
						}
					}
					peer, dest, e := acceptCorePeerNetwork(conn, 2)
					if e != nil {
						return e
					}
					if dest.String() != "192.0.2.1:53" {
						return fmt.Errorf("wrong native UDP destination %v", dest)
					}
					b := make([]byte, 65535)
					n, e := peer.Read(b)
					if e != nil {
						return e
					}
					if string(b[:n]) != "udp payload" {
						return fmt.Errorf("wrong native UDP payload")
					}
					var response bytes.Buffer
					if e = M.SocksaddrSerializer.WriteAddrPort(&response, dest); e != nil {
						return e
					}
					response.Write(b[:n])
					_, e = peer.Write(response.Bytes())
					return e
				})
				o.UDPOverTCP = &option.UDPOverTCPOptions{Enabled: true, Version: 2}
				if transport.pass {
					p := passTestOptions()
					p.Password = "outer-pass-password"
					o.Pass = true
					o.PassOptions = &p
				}
				h := makeTestOutbound(t, o)
				ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
				defer cancel()
				dest := M.ParseSocksaddr("192.0.2.1:53")
				payload := []byte("udp payload")
				if packet {
					conn, e := h.ListenPacket(ctx, dest)
					if e != nil {
						t.Fatal(e)
					}
					defer conn.Close()
					if _, e = conn.WriteTo(payload, dest.UDPAddr()); e != nil {
						t.Fatal(e)
					}
					b := make([]byte, 64)
					n, addr, e := conn.ReadFrom(b)
					if e != nil || string(b[:n]) != string(payload) || M.SocksaddrFromNet(addr) != dest {
						t.Fatalf("UDP echo %q %v %v", b[:n], addr, e)
					}
				} else {
					conn, e := h.DialContext(ctx, "udp", dest)
					if e != nil {
						t.Fatal(e)
					}
					defer conn.Close()
					if _, e = conn.Write(payload); e != nil {
						t.Fatal(e)
					}
					b := make([]byte, 64)
					n, e := conn.Read(b)
					if e != nil || string(b[:n]) != string(payload) {
						t.Fatalf("UDP echo %q %v", b[:n], e)
					}
				}
				testDone(t, done)
			})
		}
	}
}

func TestOutboundCancellation(t *testing.T) {
	t.Run("tls-handshake", func(t *testing.T) {
		listener, e := net.Listen("tcp", "127.0.0.1:0")
		if e != nil {
			t.Fatal(e)
		}
		defer listener.Close()
		accepted := make(chan struct{})
		closed := make(chan error, 1)
		go func() {
			conn, e := listener.Accept()
			if e != nil {
				closed <- e
				return
			}
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(3 * time.Second))
			close(accepted)
			_, e = io.Copy(io.Discard, conn)
			closed <- e
		}()
		o := validOutboundOptions()
		addr := M.ParseSocksaddr(listener.Addr().String())
		o.Server = addr.AddrString()
		o.ServerPort = addr.Port
		h := makeTestOutbound(t, o)
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		done := make(chan error, 1)
		go func() {
			conn, e := h.DialContext(ctx, "tcp", M.ParseSocksaddr("example.com:443"))
			if conn != nil {
				conn.Close()
			}
			done <- e
		}()
		select {
		case <-accepted:
		case <-time.After(3 * time.Second):
			t.Fatal("no TCP connection")
		}
		cancel()
		select {
		case e := <-done:
			if e == nil {
				t.Fatal("cancelled TLS dial succeeded")
			}
		case <-time.After(2 * time.Second):
			t.Fatal("TLS cancellation did not stop dial")
		}
		testDone(t, closed)
	})
	t.Run("websocket-upgrade", func(t *testing.T) {
		accepted := make(chan struct{})
		closed := make(chan error, 1)
		server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			close(accepted)
			select {
			case <-r.Context().Done():
				closed <- nil
			case <-time.After(3 * time.Second):
				closed <- fmt.Errorf("WS raw connection remained open")
			}
		}))
		defer server.Close()
		o := localTLSOptions(t, server.Listener.Addr().String(), server.Certificate().Raw)
		o.Transport = &option.V2RayTransportOptions{Type: "ws"}
		h := makeTestOutbound(t, o)
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		done := make(chan error, 1)
		go func() {
			conn, e := h.DialContext(ctx, "tcp", M.ParseSocksaddr("example.com:443"))
			if conn != nil {
				conn.Close()
			}
			done <- e
		}()
		select {
		case <-accepted:
		case <-time.After(3 * time.Second):
			t.Fatal("no WS upgrade")
		}
		cancel()
		select {
		case e := <-done:
			if e == nil {
				t.Fatal("cancelled WS dial succeeded")
			}
		case <-time.After(2 * time.Second):
			t.Fatal("WS cancellation did not stop upgrade")
		}
		testDone(t, closed)
	})
}

type testPipeDialer struct {
	N.Dialer
	conn net.Conn
}

func (d *testPipeDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return d.conn, nil
}
func TestOutboundCancellationDuringFirstFrame(t *testing.T) {
	seed := httptest.NewTLSServer(http.NotFoundHandler())
	certificate := seed.TLS.Certificates[0]
	der := seed.Certificate().Raw
	seed.Close()
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	o := localTLSOptions(t, "127.0.0.1:443", der)
	h := makeTestOutbound(t, o)
	h.dialer = &testPipeDialer{conn: a}
	ready, release := make(chan struct{}), make(chan struct{})
	defer close(release)
	closed := make(chan error, 1)
	go func() {
		server := stdtls.Server(b, &stdtls.Config{Certificates: []stdtls.Certificate{certificate}, NextProtos: []string{"http/1.1"}})
		if e := server.Handshake(); e != nil {
			closed <- e
			return
		}
		close(ready)
		<-release
		_, e := server.Read(make([]byte, 1))
		if e == nil {
			closed <- fmt.Errorf("first write did not cancel")
		} else {
			closed <- nil
		}
	}()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		conn, e := h.DialContext(ctx, "tcp", M.ParseSocksaddr("example.com:443"))
		if conn != nil {
			conn.Close()
		}
		done <- e
	}()
	select {
	case <-ready:
	case <-time.After(3 * time.Second):
		t.Fatal("TLS handshake not ready")
	}
	cancel()
	select {
	case e := <-done:
		if e == nil {
			t.Fatal("first-frame cancellation ignored")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("first-frame write remained blocked")
	}
	// The pipe's peer must see the underlying socket close, without consuming a record.
	if _, e := b.Write([]byte{1}); e == nil {
		t.Fatal("raw pipe remained open")
	}
}
func TestOutboundClientClose(t *testing.T) {
	for _, websocket := range []bool{false, true} {
		t.Run(fmt.Sprintf("ws=%v", websocket), func(t *testing.T) {
			o, done := localNinjaServer(t, websocket, func(conn net.Conn) error {
				peer, _, e := acceptCorePeer(conn)
				if e != nil {
					return e
				}
				if _, e = peer.Write([]byte("ready")); e != nil {
					return e
				}
				n, e := peer.Read(make([]byte, 1))
				if n != 0 || e == nil {
					return fmt.Errorf("client close not observed")
				}
				return nil
			})
			h := makeTestOutbound(t, o)
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			conn, e := h.DialContext(ctx, "tcp", M.ParseSocksaddr("example.com:443"))
			if e != nil {
				t.Fatal(e)
			}
			b := make([]byte, 5)
			if _, e = io.ReadFull(conn, b); e != nil {
				conn.Close()
				t.Fatal(e)
			}
			if e = conn.Close(); e != nil {
				t.Fatal(e)
			}
			testDone(t, done)
		})
	}
}

func TestOutboundDefaultChromeAndExplicitTLS(t *testing.T) {
	o := validOutboundOptions()
	h := makeTestOutbound(t, o)
	if fmt.Sprintf("%T", h.tlsConfig) != "*tls.UTLSClientConfig" {
		t.Fatalf("default TLS does not use uTLS: %T", h.tlsConfig)
	}
	if !slices.Equal(h.tlsConfig.NextProtos(), []string{"http/1.1"}) {
		t.Fatal("default ALPN changed")
	}
	o.TLS.UTLS = &option.OutboundUTLSOptions{Enabled: false}
	h = makeTestOutbound(t, o)
	if fmt.Sprintf("%T", h.tlsConfig) != "*tls.STDClientConfig" {
		t.Fatalf("explicit TLS setting not preserved: %T", h.tlsConfig)
	}
}
