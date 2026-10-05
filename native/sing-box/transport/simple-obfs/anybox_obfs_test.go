package obfs

import (
	"io"
	"net"
	"testing"
	"time"
)

func TestAnyBoxFragmentedHTTP(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	client.SetDeadline(time.Now().Add(time.Second))
	go func() {
		defer server.Close()
		for _, s := range []string{"HTTP/1.1 101 Switching", " Protocols\r\nX-Test: true\r", "\n\r\nPAY", "LOAD"} {
			server.Write([]byte(s))
		}
	}()
	conn := NewHTTPObfs(client, "test", "80")
	bytes := make([]byte, 7)
	if _, err := io.ReadFull(conn, bytes); err != nil {
		t.Fatal(err)
	}
	if string(bytes) != "PAYLOAD" {
		t.Fatal(string(bytes))
	}
}
func TestAnyBoxTLSLengthError(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	go func() { server.Write([]byte{0, 0, 0, 0}); server.Close() }()
	conn := &TLSObfs{Conn: client}
	n, err := conn.Read(make([]byte, 32))
	if n != 0 || err == nil {
		t.Fatalf("%d %v", n, err)
	}
}
