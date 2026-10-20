package libcore

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/protocol/group"
)

func TestSplitURLTestTimeoutKeepsTotalBudget(t *testing.T) {
	for _, timeout := range []int32{1, 500, 1500, 2000, 3000, 3500, 5000} {
		primary, fallback := splitURLTestTimeout(timeout)
		if primary+fallback > timeout {
			t.Fatalf("timeout=%d primary=%d fallback=%d", timeout, primary, fallback)
		}
		if primary <= 0 {
			t.Fatalf("timeout=%d has no primary budget", timeout)
		}
	}
}

func TestUnifiedDelayConfigControlsAutomaticURLTestGroup(t *testing.T) {
	for _, enabled := range []bool{false, true} {
		t.Run(fmt.Sprint(enabled), func(t *testing.T) {
			var requests atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if requests.Add(1) == 1 {
					time.Sleep(120 * time.Millisecond)
				}
				w.WriteHeader(http.StatusNoContent)
			}))
			defer server.Close()
			config := fmt.Sprintf(`{"log":{"disabled":true},"experimental":{"unified_delay":%t},"outbounds":[{"type":"direct","tag":"direct"},{"type":"urltest","tag":"auto","outbounds":["direct"],"url":%q}],"route":{"final":"direct","tcp_concurrent":true}}`, enabled, server.URL)
			instance, err := checkVlessConfig(config)
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			outbound, _ := instance.Outbound().Outbound("auto")
			// Initialize the automatic group's existing pipeline without its
			// background PostStart probe racing the controlled first response.
			auto := outbound.(*group.URLTest)
			if err := auto.Start(); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			result, _ := auto.URLTest(ctx)
			measured := int(result["direct"])
			if measured <= 0 {
				t.Fatal("automatic group did not produce a result")
			}
			if enabled && measured >= 100 {
				t.Fatalf("automatic unified delay retained warm-up: %d", measured)
			}
			if enabled && requests.Load() < 2 {
				t.Fatal("automatic group did not perform warm measurement")
			}
			if !enabled && (measured < 100 || requests.Load() != 1) {
				t.Fatalf("automatic cold mode: %dms, %d requests", measured, requests.Load())
			}
		})
	}
}

func TestURLTestDirectKeepsWarmAndMeasureWithinBudget(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(90 * time.Millisecond):
			w.WriteHeader(http.StatusNoContent)
		case <-r.Context().Done():
		}
	}))
	defer server.Close()
	budget := 140 * time.Millisecond
	started := time.Now()
	_, _ = urlTestDirect(context.Background(), &http.Client{Timeout: budget}, server.URL)
	if elapsed := time.Since(started); elapsed > budget+25*time.Millisecond {
		t.Fatalf("warm and measure exceeded total budget: elapsed=%s budget=%s", elapsed, budget)
	}
}

func TestSplitURLTestFourSeconds(t *testing.T) {
	primary, fallback := splitURLTestTimeout(4000)
	if primary != 3000 || fallback != 1000 {
		t.Fatalf("got %d+%d", primary, fallback)
	}
}

func TestURLTestDirectParentDeadlineAndCancellation(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer server.Close()
	for _, canceled := range []bool{false, true} {
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Millisecond)
		if canceled {
			cancel()
		}
		started := time.Now()
		_, err := urlTestDirect(ctx, &http.Client{Timeout: time.Second}, server.URL)
		cancel()
		if err == nil {
			t.Fatal("expected cancellation error")
		}
		if elapsed := time.Since(started); elapsed > 100*time.Millisecond {
			t.Fatalf("parent exceeded: %s", elapsed)
		}
	}
}

func TestURLTestDirectRetryBodyAndFallbackShareDeadline(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodHead {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	started := time.Now()
	_, _ = urlTestDirect(ctx, &http.Client{Timeout: 70 * time.Millisecond}, server.URL)
	_, _ = urlTestDirect(ctx, &http.Client{Timeout: 70 * time.Millisecond}, server.URL)
	if elapsed := time.Since(started); elapsed > 140*time.Millisecond {
		t.Fatalf("retry/body/fallback extended parent deadline: %s", elapsed)
	}
}

func TestBoxCloseCancelsProbeContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	instance := &BoxInstance{ctx: ctx, cancel: cancel, closeBox: func() error { return nil }}
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
	if instance.ctx.Err() != context.Canceled {
		t.Fatal("close did not cancel instance probe parent")
	}
}
