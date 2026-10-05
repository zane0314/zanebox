package libcore

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type anyboxCloseBody struct {
	io.Reader
	closed atomic.Bool
}

func (b *anyboxCloseBody) Close() error { b.closed.Store(true); return nil }
func TestAnyBoxHTTPOwnership(t *testing.T) {
	loser := &anyboxCloseBody{Reader: strings.NewReader("loser")}
	loserReady := make(chan struct{})
	releaseLoser := make(chan struct{})
	var winnerContext context.Context
	winner := func(ctx context.Context) (*http.Response, error) {
		winnerContext = ctx
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader("winner"))}, nil
	}
	late := func(ctx context.Context) (*http.Response, error) {
		close(loserReady)
		<-releaseLoser
		return &http.Response{StatusCode: 200, Body: loser}, nil
	}
	response, err := raceHTTPRequests(context.Background(), []requestFunc{winner, late}, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if winnerContext.Err() != nil {
		t.Fatal("winner body canceled prematurely")
	}
	body, err := io.ReadAll(response.Body)
	if err != nil || string(body) != "winner" {
		t.Fatal(err, string(body))
	}
	response.Body.Close()
	if winnerContext.Err() == nil {
		t.Fatal("winner cleanup missing")
	}
	<-loserReady
	close(releaseLoser)
	deadline := time.Now().Add(time.Second)
	for !loser.closed.Load() && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if !loser.closed.Load() {
		t.Fatal("late loser body leaked")
	}
	_, err = raceHTTPRequests(context.Background(), []requestFunc{func(ctx context.Context) (*http.Response, error) { <-ctx.Done(); return nil, ctx.Err() }}, time.Millisecond)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal(err)
	}
}
