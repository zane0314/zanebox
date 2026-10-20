package xhttp

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type compatDialer struct{}

func (compatDialer) DialContext(ctx context.Context, network string, dest M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, dest.String())
}

func (compatDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, fmt.Errorf("packet dial is not used by this fixture")
}

// The real server may wait for the VLESS request in the first upload before
// committing downstream headers. Dial must return early enough to send it.
func TestXHTTPUploadBeforeDownloadHeaders(t *testing.T) {
	for _, mode := range []string{"packet-up", "stream-up", "stream-one"} {
		t.Run(mode, func(t *testing.T) {
			const payload = "first-client-request"
			uploaded := make(chan string, 1)
			server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.ProtoMajor != 2 {
					t.Errorf("HTTP version = %s, want HTTP/2", r.Proto)
				}
				if r.Method == http.MethodPost {
					data := make([]byte, len(payload))
					if _, err := io.ReadFull(r.Body, data); err != nil {
						return
					}
					if mode == "stream-one" {
						_, _ = io.WriteString(w, string(data))
						w.(http.Flusher).Flush()
						<-r.Context().Done()
						return
					}
					uploaded <- string(data)
					w.WriteHeader(http.StatusOK)
					w.(http.Flusher).Flush()
					if mode == "stream-up" {
						_, _ = io.Copy(io.Discard, r.Body)
					}
					return
				}
				select {
				case data := <-uploaded:
					_, _ = io.WriteString(w, data)
					w.(http.Flusher).Flush()
					<-r.Context().Done()
				case <-r.Context().Done():
				}
			}))
			server.EnableHTTP2 = true
			server.StartTLS()
			defer server.Close()
			transport, err := NewClient(context.Background(), compatDialer{}, M.ParseSocksaddr(server.Listener.Addr().String()), V2RayXHTTPOptions{
				V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{Mode: mode, Path: "/xhttp", Host: "localhost"},
			}, &anyboxHandshakeTLSConfig{config: &tls.Config{
				InsecureSkipVerify: true, // local self-signed fixture
				NextProtos:         []string{"h2"},
			}})
			if err != nil {
				t.Fatal(err)
			}
			defer transport.Close()
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			conn, err := transport.DialContext(ctx)
			if err != nil {
				t.Fatalf("dial must allow the first upload before downstream headers: %v", err)
			}
			defer conn.Close()
			result := make(chan error, 1)
			go func() {
				if _, err := io.WriteString(conn, payload); err != nil {
					result <- err
					return
				}
				data := make([]byte, len(payload))
				_, err := io.ReadFull(conn, data)
				if err == nil && string(data) != payload {
					err = fmt.Errorf("response = %q, want %q", data, payload)
				}
				result <- err
			}()
			select {
			case err := <-result:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("upload/download remained blocked")
			}
		})
	}
}

func TestXHTTPUploadContentType(t *testing.T) {
	for _, tc := range []struct {
		name   string
		packet bool
		noGRPC bool
		custom string
		want   string
	}{
		{name: "packet-default", packet: true, want: "application/octet-stream"},
		{name: "packet-no-grpc", packet: true, noGRPC: true, want: "application/octet-stream"},
		{name: "packet-custom", packet: true, custom: "application/custom", want: "application/custom"},
		{name: "stream-default", want: "application/grpc"},
		{name: "stream-no-grpc", noGRPC: true, want: "application/octet-stream"},
		{name: "stream-custom", custom: "application/custom", want: "application/custom"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			headers := make(chan string, 1)
			server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				headers <- r.Header.Get("Content-Type")
				_, _ = io.Copy(io.Discard, r.Body)
				w.WriteHeader(http.StatusOK)
			}))
			server.EnableHTTP2 = true
			server.StartTLS()
			defer server.Close()
			client := &DefaultDialerClient{
				options: &V2RayXHTTPBaseOptions{NoGRPCHeader: tc.noGRPC, Headers: map[string]string{"Content-Type": tc.custom}},
				client:  server.Client(), httpVersion: "2",
			}
			defer client.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			if tc.packet {
				if err := client.PostPacket(ctx, server.URL, strings.NewReader("packet"), 6); err != nil {
					t.Fatal(err)
				}
			} else {
				reader, _, _, err := client.OpenStream(ctx, server.URL, strings.NewReader("stream"), false)
				if err != nil {
					t.Fatal(err)
				}
				defer reader.Close()
			}
			select {
			case got := <-headers:
				if got != tc.want {
					t.Fatalf("Content-Type = %q, want %q", got, tc.want)
				}
			case <-ctx.Done():
				t.Fatal("server did not receive upload")
			}
		})
	}
}

func TestXHTTPPendingDownloadCloseAndLateStatus(t *testing.T) {
	for _, action := range []string{"reader-close", "client-close", "bad-status"} {
		t.Run(action, func(t *testing.T) {
			started, canceled, respond := make(chan struct{}), make(chan struct{}), make(chan struct{})
			server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				close(started)
				select {
				case <-respond:
					w.WriteHeader(http.StatusForbidden)
				case <-r.Context().Done():
				}
				close(canceled)
			}))
			server.EnableHTTP2 = true
			server.StartTLS()
			defer server.Close()
			client := &DefaultDialerClient{options: &V2RayXHTTPBaseOptions{}, client: server.Client(), httpVersion: "2"}
			defer client.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			reader, _, _, err := client.OpenStream(ctx, server.URL, nil, false)
			if err != nil {
				t.Fatal(err)
			}
			defer reader.Close()
			select {
			case <-started:
			case <-ctx.Done():
				t.Fatal("GET did not start")
			}
			// The returned stream owns its request even while headers are pending.
			cancel()
			select {
			case <-canceled:
				t.Fatal("dial context cancellation terminated a returned stream")
			case <-time.After(30 * time.Millisecond):
			}
			readResult := make(chan error, 1)
			go func() { _, err := reader.Read(make([]byte, 1)); readResult <- err }()
			switch action {
			case "reader-close":
				_ = reader.Close()
			case "client-close":
				_ = client.Close()
			case "bad-status":
				close(respond)
			}
			select {
			case err := <-readResult:
				if err == nil || (action == "bad-status" && !strings.Contains(err.Error(), "403")) {
					t.Fatalf("pending read error = %v (action %s)", err, action)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("pending Read did not unblock")
			}
			select {
			case <-canceled:
			case <-time.After(3 * time.Second):
				t.Fatal("request survived close")
			}
			_ = client.Close()
			client.activeMu.Lock()
			active := len(client.active)
			client.activeMu.Unlock()
			if active != 0 {
				t.Fatalf("active readers after close = %d", active)
			}
		})
	}
}
