package urltest

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type delayedTestDialer struct {
	dials atomic.Int32
}

func (d *delayedTestDialer) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	d.dials.Add(1)
	select {
	case <-time.After(120 * time.Millisecond):
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	return (&net.Dialer{}).DialContext(ctx, network, address.String())
}

func (*delayedTestDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	panic("not used")
}

func TestUnifiedDelayActuallyReusesConnectionAndExcludesDial(t *testing.T) {
	for _, enabled := range []bool{false, true} {
		t.Run(map[bool]string{false: "cold", true: "unified"}[enabled], func(t *testing.T) {
			var requests atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests.Add(1)
				time.Sleep(20 * time.Millisecond)
				w.WriteHeader(http.StatusNoContent)
			}))
			defer server.Close()
			dialer := new(delayedTestDialer)
			ctx, cancel := context.WithTimeout(WithUnifiedDelay(context.Background(), enabled), time.Second)
			defer cancel()
			delay, err := URLTest(ctx, server.URL, dialer)
			if err != nil {
				t.Fatal(err)
			}
			wantRequests := int32(1)
			if enabled {
				wantRequests = 2
				if delay >= 100 {
					t.Fatalf("unified delay includes the 120ms dial: %dms", delay)
				}
			} else if delay < 120 {
				t.Fatalf("cold delay omitted dial: %dms", delay)
			}
			if requests.Load() != wantRequests || dialer.dials.Load() != 1 {
				t.Fatalf("requests/dials = %d/%d, want %d/1", requests.Load(), dialer.dials.Load(), wantRequests)
			}
			t.Logf("unified=%t requests=%d connections=%d measured=%dms", enabled, requests.Load(), dialer.dials.Load(), delay)
		})
	}
}

func TestUnifiedDelayRedialFallsBackToColdAndKeepsDeadline(t *testing.T) {
	for _, closeConnection := range []bool{false, true} {
		var requests atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if requests.Add(1) == 1 {
				if closeConnection {
					w.Header().Set("Connection", "close")
				}
				w.WriteHeader(http.StatusNoContent)
				return
			}
			if !closeConnection {
				<-r.Context().Done()
				return
			}
			w.WriteHeader(http.StatusNoContent)
		}))
		dialer := new(delayedTestDialer)
		ctx, cancel := context.WithTimeout(WithUnifiedDelay(context.Background(), true), 300*time.Millisecond)
		started := time.Now()
		delay, err := URLTest(ctx, server.URL, dialer)
		cancel()
		server.Close()
		if err != nil || delay < 100 {
			t.Fatalf("fallback must retain first dial duration, got %dms %v", delay, err)
		}
		if elapsed := time.Since(started); elapsed > 400*time.Millisecond {
			t.Fatalf("deadline extended: %s", elapsed)
		}
	}
}
