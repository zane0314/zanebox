package xhttp_test

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"libcore/protocol/vless"
	"libcore/protocol/vless/xhttp"
	"libcore/protocol/vmess"
)

type directDialer struct{}

func (directDialer) DialContext(ctx context.Context, network string, dest M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, dest.String())
}

type splitDNS struct {
	adapter.DNSRouter
	calls atomic.Int32
}

func (d *splitDNS) Lookup(_ context.Context, domain string, options adapter.DNSQueryOptions) ([]netip.Addr, error) {
	if domain != "download.test" || options.Transport == nil {
		return nil, fmt.Errorf("unexpected resolver for %s", domain)
	}
	d.calls.Add(1)
	return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
}

type splitDNSTransports struct{ adapter.DNSTransportManager }
type splitDNSTransport struct{ adapter.DNSTransport }

func (splitDNSTransports) Transport(tag string) (adapter.DNSTransport, bool) {
	return &splitDNSTransport{}, tag == "fixture"
}

func TestXHTTPDomainDownloadWithLiteralUpload(t *testing.T) {
	for _, protocol := range []string{"vless", "vmess"} {
		t.Run(protocol, func(t *testing.T) {
			up := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, _ = io.Copy(io.Discard, r.Body)
				w.WriteHeader(200)
			}))
			up.EnableHTTP2 = true
			up.StartTLS()
			defer up.Close()
			arrived := make(chan struct{}, 1)
			down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				arrived <- struct{}{}
				w.WriteHeader(200)
				w.(http.Flusher).Flush()
				<-r.Context().Done()
			}))
			defer down.Close()
			_, upPort, _ := net.SplitHostPort(up.Listener.Addr().String())
			_, downPort, _ := net.SplitHostPort(down.Listener.Addr().String())
			raw := fmt.Sprintf(`{"server":"127.0.0.1","server_port":%s,"uuid":"11111111-1111-4111-8111-111111111111","security":"auto","domain_resolver":{"server":"fixture"},"tls":{"enabled":true,"insecure":true,"server_name":"up.example","alpn":["h2"]},"transport":{"type":"xhttp","mode":"packet-up","path":"/up","download":{"server":"download.test","server_port":%s,"path":"/down","mode":"auto"}}}`, upPort, downPort)
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			dns := &splitDNS{}
			ctx = service.ContextWith[adapter.DNSRouter](ctx, dns)
			ctx = service.ContextWith[adapter.DNSTransportManager](ctx, splitDNSTransports{})
			var outbound adapter.Outbound
			var err error
			if protocol == "vless" {
				var options vless.VLESSOutboundOptions
				if err = json.Unmarshal([]byte(raw), &options); err == nil {
					outbound, err = vless.NewOutbound(ctx, nil, log.StdLogger(), "fixture", options)
				}
			} else {
				var options vmess.VMessOutboundOptions
				if err = json.Unmarshal([]byte(raw), &options); err == nil {
					outbound, err = vmess.NewOutbound(ctx, nil, log.StdLogger(), "fixture", options)
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			defer outbound.(io.Closer).Close()
			conn, err := outbound.DialContext(ctx, "tcp", M.ParseSocksaddr("203.0.113.9:80"))
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			if _, err = io.WriteString(conn, "request"); err != nil {
				t.Fatal(err)
			}
			select {
			case <-arrived:
				if dns.calls.Load() == 0 {
					t.Fatal("download bypassed its configured DNS resolver")
				}
			case <-time.After(time.Second):
				t.Fatal("IP upload did not resolve the separate download domain")
			}
		})
	}
}

func (directDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, fmt.Errorf("unexpected packet dial")
}

// Run from native/OwnBoxForAndroid/libcore: go test -race ../../../tests/xhttp_split_test.go
func TestSeparateXHTTPEntrances(t *testing.T) {
	for _, mode := range []string{"packet-up", "stream-up"} {
		for _, secureDownload := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/downloadTLS=%t", mode, secureDownload), func(t *testing.T) {
				payload := "separate-entrances"
				response := "download:" + payload
				uploaded := make(chan string, 1)
				var upCount, downCount atomic.Int32
				up := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					if r.Method != "POST" || r.Host != "up.example" || r.TLS == nil || r.ProtoMajor != 2 || !strings.HasPrefix(r.URL.Path, "/up/") {
						t.Errorf("wrong upload entrance: %s %s %s TLS=%t", r.Method, r.Host, r.URL, r.TLS != nil)
						w.WriteHeader(400)
						return
					}
					body := make([]byte, len(payload))
					if _, err := io.ReadFull(r.Body, body); err != nil || string(body) != payload {
						t.Errorf("upload payload: %v", err)
						return
					}
					upCount.Add(1)
					uploaded <- strings.Split(strings.TrimPrefix(r.URL.Path, "/up/"), "/")[0]
					w.WriteHeader(200)
					w.(http.Flusher).Flush()
					if mode == "stream-up" {
						_, _ = io.Copy(io.Discard, r.Body)
					}
				}))
				up.EnableHTTP2 = true
				up.StartTLS()
				defer up.Close()
				downHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					if r.Method != "GET" || r.Host != "down.example" || (r.TLS != nil) != secureDownload || r.ProtoMajor != 1 || !strings.HasPrefix(r.URL.Path, "/down/") {
						t.Errorf("wrong download entrance: %s %s %s TLS=%t", r.Method, r.Host, r.URL, r.TLS != nil)
						w.WriteHeader(400)
						return
					}
					select {
					case session := <-uploaded:
						if strings.TrimPrefix(r.URL.Path, "/down/") != session {
							t.Errorf("upload/download sessions differ")
							return
						}
						downCount.Add(1)
						_, _ = io.WriteString(w, response)
						w.(http.Flusher).Flush()
						<-r.Context().Done()
					case <-r.Context().Done():
					}
				})
				var down *httptest.Server
				if secureDownload {
					down = httptest.NewTLSServer(downHandler)
				} else {
					down = httptest.NewServer(downHandler)
				}
				defer down.Close()
				host, portText, _ := net.SplitHostPort(down.Listener.Addr().String())
				port, _ := strconv.Atoi(portText)
				var options xhttp.V2RayXHTTPOptions
				raw := fmt.Sprintf(`{"mode":%q,"host":"up.example","path":"/up","x_padding_bytes":"0","download":{"server":%q,"server_port":%d,"host":"down.example","path":"/down","mode":"auto","x_padding_bytes":"0"}}`, mode, host, port)
				if err := json.Unmarshal([]byte(raw), &options); err != nil {
					t.Fatal(err)
				}
				if secureDownload {
					options.Download.TLS = &option.OutboundTLSOptions{Enabled: true, ServerName: "down.example", Insecure: true, ALPN: []string{"http/1.1"}}
				}
				ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
				defer cancel()
				upTLS, err := tls.NewClient(ctx, log.StdLogger(), "up.example", option.OutboundTLSOptions{Enabled: true, ServerName: "up.example", Insecure: true, ALPN: []string{"h2"}})
				if err != nil {
					t.Fatal(err)
				}
				transport, err := xhttp.NewClient(ctx, directDialer{}, M.ParseSocksaddr(up.Listener.Addr().String()), options, upTLS)
				if err != nil {
					t.Fatal(err)
				}
				defer transport.Close()
				conn, err := transport.DialContext(ctx)
				if err != nil {
					t.Fatal(err)
				}
				defer conn.Close()
				transferred := make(chan error, 1)
				go func() {
					if _, err := io.WriteString(conn, payload); err != nil {
						transferred <- err
						return
					}
					body := make([]byte, len(response))
					_, err := io.ReadFull(conn, body)
					if err == nil && string(body) != response {
						err = fmt.Errorf("unexpected download payload: %q", body)
					}
					transferred <- err
				}()
				select {
				case err := <-transferred:
					if err != nil {
						t.Fatal(err)
					}
				case <-ctx.Done():
					t.Fatal("separate entrances did not transfer the payload")
				}
				if upCount.Load() != 1 || downCount.Load() != 1 {
					t.Fatalf("entrance counts = %d/%d", upCount.Load(), downCount.Load())
				}
			})
		}
	}
}
