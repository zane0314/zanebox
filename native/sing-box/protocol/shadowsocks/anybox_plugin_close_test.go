package shadowsocks

import (
	"context"
	"errors"
	"net"
	"testing"
)

type anyboxClosingPlugin struct{ closes int }

func (p *anyboxClosingPlugin) DialContext(context.Context) (net.Conn, error) {
	return nil, errors.New("unused")
}
func (p *anyboxClosingPlugin) Close() error { p.closes++; return nil }

func TestAnyBoxPluginLifecycle(t *testing.T) {
	p := &anyboxClosingPlugin{}
	o := &Outbound{plugin: p}
	o.InterfaceUpdated(context.Background())
	if p.closes != 1 {
		t.Fatalf("interface update left plugin open: %d", p.closes)
	}
	if err := o.Close(); err != nil {
		t.Fatal(err)
	}
	if p.closes != 2 {
		t.Fatalf("outbound close left plugin open: %d", p.closes)
	}
}
