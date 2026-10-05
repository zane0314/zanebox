package clashapi

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/go-chi/chi/v5"
)

// Exercises the shipped authentication middleware, not a second implementation.
func TestAnyBoxLocalAuthentication(t *testing.T) {
	const fixtureSecret = "local-test-only"
	router := chi.NewRouter()
	router.Use(authentication(fixtureSecret))
	router.Get("/*", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) })
	for _, tc := range []struct {
		name, path, bearer, upgrade string
		want                       int
	}{
		{"missing", "/connections", "", "", 401},
		{"wrong", "/proxies", "Bearer wrong", "", 401},
		{"http", "/connections", "Bearer " + fixtureSecret, "", 204},
		{"query-is-not-http-auth", "/connections?token=" + fixtureSecret, "", "", 401},
		{"ws-missing", "/traffic", "", "websocket", 401},
		{"ws-wrong", "/traffic?token=wrong", "", "websocket", 401},
		{"ws-connections", "/connections?token=" + fixtureSecret, "", "websocket", 204},
		{"ws-traffic", "/traffic?token=" + fixtureSecret, "", "websocket", 204},
		{"ws-memory", "/memory?token=" + fixtureSecret, "", "websocket", 204},
		{"ws-logs", "/logs?token=" + fixtureSecret, "", "websocket", 204},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodGet, tc.path, nil)
			r.Header.Set("Authorization", tc.bearer)
			r.Header.Set("Upgrade", tc.upgrade)
			w := httptest.NewRecorder()
			router.ServeHTTP(w, r)
			if w.Code != tc.want {
				t.Fatalf("status = %d; want %d", w.Code, tc.want)
			}
		})
	}
}
