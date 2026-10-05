package xhttp

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"sync"
	"sync/atomic"

	"libcore/protocol/vless/internal/xray"
	"libcore/protocol/vless/internal/xray/signal/done"
)

// interface to abstract between use of browser dialer, vs net/http
type DialerClient interface {
	IsClosed() bool

	// ctx, url, body, uploadOnly
	OpenStream(context.Context, string, io.Reader, bool) (io.ReadCloser, net.Addr, net.Addr, error)

	// ctx, url, body, contentLength
	PostPacket(context.Context, string, io.Reader, int64) error
}

// implements xhttp.DialerClient in terms of direct network connections
type DefaultDialerClient struct {
	options        *V2RayXHTTPBaseOptions
	client         *http.Client
	closed         atomic.Bool
	httpVersion    string
	ctx            context.Context
	cancel         context.CancelFunc
	closeOnce      sync.Once
	closeErr       error
	closeTransport func() error
	activeMu       sync.Mutex
	active         map[*WaitReadCloser]struct{}
	// pool of net.Conn, created using dialUploadConn
	uploadRawPool  *sync.Pool
	dialUploadConn func(ctxInner context.Context) (net.Conn, error)
}

var errDialerClientClosed = errors.New("xhttp dialer client closed")
var errXHTTPConnTrackerClosed = errors.New("xhttp connection tracker closed")

type xhttpConnTracker struct {
	mu     sync.Mutex
	closed bool
	conns  map[*xhttpTrackedConn]struct{}
}

type xhttpTrackedConn struct {
	net.Conn
	tracker *xhttpConnTracker
	once    sync.Once
	err     error
}

func newXHTTPConnTracker() *xhttpConnTracker {
	return &xhttpConnTracker{conns: make(map[*xhttpTrackedConn]struct{})}
}

func (t *xhttpConnTracker) Track(conn net.Conn) net.Conn {
	if conn == nil {
		return nil
	}
	tracked := &xhttpTrackedConn{Conn: conn, tracker: t}
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		_ = conn.Close()
		return nil
	}
	t.conns[tracked] = struct{}{}
	t.mu.Unlock()
	return tracked
}

func (c *xhttpTrackedConn) Close() error {
	c.once.Do(func() {
		c.err = c.Conn.Close()
		c.tracker.mu.Lock()
		delete(c.tracker.conns, c)
		c.tracker.mu.Unlock()
	})
	return c.err
}

func (t *xhttpConnTracker) Close() error {
	t.mu.Lock()
	t.closed = true
	conns := make([]*xhttpTrackedConn, 0, len(t.conns))
	for conn := range t.conns {
		conns = append(conns, conn)
	}
	t.mu.Unlock()
	var firstErr error
	for _, conn := range conns {
		if err := conn.Close(); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

func (c *DefaultDialerClient) IsClosed() bool {
	return c.closed.Load()
}

func (c *DefaultDialerClient) trackWaitReader(reader *WaitReadCloser) bool {
	previousOnClose := reader.onClose
	reader.onClose = func() {
		if previousOnClose != nil {
			previousOnClose()
		}
		c.activeMu.Lock()
		delete(c.active, reader)
		c.activeMu.Unlock()
	}
	c.activeMu.Lock()
	defer c.activeMu.Unlock()
	if c.closed.Load() {
		return false
	}
	if c.active == nil {
		c.active = make(map[*WaitReadCloser]struct{})
	}
	c.active[reader] = struct{}{}
	return true
}

func (c *DefaultDialerClient) requestContext(ctx context.Context) (context.Context, func()) {
	requestCtx, cancel := context.WithCancel(ctx)
	if c.ctx == nil {
		return requestCtx, cancel
	}
	stop := context.AfterFunc(c.ctx, cancel)
	return requestCtx, func() {
		stop()
		cancel()
	}
}

func (c *DefaultDialerClient) Close() error {
	c.closed.Store(true)
	c.closeOnce.Do(func() {
		if c.cancel != nil {
			c.cancel()
		}
		c.activeMu.Lock()
		active := make([]*WaitReadCloser, 0, len(c.active))
		for reader := range c.active {
			active = append(active, reader)
		}
		c.active = nil
		c.activeMu.Unlock()
		for _, reader := range active {
			reader.SetErr(errDialerClientClosed)
			_ = reader.Close()
		}
		if c.closeTransport != nil {
			c.closeErr = c.closeTransport()
		}
	})
	return c.closeErr
}

func (c *DefaultDialerClient) OpenStream(ctx context.Context, url string, body io.Reader, uploadOnly bool) (wrc io.ReadCloser, remoteAddr, localAddr net.Addr, err error) {
	if c.IsClosed() {
		return nil, nil, nil, errDialerClientClosed
	}
	// this is done when the TCP/UDP connection to the server was established,
	// and we can unblock the Dial function and print correct net addresses in
	// logs
	gotConn := done.New()
	var addrMu sync.Mutex
	var gotRemoteAddr, gotLocalAddr net.Addr
	requestCtx, cancelRequest := c.requestContext(context.WithoutCancel(ctx))
	ctxTrace := httptrace.WithClientTrace(requestCtx, &httptrace.ClientTrace{
		GotConn: func(connInfo httptrace.GotConnInfo) {
			addrMu.Lock()
			gotRemoteAddr = connInfo.Conn.RemoteAddr()
			gotLocalAddr = connInfo.Conn.LocalAddr()
			addrMu.Unlock()
			gotConn.Close()
		},
	})
	method := "GET" // stream-down
	if body != nil {
		method = "POST" // stream-up/one
	}
	req, _ := http.NewRequestWithContext(ctxTrace, method, url, body)
	req.Header = c.options.GetRequestHeader(url)
	if req.Header.Get("X-Accel-Buffering") == "" {
		req.Header.Set("X-Accel-Buffering", "no")
	}
	if req.Header.Get("Cache-Control") == "" {
		req.Header.Set("Cache-Control", "no-store, no-cache, must-revalidate")
	}
	if method == "GET" {
		if !c.options.NoSSEHeader && req.Header.Get("Accept") == "" {
			req.Header.Set("Accept", "text/event-stream")
		}
	} else if method == "POST" {
		if req.Header.Get("Content-Type") == "" {
			if !c.options.NoGRPCHeader {
				req.Header.Set("Content-Type", "application/grpc")
			} else {
				req.Header.Set("Content-Type", "application/octet-stream")
			}
		}
	}
	waitReader := &WaitReadCloser{Wait: make(chan struct{})}
	wrc = waitReader
	waitReader.onClose = cancelRequest
	if !c.trackWaitReader(waitReader) {
		cancelRequest()
		return nil, nil, nil, errDialerClientClosed
	}

	var doErr error
	var doErrMu sync.Mutex
	setDoErr := func(e error) {
		doErrMu.Lock()
		if doErr == nil {
			doErr = e
		}
		doErrMu.Unlock()
	}

	go func() {
		resp, dErr := c.client.Do(req)
		if dErr != nil {
			if !uploadOnly { // stream-down is enough
				c.closed.Store(true)
			}
			setDoErr(dErr)
			waitReader.SetErr(dErr)
			gotConn.Close()
			if closer, ok := body.(io.Closer); ok {
				closer.Close()
			}
			waitReader.Close()
			return
		}
		if resp.StatusCode != 200 || uploadOnly { // stream-up
			if resp.StatusCode != 200 {
				c.closed.Store(true)
				statusErr := fmt.Errorf("bad status code: %s", resp.Status)
				setDoErr(statusErr)
				waitReader.SetErr(statusErr)
				if closer, ok := body.(io.Closer); ok {
					closer.Close()
				}
			}
			io.Copy(io.Discard, io.LimitReader(resp.Body, 32*1024))
			resp.Body.Close() // if it is called immediately, the upload will be interrupted also
			waitReader.Close()
			return
		}
		waitReader.Set(resp.Body)
	}()

	if body == nil {
		select {
		case <-waitReader.Wait:
		case <-ctx.Done():
			c.closed.Store(true)
			waitReader.SetErr(ctx.Err())
			waitReader.Close()
			return nil, nil, nil, ctx.Err()
		}
	} else {
		select {
		case <-gotConn.Wait():
		case <-waitReader.Wait:
		case <-ctx.Done():
			c.closed.Store(true)
			waitReader.SetErr(ctx.Err())
			waitReader.Close()
			if closer, ok := body.(io.Closer); ok {
				closer.Close()
			}
			return nil, nil, nil, ctx.Err()
		}
	}

	doErrMu.Lock()
	err = doErr
	doErrMu.Unlock()
	if err != nil {
		return nil, nil, nil, err
	}
	if waitErr := waitReader.Err(); waitErr != nil {
		return nil, nil, nil, waitErr
	}
	addrMu.Lock()
	remoteAddr = gotRemoteAddr
	localAddr = gotLocalAddr
	addrMu.Unlock()
	return
}

func (c *DefaultDialerClient) PostPacket(ctx context.Context, url string, body io.Reader, contentLength int64) error {
	if c.IsClosed() {
		return errDialerClientClosed
	}
	requestCtx, releaseRequest := c.requestContext(ctx)
	defer releaseRequest()
	req, err := http.NewRequestWithContext(requestCtx, "POST", url, body)
	if err != nil {
		return err
	}
	req.ContentLength = contentLength
	req.Header = c.options.GetRequestHeader(url)
	if req.Header.Get("X-Accel-Buffering") == "" {
		req.Header.Set("X-Accel-Buffering", "no")
	}
	if req.Header.Get("Cache-Control") == "" {
		req.Header.Set("Cache-Control", "no-store, no-cache, must-revalidate")
	}
	if req.Header.Get("Content-Type") == "" {
		if !c.options.NoGRPCHeader {
			req.Header.Set("Content-Type", "application/grpc")
		} else {
			req.Header.Set("Content-Type", "application/octet-stream")
		}
	}
	if c.httpVersion != "1.1" {
		resp, err := c.client.Do(req)
		if err != nil {
			c.closed.Store(true)
			return err
		}
		_, copyErr := io.Copy(io.Discard, io.LimitReader(resp.Body, 32*1024))
		closeErr := resp.Body.Close()
		if resp.StatusCode != 200 {
			c.closed.Store(true)
			if copyErr != nil {
				return copyErr
			}
			if closeErr != nil {
				return closeErr
			}
			return fmt.Errorf("bad status code: %s", resp.Status)
		}
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	} else {
		// stringify the entire HTTP/1.1 request so it can be
		// safely retried. if instead req.Write is called multiple
		// times, the body is already drained after the first
		// request
		requestBuff := new(bytes.Buffer)
		common.Must(req.Write(requestBuff))
		var uploadConn any
		var h1UploadConn *H1Conn
		for {
			uploadConn = c.uploadRawPool.Get()
			newConnection := uploadConn == nil
			if newConnection {
				newConn, err := c.dialUploadConn(requestCtx)
				if err != nil {
					return err
				}
				h1UploadConn = NewH1Conn(newConn)
				uploadConn = h1UploadConn
			} else {
				h1UploadConn = uploadConn.(*H1Conn)
			}
			_, err := h1UploadConn.Write(requestBuff.Bytes())
			if err == nil {
				h1UploadConn.UnreadedResponsesCount++
				break
			}
			h1UploadConn.Close()
			if newConnection {
				return err
			}
		}
		for h1UploadConn.UnreadedResponsesCount > 0 {
			resp, err := http.ReadResponse(h1UploadConn.RespBufReader, req)
			if err != nil {
				c.closed.Store(true)
				h1UploadConn.Close()
				return fmt.Errorf("error while reading response: %s", err.Error())
			}
			_, copyErr := io.Copy(io.Discard, io.LimitReader(resp.Body, 32*1024))
			closeErr := resp.Body.Close()
			h1UploadConn.UnreadedResponsesCount--
			if resp.StatusCode != 200 {
				c.closed.Store(true)
				h1UploadConn.Close()
				if copyErr != nil {
					return copyErr
				}
				if closeErr != nil {
					return closeErr
				}
				return fmt.Errorf("got non-200 error response code: %d", resp.StatusCode)
			}
			if copyErr != nil {
				h1UploadConn.Close()
				return copyErr
			}
			if closeErr != nil {
				h1UploadConn.Close()
				return closeErr
			}
		}
		c.uploadRawPool.Put(uploadConn)
	}

	return nil
}

type WaitReadCloser struct {
	Wait chan struct{}
	io.ReadCloser
	err     error
	mu      sync.Mutex
	once    sync.Once
	closed  bool
	onClose func()
}

func (w *WaitReadCloser) notify() {
	w.once.Do(func() {
		close(w.Wait)
	})
}

func (w *WaitReadCloser) Set(rc io.ReadCloser) {
	w.mu.Lock()
	if w.closed || w.ReadCloser != nil {
		w.mu.Unlock()
		rc.Close()
		return
	}
	w.ReadCloser = rc
	w.mu.Unlock()
	w.notify()
}

func (w *WaitReadCloser) SetErr(err error) {
	w.mu.Lock()
	if w.err == nil {
		w.err = err
	}
	w.mu.Unlock()
	w.notify()
}

func (w *WaitReadCloser) Err() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.err
}

func (w *WaitReadCloser) Read(b []byte) (int, error) {
	w.mu.Lock()
	rc := w.ReadCloser
	err := w.err
	w.mu.Unlock()

	if rc == nil {
		if err != nil {
			return 0, err
		}
		<-w.Wait
		w.mu.Lock()
		rc = w.ReadCloser
		err = w.err
		w.mu.Unlock()
		if rc == nil {
			if err != nil {
				return 0, err
			}
			return 0, io.ErrClosedPipe
		}
	}
	return rc.Read(b)
}

func (w *WaitReadCloser) Close() error {
	w.mu.Lock()
	if w.closed {
		w.mu.Unlock()
		return nil
	}
	w.closed = true
	rc := w.ReadCloser
	w.ReadCloser = nil
	onClose := w.onClose
	w.onClose = nil
	w.mu.Unlock()

	w.notify()
	if onClose != nil {
		onClose()
	}
	if rc != nil {
		return rc.Close()
	}

	return nil
}
