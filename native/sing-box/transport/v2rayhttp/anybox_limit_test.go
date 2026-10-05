package v2rayhttp

import (
	"bufio"
	"context"
	"github.com/sagernet/sing/common/logger"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

type rejectedHijacker struct{ *httptest.ResponseRecorder }

func (rejectedHijacker) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	panic("oversized request hijacked")
}
func TestAnyBoxHTTP1BodyLimit(t *testing.T) {
	server := &Server{logger: logger.NOP()}
	request, _ := http.NewRequestWithContext(context.Background(), "GET", "http://localhost/", nil)
	request.ContentLength = 1 << 40
	recorder := httptest.NewRecorder()
	server.ServeHTTP(rejectedHijacker{recorder}, request)
	if recorder.Code != http.StatusRequestEntityTooLarge {
		t.Fatal(recorder.Code)
	}
}
