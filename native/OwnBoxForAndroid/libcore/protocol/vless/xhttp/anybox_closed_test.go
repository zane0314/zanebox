package xhttp

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func TestAnyBoxClosedClientConcurrency(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer server.Close()
	client := &DefaultDialerClient{options: &V2RayXHTTPBaseOptions{}, client: server.Client(), httpVersion: "2"}
	first := true
	manager := NewXmuxManager(V2RayXHTTPXmuxOptions{}, func() XmuxConn {
		if first {
			first = false
			return client
		}
		return &DefaultDialerClient{}
	})
	manager.GetXmuxClient(context.Background())
	var wg sync.WaitGroup
	start := make(chan struct{})
	for n := 0; n < 6; n++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			for i := 0; i < 20; i++ {
				client.IsClosed()
				manager.GetXmuxClient(context.Background())
				if client.PostPacket(context.Background(), server.URL, strings.NewReader("x"), 1) == nil {
					t.Error("non-200 response accepted")
				}
			}
		}()
	}
	close(start)
	wg.Wait()
	if !client.IsClosed() || manager.GetXmuxClient(context.Background()).XmuxConn == client {
		t.Fatal("failed client retained by mux manager")
	}
	server.Close()
	if _, _, _, err := client.OpenStream(context.Background(), server.URL, nil, false); err == nil {
		t.Fatal("closed endpoint accepted")
	}
}
