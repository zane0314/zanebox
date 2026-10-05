package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/ech"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	TrySocks5(port int32, username string, password string)
	// TryBoxOutbound 经 mainInstance 路由查询出口（保留现有 JNI ABI）。
	TryBoxOutbound()
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	ctx           context.Context
	cancel        context.CancelFunc
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	trySocks5     bool
	tryH3Direct   bool
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	client.ctx, client.cancel = context.WithCancel(context.Background())
	client.h1h2Client.Timeout = 30 * time.Second
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.DisableKeepAlives = true
	return client
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

func (c *httpClient) TrySocks5(port int32, username string, password string) {
	dialer := new(net.Dialer)
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		// net/http detaches dial cancellation from the request to allow reuse.
		// Bind the SOCKS handshake to client ownership and an explicit budget.
		ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		stopClient := context.AfterFunc(c.ctx, cancel)
		defer stopClient()
		defer cancel()
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			stopCancel := context.AfterFunc(ctx, func() { _ = socksConn.Close() })
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), username, password)
			stopCancel()
			if err != nil || ctx.Err() != nil {
				_ = socksConn.Close()
				if ctx.Err() != nil {
					return nil, ctx.Err()
				}
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			return socksConn, err
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
}

// TryBoxOutbound retains the existing JNI name but enters the live router.
// A direct call to Default().DialContext bypassed DNS and routing/reject rules.
func (c *httpClient) TryBoxOutbound() {
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		b := mainInstance.Load()
		if b == nil || b.Box == nil {
			return nil, errors.New("box not running")
		}
		if network != "tcp" && network != "tcp4" && network != "tcp6" {
			return nil, errors.New("exit probe requires TCP")
		}
		routeMetadata := adapter.InboundContext{
			Network: "tcp", Destination: metadata.ParseSocksaddr(addr),
		}
		for _, inbound := range b.Inbound().Inbounds() {
			if inbound.Type() == C.TypeTun {
				routeMetadata.Inbound = inbound.Tag()
				routeMetadata.InboundType = C.TypeTun
				break
			}
		}
		client, server := net.Pipe()
		routeContext, cancel := context.WithCancel(ctx)
		stop := context.AfterFunc(c.ctx, func() {
			cancel()
			_ = client.Close()
			_ = server.Close()
		})
		go b.Router().RouteConnectionEx(routeContext, server, routeMetadata, func(error) {
			stop()
			cancel()
			_ = server.Close()
		})
		return client, nil
	}
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	req.request = *req.request.WithContext(c.ctx)
	return req
}

func (c *httpClient) Close() {
	c.cancel()
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request http.Request
}

func (r *httpRequest) AllowInsecure() {
	r.tls.InsecureSkipVerify = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
	}
	return
}

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	buffer := bytes.Buffer{}
	buffer.Write(content)
	data := buffer.Bytes()
	r.request.GetBody = func() (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(data)), nil }
	r.request.Body, _ = r.request.GetBody()
	r.request.ContentLength = int64(len(content))
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

func (r *httpRequest) Execute() (HTTPResponse, error) {
	defer device.DeferPanicToError("http execute", func(err error) { log.Println(err) })
	// full direct
	if r.tryH3Direct && !r.trySocks5 {
		return r.doH3Direct()
	}
	response, err := r.h1h2Client.Do(&r.request)
	if err != nil {
		// The returned error may include a credential-bearing subscription URL.
		// Let callers report a safe category instead of duplicating it in logs.
		// trySocks5 && tryH3Direct
		if r.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			log.Println("http execute: socks5 unavailable, falling back to H3 direct")
			return r.doH3Direct()
		}
		return nil, err
	}
	httpResp := &httpResponse{Response: response}
	if response.StatusCode >= 400 {
		return nil, errors.New(httpResp.errorString())
	}
	return httpResp, nil
}

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	request := func(ctx context.Context, transport http.RoundTripper, cleanup func()) (*http.Response, error) {
		cloned := r.request.Clone(ctx)
		if cloned.Body != nil && cloned.GetBody != nil {
			body, err := cloned.GetBody()
			if err != nil {
				cleanup()
				return nil, err
			}
			cloned.Body = body
		}
		response, err := (&http.Client{Transport: transport}).Do(cloned)
		if err != nil {
			cleanup()
			return response, err
		}
		response.Body = &httpCleanupBody{ReadCloser: response.Body, cleanup: cleanup}
		return response, nil
	}
	requests := []requestFunc{
		func(ctx context.Context) (*http.Response, error) {
			transport := &http.Transport{
				DisableKeepAlives: true,
				DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
					conn, err := (&net.Dialer{}).DialContext(ctx, network, addr)
					if err != nil {
						return nil, err
					}
					domain := addr
					if host, _, err := net.SplitHostPort(addr); err == nil {
						domain = host
					}
					config := ech.NewECHClientConfig(domain, &r.tls, gLocalDNSTransport)
					tlsConn, err := config.Client(ctx, conn)
					if err == nil {
						err = tlsConn.HandshakeContext(ctx)
					}
					if err != nil {
						conn.Close()
						return nil, err
					}
					return tlsConn, nil
				},
			}
			return request(ctx, transport, transport.CloseIdleConnections)
		},
	}
	if r.request.URL.Scheme == "https" && (r.request.Method == "GET" || r.request.Method == "HEAD") {
		requests = append(requests, func(ctx context.Context) (*http.Response, error) {
			transport := &http3.Transport{TLSClientConfig: r.tls.Clone(), QUICConfig: &quic.Config{MaxIdleTimeout: 10 * time.Second}}
			return request(ctx, transport, func() { transport.Close() })
		})
	}
	response, err := raceHTTPRequests(r.request.Context(), requests, 10*time.Second)
	if err != nil {
		return nil, err
	}
	return &httpResponse{Response: response}, nil
}

type httpResponse struct {
	*http.Response

	getContentOnce sync.Once
	content        []byte
	contentError   error
}

func (h *httpResponse) errorString() string {
	content, err := h.getContentString()
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	if len(content) > 100 {
		content = content[:100] + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", content)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.Body.Close()
		h.content, h.contentError = io.ReadAll(io.LimitReader(h.Body, (64<<20)+1))
		if len(h.content) > 64<<20 {
			h.content = nil
			h.contentError = errors.New("HTTP response exceeds 64 MiB")
		}
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	defer h.Body.Close()
	file, err := os.CreateTemp(filepath.Dir(path), ".download-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	count, err := io.Copy(file, io.LimitReader(h.Body, (256<<20)+1))
	if err != nil {
		return err
	}
	if count > 256<<20 {
		return errors.New("HTTP download exceeds 256 MiB")
	}
	if err = file.Sync(); err != nil {
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	return os.Rename(file.Name(), path)
}
