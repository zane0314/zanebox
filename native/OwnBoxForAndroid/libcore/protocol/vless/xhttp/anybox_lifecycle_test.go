package xhttp

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	singtls "github.com/sagernet/sing-box/common/tls"
	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/net/http2"
	Xbadoption "libcore/protocol/vless/internal/xray/badoption"
)

type anyboxLifecycleConn struct {
	retired atomic.Bool
	closes  atomic.Int32
}

func (c *anyboxLifecycleConn) IsClosed() bool {
	return c.retired.Load()
}

func (c *anyboxLifecycleConn) Close() error {
	c.closes.Add(1)
	return nil
}

func TestAnyBoxXmuxManagerClosesActiveAndRetiredClients(t *testing.T) {
	var created []*anyboxLifecycleConn
	manager := NewXmuxManager(V2RayXHTTPXmuxOptions{}, func() XmuxConn {
		conn := &anyboxLifecycleConn{}
		created = append(created, conn)
		return conn
	})

	first := manager.GetXmuxClient(context.Background())
	firstConn := first.XmuxConn.(*anyboxLifecycleConn)
	first.OpenUsage.Store(1)
	firstConn.retired.Store(true)
	second := manager.GetXmuxClient(context.Background())
	if second == first {
		t.Fatal("retired client was reused")
	}
	if firstConn.closes.Load() != 0 {
		t.Fatal("active retired client closed before its last use")
	}
	first.Release()
	if firstConn.closes.Load() != 1 {
		t.Fatalf("active retired client close count = %d, want 1", firstConn.closes.Load())
	}

	var closeWG sync.WaitGroup
	for i := 0; i < 8; i++ {
		closeWG.Add(1)
		go func() {
			defer closeWG.Done()
			if err := manager.Close(); err != nil {
				t.Errorf("manager close: %v", err)
			}
		}()
	}
	closeWG.Wait()
	if got := created[1].closes.Load(); got != 1 {
		t.Fatalf("active client close count = %d, want 1", got)
	}

	third := manager.GetXmuxClient(context.Background())
	if third == second {
		t.Fatal("manager did not create a fresh client after reset")
	}
	if err := manager.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestAnyBoxClientCloseAllowsRedial(t *testing.T) {
	var primaryCreated, downloadCreated []*anyboxLifecycleConn
	primary := NewXmuxManager(V2RayXHTTPXmuxOptions{}, func() XmuxConn {
		conn := &anyboxLifecycleConn{}
		primaryCreated = append(primaryCreated, conn)
		return conn
	})
	download := NewXmuxManager(V2RayXHTTPXmuxOptions{}, func() XmuxConn {
		conn := &anyboxLifecycleConn{}
		downloadCreated = append(downloadCreated, conn)
		return conn
	})
	client := &Client{xmuxManager: primary, xmuxManager2: download}
	oldPrimary := primary.GetXmuxClient(context.Background())
	oldDownload := download.GetXmuxClient(context.Background())

	var closeWG sync.WaitGroup
	for i := 0; i < 8; i++ {
		closeWG.Add(1)
		go func() {
			defer closeWG.Done()
			_ = client.Close()
		}()
	}
	closeWG.Wait()
	if primaryCreated[0].closes.Load() != 1 || downloadCreated[0].closes.Load() != 1 {
		t.Fatalf("client close counts = %d/%d, want 1/1", primaryCreated[0].closes.Load(), downloadCreated[0].closes.Load())
	}

	newPrimary := primary.GetXmuxClient(context.Background())
	newDownload := download.GetXmuxClient(context.Background())
	if newPrimary == oldPrimary || newDownload == oldDownload {
		t.Fatal("client close did not reset both xmux pools")
	}
	_ = client.Close()
}

type anyboxUploadClient struct {
	closed   atomic.Bool
	closes   atomic.Int32
	posts    atomic.Int32
	inFlight atomic.Int32
}

func (c *anyboxUploadClient) IsClosed() bool {
	return c.closed.Load()
}

func (c *anyboxUploadClient) Close() error {
	c.closed.Store(true)
	c.closes.Add(1)
	return nil
}

func (c *anyboxUploadClient) OpenStream(context.Context, string, io.Reader, bool) (io.ReadCloser, net.Addr, net.Addr, error) {
	return io.NopCloser(strings.NewReader("")), nil, nil, nil
}

func (c *anyboxUploadClient) PostPacket(context.Context, string, io.Reader, int64) error {
	c.posts.Add(1)
	c.inFlight.Add(1)
	defer c.inFlight.Add(-1)
	return nil
}

type anyboxUploadFactory struct {
	mu         sync.Mutex
	clients    []*anyboxUploadClient
	blockIndex int
	blocked    chan struct{}
	release    chan struct{}
}

func (f *anyboxUploadFactory) newClient() XmuxConn {
	client := new(anyboxUploadClient)
	f.mu.Lock()
	index := len(f.clients) + 1
	f.clients = append(f.clients, client)
	blocking := index == f.blockIndex
	blocked, release := f.blocked, f.release
	if blocking {
		close(blocked)
	}
	f.mu.Unlock()
	if blocking {
		<-release
	}
	return client
}

func (f *anyboxUploadFactory) prepareBlockedNext() (<-chan struct{}, chan<- struct{}) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.blockIndex = len(f.clients) + 1
	f.blocked = make(chan struct{})
	f.release = make(chan struct{})
	return f.blocked, f.release
}

func (f *anyboxUploadFactory) snapshot() []*anyboxUploadClient {
	f.mu.Lock()
	defer f.mu.Unlock()
	clients := make([]*anyboxUploadClient, len(f.clients))
	copy(clients, f.clients)
	return clients
}

func TestAnyBoxUploadRotationKeepsPoolBoundedAndCloseSafe(t *testing.T) {
	factory := new(anyboxUploadFactory)
	manager := NewXmuxManager(V2RayXHTTPXmuxOptions{
		HMaxRequestTimes: Xbadoption.Range{From: 1, To: 1},
	}, factory.newClient)
	options := &V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode:                 "packet-up",
		ScMaxEachPostBytes:   Xbadoption.Range{From: 16384, To: 16384},
		ScMinPostsIntervalMs: Xbadoption.Range{From: 0, To: 1},
	}}
	requestURL := func(string) url.URL {
		return url.URL{Scheme: "http", Host: "local", Path: "/xhttp/"}
	}
	getHTTPClient := func() (DialerClient, *XmuxClient) {
		xmuxClient := manager.AcquireXmuxClient(context.Background())
		return xmuxClient.XmuxConn.(DialerClient), xmuxClient
	}
	client := &Client{
		options:        options,
		getRequestURL:  requestURL,
		getRequestURL2: requestURL,
		getHTTPClient:  getHTTPClient,
		getHTTPClient2: getHTTPClient,
		xmuxManager:    manager,
	}
	rawConn, err := client.DialContext(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	conn := rawConn.(*splitConn)
	defer conn.Close()

	waitForPoolBound := func() {
		t.Helper()
		deadline := time.Now().Add(3 * time.Second)
		for time.Now().Before(deadline) {
			manager.mtx.Lock()
			active, retired := len(manager.xmuxClients), len(manager.retired)
			manager.mtx.Unlock()
			if active <= 1 && retired <= 1 {
				return
			}
			time.Sleep(time.Millisecond)
		}
		manager.mtx.Lock()
		active, retired := len(manager.xmuxClients), len(manager.retired)
		manager.mtx.Unlock()
		t.Fatalf("xmux pool grew after completed post: active=%d retired=%d", active, retired)
	}
	waitForPosts := func(want int32) {
		t.Helper()
		deadline := time.Now().Add(3 * time.Second)
		var posts, inFlight int32
		for time.Now().Before(deadline) {
			posts, inFlight = 0, 0
			for _, uploadClient := range factory.snapshot() {
				posts += uploadClient.posts.Load()
				inFlight += uploadClient.inFlight.Load()
			}
			if posts >= want && inFlight == 0 {
				return
			}
			time.Sleep(time.Millisecond)
		}
		t.Fatalf("completed POST count = %d, in-flight = %d, want at least %d and zero in-flight", posts, inFlight, want)
	}

	const rounds = 24
	for i := 0; i < rounds; i++ {
		if _, err := conn.Write(make([]byte, 8192)); err != nil {
			t.Fatalf("upload round %d: %v", i, err)
		}
		waitForPosts(int32(i + 1))
		waitForPoolBound()
	}
	var completedPosts int32
	for _, uploadClient := range factory.snapshot() {
		completedPosts += uploadClient.posts.Load()
	}
	if completedPosts < rounds {
		t.Fatalf("completed POST count = %d, want at least %d", completedPosts, rounds)
	}

	blocked, release := factory.prepareBlockedNext()
	writeDone := make(chan error, 1)
	go func() {
		_, writeErr := conn.Write(make([]byte, 8192))
		writeDone <- writeErr
	}()
	select {
	case <-blocked:
	case <-time.After(3 * time.Second):
		t.Fatal("upload rotation did not reach replacement client")
	}
	select {
	case writeErr := <-writeDone:
		if writeErr != nil {
			t.Fatal(writeErr)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("upload writer did not finish before close handoff")
	}
	closeDone := make(chan struct{})
	go func() {
		_ = conn.Close()
		close(closeDone)
	}()
	close(release)
	select {
	case <-closeDone:
	case <-time.After(3 * time.Second):
		t.Fatal("connection close remained blocked during client handoff")
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	for i, uploadClient := range factory.snapshot() {
		if got := uploadClient.closes.Load(); got != 1 {
			t.Errorf("client %d close count = %d, want 1", i, got)
		}
	}
}

type anyboxHandshakeTLSConfig struct {
	config         *tls.Config
	handshakeLimit time.Duration
}

func (c *anyboxHandshakeTLSConfig) ServerName() string {
	return c.config.ServerName
}

func (c *anyboxHandshakeTLSConfig) SetServerName(serverName string) {
	c.config.ServerName = serverName
}

func (c *anyboxHandshakeTLSConfig) NextProtos() []string {
	return c.config.NextProtos
}

func (c *anyboxHandshakeTLSConfig) SetNextProtos(nextProtos []string) {
	c.config.NextProtos = nextProtos
}

func (c *anyboxHandshakeTLSConfig) HandshakeTimeout() time.Duration {
	return c.handshakeLimit
}

func (c *anyboxHandshakeTLSConfig) SetHandshakeTimeout(timeout time.Duration) {
	c.handshakeLimit = timeout
}

func (c *anyboxHandshakeTLSConfig) STDConfig() (*singtls.STDConfig, error) {
	return c.config, nil
}

func (c *anyboxHandshakeTLSConfig) Client(conn net.Conn) (singtls.Conn, error) {
	return tls.Client(conn, c.config), nil
}

func (c *anyboxHandshakeTLSConfig) Clone() singtls.Config {
	clone := *c
	clone.config = c.config.Clone()
	return &clone
}

type anyboxFixedDialer struct {
	conn net.Conn
}

func (d *anyboxFixedDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return d.conn, nil
}

func (d *anyboxFixedDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("packet dial is not used by this fixture")
}

type anyboxCloseSignalConn struct {
	net.Conn
	once   sync.Once
	closed chan struct{}
}

func (c *anyboxCloseSignalConn) Close() error {
	c.once.Do(func() { close(c.closed) })
	return c.Conn.Close()
}

func TestAnyBoxTLSHandshakeFailureClosesDialedSocket(t *testing.T) {
	clientPipe, serverPipe := net.Pipe()
	defer serverPipe.Close()
	closeSignal := &anyboxCloseSignalConn{Conn: clientPipe, closed: make(chan struct{})}
	go func() {
		_ = serverPipe.Close()
	}()
	config := &anyboxHandshakeTLSConfig{
		config: &tls.Config{
			InsecureSkipVerify: true, // fixture deliberately rejects before certificate validation
			NextProtos:         []string{"h2"},
		},
	}
	client := createHTTPClient(
		M.Socksaddr{},
		&anyboxFixedDialer{conn: closeSignal},
		&V2RayXHTTPBaseOptions{},
		config,
	)
	_, _, _, err := client.OpenStream(context.Background(), "https://example.invalid/xhttp", nil, false)
	if err == nil {
		t.Fatal("TLS handshake failure unexpectedly returned success")
	}
	select {
	case <-closeSignal.closed:
	case <-time.After(3 * time.Second):
		t.Fatal("TLS handshake failure did not close the dialed socket")
	}
	if err := client.(*DefaultDialerClient).Close(); err != nil {
		t.Fatal(err)
	}
}

type anyboxContextValueKey struct{}

type anyboxValueRoundTripper struct {
	key    anyboxContextValueKey
	values chan any
}

func (r *anyboxValueRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	r.values <- req.Context().Value(r.key)
	return &http.Response{
		StatusCode: http.StatusOK,
		Status:     "200 OK",
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader("context-value")),
		Request:    req,
	}, nil
}

func TestAnyBoxOpenStreamPreservesContextValue(t *testing.T) {
	key := anyboxContextValueKey{}
	transport := &anyboxValueRoundTripper{key: key, values: make(chan any, 1)}
	lifecycleCtx, cancelLifecycle := context.WithCancel(context.Background())
	client := &DefaultDialerClient{
		options: &V2RayXHTTPBaseOptions{},
		client:  &http.Client{Transport: transport},
		ctx:     lifecycleCtx,
		cancel:  cancelLifecycle,
	}
	requestCtx := context.WithValue(context.Background(), key, "request-value")
	reader, _, _, err := client.OpenStream(requestCtx, "http://context-value.invalid/xhttp", nil, false)
	if err != nil {
		t.Fatal(err)
	}
	select {
	case value := <-transport.values:
		if value != "request-value" {
			t.Fatalf("RoundTripper context value = %v, want request-value", value)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("RoundTripper did not receive request")
	}
	if err := reader.Close(); err != nil {
		t.Fatal(err)
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestAnyBoxDefaultDialerClientCloseHTTP2(t *testing.T) {
	started := make(chan struct{})
	canceled := make(chan struct{})
	connClosed := make(chan struct{})
	var startOnce, cancelOnce, connCloseOnce sync.Once
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		startOnce.Do(func() { close(started) })
		<-r.Context().Done()
		cancelOnce.Do(func() { close(canceled) })
	}))
	server.Config.ConnState = func(_ net.Conn, state http.ConnState) {
		if state == http.StateClosed {
			connCloseOnce.Do(func() { close(connClosed) })
		}
	}
	server.EnableHTTP2 = true
	if err := http2.ConfigureServer(server.Config, &http2.Server{}); err != nil {
		t.Fatal(err)
	}
	server.StartTLS()
	defer server.Close()

	baseTransport := server.Client().Transport.(*http.Transport)
	connectionTracker := newXHTTPConnTracker()
	h2Transport := &http2.Transport{
		TLSClientConfig: baseTransport.TLSClientConfig.Clone(),
		DialTLSContext: func(ctx context.Context, network string, addr string, cfg *tls.Config) (net.Conn, error) {
			conn, err := (&net.Dialer{}).DialContext(ctx, network, addr)
			if err != nil {
				return nil, err
			}
			trackedConn := connectionTracker.Track(conn)
			if trackedConn == nil {
				return nil, errXHTTPConnTrackerClosed
			}
			tlsConfig := baseTransport.TLSClientConfig.Clone()
			if cfg != nil {
				tlsConfig = cfg.Clone()
			}
			tlsConn := tls.Client(trackedConn, tlsConfig)
			if err := tlsConn.HandshakeContext(ctx); err != nil {
				_ = trackedConn.Close()
				return nil, err
			}
			return tlsConn, nil
		},
	}
	lifecycleCtx, cancel := context.WithCancel(context.Background())
	client := &DefaultDialerClient{
		options:     &V2RayXHTTPBaseOptions{},
		client:      &http.Client{Transport: h2Transport},
		httpVersion: "2",
		ctx:         lifecycleCtx,
		cancel:      cancel,
		closeTransport: func() error {
			h2Transport.CloseIdleConnections()
			return connectionTracker.Close()
		},
	}

	result := make(chan error, 1)
	go func() {
		_, _, _, err := client.OpenStream(context.Background(), server.URL, nil, false)
		result <- err
	}()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("local H2 server did not receive request")
	}
	connectionTracker.mu.Lock()
	trackedCount := len(connectionTracker.conns)
	connectionTracker.mu.Unlock()
	if trackedCount != 1 {
		t.Fatalf("tracked H2 TCP connections = %d, want 1", trackedCount)
	}

	var closeWG sync.WaitGroup
	for i := 0; i < 8; i++ {
		closeWG.Add(1)
		go func() {
			defer closeWG.Done()
			_ = client.Close()
		}()
	}
	closeWG.Wait()
	select {
	case <-canceled:
	case <-time.After(3 * time.Second):
		t.Fatal("H2 request survived client close")
	}
	select {
	case <-connClosed:
	case <-time.After(3 * time.Second):
		t.Fatal("H2 TCP connection survived client close")
	}
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("closed H2 request returned success")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("OpenStream did not unblock after client close")
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestAnyBoxOpenStreamContextCancelPendingH2(t *testing.T) {
	started := make(chan struct{})
	canceled := make(chan struct{})
	var startOnce, cancelOnce sync.Once
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		startOnce.Do(func() { close(started) })
		<-r.Context().Done()
		cancelOnce.Do(func() { close(canceled) })
	}))
	server.EnableHTTP2 = true
	if err := http2.ConfigureServer(server.Config, &http2.Server{}); err != nil {
		t.Fatal(err)
	}
	server.StartTLS()
	defer server.Close()
	baseTransport := server.Client().Transport.(*http.Transport)
	h2Transport := &http2.Transport{TLSClientConfig: baseTransport.TLSClientConfig.Clone()}
	lifecycleCtx, cancelLifecycle := context.WithCancel(context.Background())
	client := &DefaultDialerClient{
		options:     &V2RayXHTTPBaseOptions{},
		client:      &http.Client{Transport: h2Transport},
		httpVersion: "2",
		ctx:         lifecycleCtx,
		cancel:      cancelLifecycle,
		closeTransport: func() error {
			h2Transport.CloseIdleConnections()
			return nil
		},
	}

	dialCtx, cancelDial := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() {
		_, _, _, err := client.OpenStream(dialCtx, server.URL, nil, false)
		result <- err
	}()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("local H2 server did not receive request")
	}
	cancelDial()
	select {
	case <-canceled:
	case <-time.After(3 * time.Second):
		t.Fatal("cancelled pending H2 request survived dial context cancellation")
	}
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("cancelled pending H2 request returned success")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("OpenStream did not unblock after dial context cancellation")
	}
	_ = client.Close()
}

func TestAnyBoxOpenStreamDetachedAfterH2Headers(t *testing.T) {
	started := make(chan struct{})
	canceled := make(chan struct{})
	var startOnce, cancelOnce sync.Once
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		startOnce.Do(func() { close(started) })
		<-r.Context().Done()
		cancelOnce.Do(func() { close(canceled) })
	}))
	server.EnableHTTP2 = true
	if err := http2.ConfigureServer(server.Config, &http2.Server{}); err != nil {
		t.Fatal(err)
	}
	server.StartTLS()
	defer server.Close()
	baseTransport := server.Client().Transport.(*http.Transport)
	h2Transport := &http2.Transport{TLSClientConfig: baseTransport.TLSClientConfig.Clone()}
	lifecycleCtx, cancelLifecycle := context.WithCancel(context.Background())
	client := &DefaultDialerClient{
		options:     &V2RayXHTTPBaseOptions{},
		client:      &http.Client{Transport: h2Transport},
		httpVersion: "2",
		ctx:         lifecycleCtx,
		cancel:      cancelLifecycle,
		closeTransport: func() error {
			h2Transport.CloseIdleConnections()
			return nil
		},
	}

	dialCtx, cancelDial := context.WithCancel(context.Background())
	result := make(chan struct {
		reader io.ReadCloser
		err    error
	}, 1)
	go func() {
		reader, _, _, err := client.OpenStream(dialCtx, server.URL, nil, false)
		result <- struct {
			reader io.ReadCloser
			err    error
		}{reader: reader, err: err}
	}()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("local H2 server did not receive request")
	}
	var stream struct {
		reader io.ReadCloser
		err    error
	}
	select {
	case stream = <-result:
		if stream.err != nil {
			t.Fatal(stream.err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("OpenStream did not return after H2 headers")
	}
	cancelDial()
	select {
	case <-canceled:
		t.Fatal("successful H2 stream remained tied to the short-lived dial context")
	case <-time.After(150 * time.Millisecond):
	}
	if err := stream.reader.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-canceled:
	case <-time.After(3 * time.Second):
		t.Fatal("closing returned H2 stream did not cancel request")
	}
	_ = client.Close()
}

func TestAnyBoxDefaultDialerClientCloseHTTP3(t *testing.T) {
	certificate := anyboxLifecycleCertificate(t)
	started := make(chan struct{})
	canceled := make(chan struct{})
	var startOnce, cancelOnce sync.Once
	server := &http3.Server{
		TLSConfig: http3.ConfigureTLSConfig(&tls.Config{Certificates: []tls.Certificate{certificate}}),
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			startOnce.Do(func() { close(started) })
			<-r.Context().Done()
			cancelOnce.Do(func() { close(canceled) })
		}),
	}
	udpConn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	serveErr := make(chan error, 1)
	go func() { serveErr <- server.Serve(udpConn) }()
	defer func() {
		_ = server.Close()
		_ = udpConn.Close()
		select {
		case <-serveErr:
		case <-time.After(3 * time.Second):
			t.Error("local H3 server did not stop")
		}
	}()

	h3Transport := &http3.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true, ServerName: "localhost"}, // local self-signed fixture
		Dial: func(ctx context.Context, addr string, tlsConfig *tls.Config, config *quic.Config) (*quic.Conn, error) {
			return quic.DialAddr(ctx, addr, tlsConfig, config)
		},
	}
	lifecycleCtx, cancel := context.WithCancel(context.Background())
	client := &DefaultDialerClient{
		options:     &V2RayXHTTPBaseOptions{},
		client:      &http.Client{Transport: h3Transport},
		httpVersion: "3",
		ctx:         lifecycleCtx,
		cancel:      cancel,
		closeTransport: func() error {
			return h3Transport.Close()
		},
	}

	result := make(chan error, 1)
	go func() {
		_, _, _, err := client.OpenStream(context.Background(), "https://"+udpConn.LocalAddr().String()+"/xhttp", nil, false)
		result <- err
	}()
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("local H3 server did not receive request")
	}
	var closeWG sync.WaitGroup
	for i := 0; i < 8; i++ {
		closeWG.Add(1)
		go func() {
			defer closeWG.Done()
			_ = client.Close()
		}()
	}
	closeWG.Wait()
	select {
	case <-canceled:
	case <-time.After(5 * time.Second):
		t.Fatal("H3 request survived client close")
	}
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("closed H3 request returned success")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("OpenStream did not unblock after H3 client close")
	}
}

func anyboxLifecycleCertificate(t *testing.T) tls.Certificate {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "localhost"},
		NotBefore:    time.Now().Add(-time.Minute),
		NotAfter:     time.Now().Add(time.Hour),
		DNSNames:     []string{"localhost"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{
		Certificate: [][]byte{der},
		PrivateKey:  key,
		Leaf:        template,
	}
}
