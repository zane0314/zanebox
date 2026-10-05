package clashapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestParseConnectionInterval(t *testing.T) {
	tests := []struct {
		name  string
		raw   string
		want  time.Duration
		valid bool
	}{
		{name: "default", want: connectionIntervalDefault, valid: true},
		{name: "minimum", raw: "100", want: connectionIntervalMin, valid: true},
		{name: "maximum", raw: "60000", want: connectionIntervalMax, valid: true},
		{name: "below minimum", raw: "99"},
		{name: "zero", raw: "0"},
		{name: "negative", raw: "-1"},
		{name: "above maximum", raw: "60001"},
		{name: "large integer", raw: "9223372036854775807"},
		{name: "integer overflow", raw: "9223372036854775808"},
		{name: "not an integer", raw: "one second"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, valid := parseConnectionInterval(test.raw)
			if valid != test.valid {
				t.Fatalf("parseConnectionInterval(%q) valid = %v, want %v", test.raw, valid, test.valid)
			}
			if valid && got != test.want {
				t.Fatalf("parseConnectionInterval(%q) = %s, want %s", test.raw, got, test.want)
			}
		})
	}
}

func TestGetConnectionsRejectsInvalidIntervalBeforeUpgrade(t *testing.T) {
	for _, raw := range []string{"0", "-1", "60001", "9223372036854775807"} {
		t.Run(raw, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/connections?interval="+raw, nil)
			req.Header.Set("Upgrade", "websocket")
			response := httptest.NewRecorder()

			getConnections(context.Background(), nil)(response, req)

			if response.Code != http.StatusBadRequest {
				t.Fatalf("invalid interval response status = %d, want %d", response.Code, http.StatusBadRequest)
			}
			if !strings.Contains(response.Body.String(), ErrBadRequest.Message) {
				t.Fatalf("invalid interval response = %q, want %q", response.Body.String(), ErrBadRequest.Message)
			}
		})
	}
}
