package urltest

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptrace"
	"sync/atomic"
	"time"
)

type unifiedDelayKey struct{}

// Policy belongs to a box/request context, never a process-wide switch: test
// instances and the running VPN may have different settings during a reload.
func WithUnifiedDelay(ctx context.Context, enabled bool) context.Context {
	return context.WithValue(ctx, unifiedDelayKey{}, enabled)
}

func UnifiedDelayPolicy(ctx context.Context) (bool, bool) {
	value, ok := ctx.Value(unifiedDelayKey{}).(bool)
	return value, ok
}

// MeasureDelay uses the same deadline for warm-up and measurement. Only a
// confirmed reused connection qualifies for the warm measurement; a redial or
// failed second request falls back to the first complete request's duration.
func MeasureDelay(ctx context.Context, client *http.Client, request *http.Request, unified bool) (int32, error) {
	request = request.Clone(ctx)
	started := time.Now()
	resp, err := client.Do(request)
	if err != nil {
		return 0, err
	}
	_, copyErr := io.Copy(io.Discard, io.LimitReader(resp.Body, 8192))
	_ = resp.Body.Close()
	if copyErr != nil {
		return 0, copyErr
	}
	if resp.StatusCode >= 500 {
		return 0, fmt.Errorf("HTTP error %d", resp.StatusCode)
	}
	first := time.Since(started)
	measured := first
	if unified && ctx.Err() == nil {
		var reused atomic.Bool
		trace := &httptrace.ClientTrace{GotConn: func(info httptrace.GotConnInfo) { reused.Store(info.Reused) }}
		second := request.Clone(httptrace.WithClientTrace(ctx, trace))
		started = time.Now()
		resp, err = client.Do(second)
		if err == nil {
			_, copyErr = io.Copy(io.Discard, io.LimitReader(resp.Body, 8192))
			_ = resp.Body.Close()
			if copyErr == nil && reused.Load() && resp.StatusCode < 500 {
				measured = time.Since(started)
			}
		}
	}
	latency := int32(measured.Milliseconds())
	if latency <= 0 {
		latency = 1
	}
	return latency, nil
}
