package tls

import (
	"context"
	stls "crypto/tls"
	M "github.com/sagernet/sing/common/metadata"
	"net"
	"sync"
	"testing"
)

type anyBoxECH struct{ *STDClientConfig }

func (c *anyBoxECH) Clone() Config { return &anyBoxECH{c.STDClientConfig.Clone().(*STDClientConfig)} }
func (c *anyBoxECH) ClientHandshake(ctx context.Context, conn net.Conn) (Conn, error) {
	if len(c.ECHConfigList()) == 1 {
		return nil, &stls.ECHRejectionError{RetryConfigList: []byte{2, 3}}
	}
	return &anyBoxTLSConn{Conn: conn}, nil
}

type anyBoxTLSConn struct{ net.Conn }

func (c *anyBoxTLSConn) NetConn() net.Conn                      { return c.Conn }
func (c *anyBoxTLSConn) HandshakeContext(context.Context) error { return nil }
func (c *anyBoxTLSConn) ConnectionState() stls.ConnectionState  { return stls.ConnectionState{} }

type anyBoxPipeDialer struct{}

func (anyBoxPipeDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	a, b := net.Pipe()
	b.Close()
	return a, nil
}
func (anyBoxPipeDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	panic("unused")
}
func TestAnyBoxECHRetryIsolation(t *testing.T) {
	config := &anyBoxECH{&STDClientConfig{config: &stls.Config{EncryptedClientHelloConfigList: []byte{1}}, serverName: "example.com"}}
	dialer := NewDialer(anyBoxPipeDialer{}, config)
	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, err := dialer.DialTLSContext(context.Background(), M.Socksaddr{})
			if err != nil {
				t.Error(err)
				return
			}
			conn.Close()
		}()
	}
	wg.Wait()
	if len(config.ECHConfigList()) != 1 {
		t.Fatal("retry changed shared configuration")
	}
}
