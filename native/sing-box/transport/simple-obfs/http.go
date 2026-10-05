package obfs

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"fmt"
	"math/rand"
	"net"
	"net/http"
)

// HTTPObfs is shadowsocks http simple-obfs implementation
type HTTPObfs struct {
	net.Conn
	host          string
	port          string
	reader        *bufio.Reader
	firstRequest  bool
	firstResponse bool
}

func (ho *HTTPObfs) Read(b []byte) (int, error) {
	if len(b) == 0 {
		return 0, nil
	}
	if ho.reader == nil {
		ho.reader = bufio.NewReader(ho.Conn)
	}
	if ho.firstResponse {
		var marker uint32
		for count := 0; ; count++ {
			if count >= 16384 {
				ho.Conn.Close()
				return 0, fmt.Errorf("obfs response headers too large")
			}
			value, err := ho.reader.ReadByte()
			if err != nil {
				ho.Conn.Close()
				return 0, err
			}
			marker = marker<<8 | uint32(value)
			if marker == 0x0d0a0d0a {
				break
			}
		}
		ho.firstResponse = false
	}
	return ho.reader.Read(b)
}

func (ho *HTTPObfs) Write(b []byte) (int, error) {
	if ho.firstRequest {
		randBytes := make([]byte, 16)
		rand.Read(randBytes)
		req, _ := http.NewRequest("GET", fmt.Sprintf("http://%s/", ho.host), bytes.NewBuffer(b[:]))
		req.Header.Set("User-Agent", fmt.Sprintf("curl/7.%d.%d", rand.Int()%54, rand.Int()%2))
		req.Header.Set("Upgrade", "websocket")
		req.Header.Set("Connection", "Upgrade")
		req.Host = ho.host
		if ho.port != "80" {
			req.Host = fmt.Sprintf("%s:%s", ho.host, ho.port)
		}
		req.Header.Set("Sec-WebSocket-Key", base64.URLEncoding.EncodeToString(randBytes))
		req.ContentLength = int64(len(b))
		err := req.Write(ho.Conn)
		ho.firstRequest = false
		return len(b), err
	}

	return ho.Conn.Write(b)
}

func (ho *HTTPObfs) Upstream() any {
	return ho.Conn
}

// NewHTTPObfs return a HTTPObfs
func NewHTTPObfs(conn net.Conn, host string, port string) net.Conn {
	return &HTTPObfs{
		Conn:          conn,
		firstRequest:  true,
		firstResponse: true,
		host:          host,
		port:          port,
	}
}
