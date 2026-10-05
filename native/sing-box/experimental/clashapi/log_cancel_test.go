package clashapi

import (
	"context"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/observable"
	"net/http/httptest"
	"testing"
	"time"
)

type anyBoxLogFactory struct {
	log.ObservableFactory
	subscription observable.Subscription[log.Entry]
	subscribed   chan struct{}
	released     chan struct{}
}

func (f *anyBoxLogFactory) Subscribe() (observable.Subscription[log.Entry], <-chan struct{}, error) {
	close(f.subscribed)
	return f.subscription, make(chan struct{}), nil
}
func (f *anyBoxLogFactory) UnSubscribe(observable.Subscription[log.Entry]) { close(f.released) }
func TestAnyBoxLogCancellation(t *testing.T) {
	for _, closeSource := range []bool{false, true} {
		ch := make(chan log.Entry)
		factory := &anyBoxLogFactory{subscription: ch, subscribed: make(chan struct{}), released: make(chan struct{})}
		ctx, cancel := context.WithCancel(context.Background())
		r := httptest.NewRequest("GET", "/logs", nil).WithContext(ctx)
		go getLogs(context.Background(), factory)(httptest.NewRecorder(), r)
		<-factory.subscribed
		if closeSource {
			close(ch)
		} else {
			cancel()
		}
		select {
		case <-factory.released:
		case <-time.After(time.Second):
			t.Fatal("idle subscription leaked")
		}
		cancel()
	}
}
