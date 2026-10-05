package libcore

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestAnyBoxHTTPClientCloseCancels(t *testing.T) {
	entered := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { close(entered); <-r.Context().Done() }))
	defer server.Close()
	client := NewHttpClient()
	request := client.NewRequest()
	if err := request.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { _, err := request.Execute(); done <- err }()
	<-entered
	client.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("request survived close")
		}
	case <-time.After(time.Second):
		t.Fatal("request did not cancel")
	}
}

func TestAnyBoxSocksHandshakeCancelCloses(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	entered := make(chan struct{})
	closed := make(chan struct{})
	go func() {
		conn, e := listener.Accept()
		if e != nil {
			return
		}
		defer conn.Close()
		data := make([]byte, 64)
		if _, e = conn.Read(data); e != nil {
			return
		}
		close(entered)
		for {
			if _, e = conn.Read(data); e != nil {
				close(closed)
				return
			}
		}
	}()
	client := NewHttpClient()
	defer client.Close()
	client.TrySocks5(int32(listener.Addr().(*net.TCPAddr).Port), "", "")
	request := client.NewRequest()
	if err = request.SetURL("http://localhost:1"); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { _, err := request.Execute(); done <- err }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("no SOCKS handshake")
	}
	client.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancel succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("handshake did not cancel")
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("SOCKS connection leaked")
	}
}

func TestAnyBoxStunBothFail(t *testing.T) {
	result := StunTest("127.0.0.1:invalid")
	if result.Success {
		t.Fatal("both failed diagnostics reported success")
	}
	if !strings.Contains(result.Text, "Discover success: false") || !strings.Contains(result.Text, "Behavior success: false") {
		t.Fatal(result.Text)
	}
}
