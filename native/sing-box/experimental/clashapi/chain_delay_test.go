package clashapi

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	outboundmanager "github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
)

type chainDelayEndpoint struct {
	adapter.Endpoint
	address string
}

func (*chainDelayEndpoint) Type() string      { return "wireguard" }
func (*chainDelayEndpoint) Tag() string       { return "chain-1-landing-2" }
func (*chainDelayEndpoint) Network() []string { return []string{"tcp", "udp"} }
func (o *chainDelayEndpoint) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	if address.String() != o.address {
		return nil, fmt.Errorf("unexpected delay target %s", address)
	}
	return (&net.Dialer{}).DialContext(ctx, network, address.String())
}

type chainDelayEndpoints struct {
	adapter.EndpointManager
	endpoint adapter.Endpoint
}

func (m chainDelayEndpoints) Get(tag string) (adapter.Endpoint, bool) {
	return m.endpoint, tag == m.endpoint.Tag()
}

func TestChainEndpointDelayUsesRequestedHTTPURL(t *testing.T) {
	requested := make(chan string, 1)
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requested <- r.URL.Path
		time.Sleep(5 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer origin.Close()
	address, err := url.Parse(origin.URL)
	if err != nil {
		t.Fatal(err)
	}
	endpoint := &chainDelayEndpoint{address: address.Host}
	endpoints := chainDelayEndpoints{endpoint: endpoint}
	manager := outboundmanager.NewManager(log.NewNOPFactory().Logger(), nil, endpoints, "")
	server := &Server{ctx: context.Background(), outbound: manager, endpoint: endpoints, urlTestHistory: urltest.NewHistoryStorage()}
	router := proxyRouter(server, nil)
	request := httptest.NewRequest(http.MethodGet, "/"+endpoint.Tag()+"/delay?timeout=1000&url="+url.QueryEscape(origin.URL+"/custom-probe"), nil)
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("endpoint delay status=%d body=%s", response.Code, response.Body)
	}
	select {
	case path := <-requested:
		if path != "/custom-probe" {
			t.Fatalf("HTTP probe path=%s", path)
		}
	default:
		t.Fatal("configured HTTP probe was not requested")
	}
	missing := httptest.NewRecorder()
	router.ServeHTTP(missing, httptest.NewRequest(http.MethodGet, "/missing/delay?timeout=1000", nil))
	if missing.Code != http.StatusNotFound {
		t.Fatalf("missing endpoint status=%d", missing.Code)
	}
}
