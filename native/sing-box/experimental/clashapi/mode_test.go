package clashapi

import (
	"context"
	stdjson "encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/service"
)

type modeTestDNSRouter struct {
	adapter.DNSRouter
	onClear    func()
	clearCalls atomic.Int32
}

type modeTestCacheFile struct {
	adapter.CacheFile
	storeMode func(string) error
}

func (c *modeTestCacheFile) StoreMode(mode string) error {
	return c.storeMode(mode)
}

func (r *modeTestDNSRouter) ClearCache() {
	r.clearCalls.Add(1)
	if r.onClear != nil {
		r.onClear()
	}
}

func newModeTestServer() (*Server, *modeTestDNSRouter) {
	dnsRouter := &modeTestDNSRouter{}
	server := &Server{
		ctx:       context.Background(),
		dnsRouter: dnsRouter,
		logger:    log.NewNOPFactory().NewLogger("mode-test"),
		mode:      "Rule",
		modeList:  []string{"Rule", "Global", "Direct"},
	}
	return server, dnsRouter
}

func TestModeReadWriteThroughPublicPathsIsRaceFree(t *testing.T) {
	server, dnsRouter := newModeTestServer()
	configHandler := getConfigs(server, log.NewNOPFactory())
	modes := []string{"Rule", "Global", "Direct", "global"}

	var waitGroup sync.WaitGroup
	for writer := 0; writer < 4; writer++ {
		waitGroup.Add(1)
		go func(offset int) {
			defer waitGroup.Done()
			for i := 0; i < 200; i++ {
				server.SetMode(modes[(offset+i)%len(modes)])
			}
		}(writer)
	}
	for reader := 0; reader < 4; reader++ {
		waitGroup.Add(1)
		go func() {
			defer waitGroup.Done()
			for i := 0; i < 200; i++ {
				mode := server.Mode()
				if mode != "Rule" && mode != "Global" && mode != "Direct" {
					t.Errorf("Mode returned invalid value %q", mode)
				}

				request := httptest.NewRequest(http.MethodGet, "/configs", nil)
				response := httptest.NewRecorder()
				configHandler(response, request)
				if response.Code != http.StatusOK {
					t.Errorf("config response status = %d, want %d", response.Code, http.StatusOK)
					continue
				}
				var config configSchema
				if err := stdjson.Unmarshal(response.Body.Bytes(), &config); err != nil {
					t.Errorf("decode config response: %v", err)
				}
			}
		}()
	}
	waitGroup.Wait()

	if got := server.Mode(); got != "Rule" && got != "Global" && got != "Direct" {
		t.Fatalf("final Mode returned invalid value %q", got)
	}
	if dnsRouter.clearCalls.Load() == 0 {
		t.Fatal("SetMode did not clear DNS cache for any mode change")
	}
}

func TestSetModeAllowsReentrantModeReadDuringCacheClear(t *testing.T) {
	server, dnsRouter := newModeTestServer()
	var callbackCalls atomic.Int32
	dnsRouter.onClear = func() {
		if callbackCalls.Add(1) == 1 {
			if got := server.Mode(); got != "Global" {
				t.Errorf("reentrant Mode returned %q, want Global", got)
			}
		}
	}

	done := make(chan struct{})
	go func() {
		server.SetMode("Global")
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("SetMode did not complete while cache clear read Mode reentrantly")
	}
	if calls := callbackCalls.Load(); calls != 1 {
		t.Fatalf("cache clear callback count = %d, want 1", calls)
	}
}

func TestSetModePersistsLatestModeInOrder(t *testing.T) {
	server, dnsRouter := newModeTestServer()
	firstStoreStarted := make(chan struct{})
	releaseFirstStore := make(chan struct{})
	directStateSet := make(chan struct{})
	directStoreStarted := make(chan struct{})
	writes := make(chan string, 2)
	var storeCalls atomic.Int32
	var signalDirectState sync.Once
	var signalDirectStore sync.Once
	dnsRouter.onClear = func() {
		if server.Mode() == "Direct" {
			signalDirectState.Do(func() { close(directStateSet) })
		}
	}
	cache := &modeTestCacheFile{
		storeMode: func(mode string) error {
			if storeCalls.Add(1) == 1 {
				close(firstStoreStarted)
				<-releaseFirstStore
			}
			if mode == "Direct" {
				signalDirectStore.Do(func() { close(directStoreStarted) })
			}
			writes <- mode
			return nil
		},
	}
	server.ctx = service.ContextWith[adapter.CacheFile](server.ctx, cache)

	firstDone := make(chan struct{})
	go func() {
		server.SetMode("Global")
		close(firstDone)
	}()
	select {
	case <-firstStoreStarted:
	case <-time.After(time.Second):
		t.Fatal("first mode persistence did not start")
	}

	secondDone := make(chan struct{})
	go func() {
		server.SetMode("Direct")
		close(secondDone)
	}()
	select {
	case <-directStateSet:
	case <-time.After(time.Second):
		t.Fatal("second mode update did not reach the cache-clear stage")
	}
	directStoreBeforeFirstDone := false
	select {
	case <-directStoreStarted:
		directStoreBeforeFirstDone = true
	case <-time.After(100 * time.Millisecond):
	}

	close(releaseFirstStore)
	select {
	case <-firstDone:
	case <-time.After(time.Second):
		t.Fatal("first mode update did not complete")
	}
	select {
	case <-secondDone:
	case <-time.After(time.Second):
		t.Fatal("second mode update did not complete")
	}
	if directStoreBeforeFirstDone {
		t.Fatal("new mode reached StoreMode before the older write completed")
	}

	firstWrite := <-writes
	secondWrite := <-writes
	if firstWrite != "Global" || secondWrite != "Direct" {
		t.Fatalf("mode writes = [%s %s], want [Global Direct]", firstWrite, secondWrite)
	}
}
