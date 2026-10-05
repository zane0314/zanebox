package wireguard

import (
	"context"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"net"
	"sync"
	"testing"
	"time"
)

type anyboxWaitingDialer struct {
	N.Dialer
	started chan struct{}
}

func (d anyboxWaitingDialer) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	close(d.started)
	<-ctx.Done()
	return nil, ctx.Err()
}
func TestAnyBoxBindCloseCancelsDial(t *testing.T) {
	started := make(chan struct{})
	bind := &ClientBind{ctx: context.Background(), done: make(chan struct{}), isConnect: true, dialer: anyboxWaitingDialer{started: started}}
	bind.Open(0)
	result := make(chan error, 1)
	go func() { _, err := bind.connect(); result <- err }()
	<-started
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); bind.Close() }()
	}
	wg.Wait()
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("closed dial succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("close did not cancel dial")
	}
	if _, err := bind.connect(); err == nil {
		t.Fatal("connection created after close")
	}
}
