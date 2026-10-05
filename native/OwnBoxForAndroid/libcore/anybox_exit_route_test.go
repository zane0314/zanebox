package libcore

import (
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// A connected exit query must honor routing rejection, not silently use final.
func TestAnyBoxExitProbeHonorsRoute(t *testing.T) {
	var hits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		_, _ = w.Write([]byte("127.0.0.1"))
	}))
	defer server.Close()
	for _, reject := range []bool{false, true} {
		rules := "[]"
		if reject {
			rules = `[{"ip_cidr":["127.0.0.0/8"],"action":"reject"}]`
		}
		box, err := NewTestSingBoxInstance(`{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct","rules":`+rules+`}}`, nil)
		if err != nil {
			t.Fatal(err)
		}
		if err = box.Start(); err != nil {
			_ = box.Close()
			t.Fatal(err)
		}
		previous := mainInstance.Swap(box)
		client := NewHttpClient()
		client.TryBoxOutbound()
		request := client.NewRequest()
		if err = request.SetURL(server.URL); err != nil {
			t.Fatal(err)
		}
		before := hits.Load()
		response, err := request.Execute()
		if err == nil {
			_, err = response.GetContent()
		}
		client.Close()
		mainInstance.Store(previous)
		_ = box.Close()
		if reject && (err == nil || hits.Load() != before) {
			t.Fatal("exit probe bypassed a route reject via default outbound")
		}
		if !reject && (err != nil || hits.Load() != before+1) {
			t.Fatal("permitted exit request did not reach server")
		}
	}
}

func TestAnyBoxExitProbeRouteCloseCancels(t *testing.T) {
	entered, closed := make(chan struct{}), make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-r.Context().Done()
		close(closed)
	}))
	defer server.Close()
	box, err := NewTestSingBoxInstance(`{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"direct"}]}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer box.Close()
	if err = box.Start(); err != nil {
		t.Fatal(err)
	}
	previous := mainInstance.Swap(box)
	defer mainInstance.Store(previous)
	client := NewHttpClient()
	defer client.Close()
	client.TryBoxOutbound()
	request := client.NewRequest()
	if err = request.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { _, err := request.Execute(); done <- err }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("route did not reach server")
	}
	client.Close()
	select {
	case err = <-done:
		if err == nil {
			t.Fatal("closed query succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("query did not cancel")
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("routed socket leaked")
	}
}
