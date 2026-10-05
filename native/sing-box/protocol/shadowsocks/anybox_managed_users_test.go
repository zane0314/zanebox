package shadowsocks

import (
	"context"
	"encoding/base64"
	"fmt"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	ss "github.com/sagernet/sing-shadowsocks"
	"github.com/sagernet/sing-shadowsocks/shadowaead"
	"github.com/sagernet/sing-shadowsocks/shadowaead_2022"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"net"
	"sync"
	"testing"
	"time"
)

type managedUserRouter struct {
	adapter.Router
	users chan string
}

func (r *managedUserRouter) RouteConnection(_ context.Context, _ net.Conn, m adapter.InboundContext) error {
	r.users <- m.User
	return nil
}
func (r *managedUserRouter) RoutePacketConnection(_ context.Context, conn N.PacketConn, m adapter.InboundContext) error {
	defer conn.Close()
	r.users <- m.User
	return nil
}

type afterHeaderConn struct {
	net.Conn
	reads  int
	update func()
}

func (c *afterHeaderConn) Read(p []byte) (int, error) {
	c.reads++
	if c.reads == 2 && c.update != nil {
		c.update()
	}
	return c.Conn.Read(p)
}
func managedFixture(t *testing.T, modern bool) (*MultiInbound, ss.Method, *managedUserRouter, []string) {
	t.Helper()
	name := "aes-128-gcm"
	serverKey := base64.StdEncoding.EncodeToString([]byte("0123456789abcdef"))
	keys := []string{"alice-test-password", "bob-test-password"}
	var client ss.Method
	var err error
	if modern {
		name = "2022-blake3-aes-128-gcm"
		keys = []string{base64.StdEncoding.EncodeToString([]byte("alice-key-1234567")), base64.StdEncoding.EncodeToString([]byte("bob-key--1234567"))}
		client, err = shadowaead_2022.NewWithPassword(name, serverKey+":"+keys[0], nil)
	} else {
		client, err = shadowaead.New(name, nil, keys[0])
	}
	if err != nil {
		t.Fatal(err)
	}
	router := &managedUserRouter{users: make(chan string, 1024)}
	h, err := newMultiInbound(context.Background(), router, log.NewNOPFactory().Logger(), "managed-test", option.ShadowsocksInboundOptions{Method: name, Password: serverKey, Managed: true})
	if err != nil {
		t.Fatal(err)
	}
	if err = h.UpdateUsers([]string{"alice"}, keys[:1]); err != nil {
		t.Fatal(err)
	}
	return h, client, router, keys
}
func authenticateManaged(h *MultiInbound, client ss.Method, update func()) error {
	server, peer := net.Pipe()
	defer server.Close()
	defer peer.Close()
	server.SetDeadline(time.Now().Add(5 * time.Second))
	peer.SetDeadline(time.Now().Add(5 * time.Second))
	done := make(chan error, 1)
	go func() {
		done <- h.service.NewConnection(context.Background(), &afterHeaderConn{Conn: server, update: update}, M.Metadata{})
	}()
	_, err := client.DialConn(peer, M.ParseSocksaddr("example.invalid:443"))
	if err != nil {
		peer.Close()
		<-done
		return err
	}
	return <-done
}
func TestManagedUsersAuthenticatedIdentity(t *testing.T) {
	for _, modern := range []bool{false, true} {
		t.Run(fmt.Sprint(modern), func(t *testing.T) {
			h, client, router, keys := managedFixture(t, modern)
			called := false
			err := authenticateManaged(h, client, func() {
				called = true
				if err := h.UpdateUsers([]string{"bob", "alice"}, []string{keys[1], keys[0]}); err != nil {
					panic(err)
				}
			})
			if err != nil {
				t.Fatal(err)
			}
			if !called {
				t.Fatal("handshake did not reach update boundary")
			}
			if user := <-router.users; user != "alice" {
				t.Fatalf("authenticated alice mislabeled as %q after index reorder", user)
			}
		})
	}
}
func TestManagedUsersConcurrentUpdate(t *testing.T) {
	for _, modern := range []bool{false, true} {
		t.Run(fmt.Sprint(modern), func(t *testing.T) {
			h, client, router, keys := managedFixture(t, modern)
			var wg sync.WaitGroup
			start := make(chan struct{})
			wg.Add(1)
			go func() {
				defer wg.Done()
				<-start
				for n := 0; n < 300; n++ {
					var err error
					if n%2 == 0 {
						err = h.UpdateUsers([]string{"alice"}, keys[:1])
					} else {
						err = h.UpdateUsers([]string{"bob", "alice"}, []string{keys[1], keys[0]})
					}
					if err != nil {
						t.Error(err)
						return
					}
				}
			}()
			close(start)
			for n := 0; n < 60; n++ {
				if err := authenticateManaged(h, client, nil); err != nil {
					t.Error(err)
					break
				}
				if user := <-router.users; user != "alice" {
					t.Errorf("wrong identity %q", user)
					break
				}
			}
			wg.Wait()
		})
	}
}

// Encrypt actual UDP packets without opening a network socket.
type managedPacketCapture struct {
	net.Conn
	data []byte
}

func (c *managedPacketCapture) Write(p []byte) (int, error) {
	c.data = append(c.data, p...)
	return len(p), nil
}
func (c *managedPacketCapture) LocalAddr() net.Addr { return &net.UDPAddr{} }
func authenticateManagedPacket(h *MultiInbound, client ss.Method, port int) error {
	c := new(managedPacketCapture)
	_, err := client.DialPacketConn(c).WriteTo([]byte("payload"), M.ParseSocksaddr("example.invalid:443"))
	if err != nil {
		return err
	}
	b := buf.NewSize(len(c.data))
	b.Write(c.data)
	err = h.service.NewPacket(context.Background(), &stubPacketConn{}, b, M.Metadata{Source: M.ParseSocksaddr(fmt.Sprintf("127.0.0.1:%d", port))})
	if err != nil {
		b.Release()
	}
	return err
}
func TestManagedUsersPacketsAndFailedUpdate(t *testing.T) {
	for _, modern := range []bool{false, true} {
		t.Run(fmt.Sprint(modern), func(t *testing.T) {
			h, client, router, keys := managedFixture(t, modern)
			// Neither a partially valid replacement nor mismatched lists may wipe the old generation.
			for _, bad := range [][]string{{keys[1], ""}, nil} {
				if err := h.UpdateUsers([]string{"bob", "alice"}, bad); err == nil {
					t.Fatal("invalid update accepted")
				}
				if err := authenticateManaged(h, client, nil); err != nil {
					t.Fatal(err)
				}
				if got := <-router.users; got != "alice" {
					t.Fatalf("failed update changed identity: %q", got)
				}
			}
			var wg sync.WaitGroup
			wg.Add(1)
			go func() {
				defer wg.Done()
				for n := 0; n < 200; n++ {
					var err error
					if n%2 == 0 {
						err = h.UpdateUsers([]string{"alice"}, keys[:1])
					} else {
						err = h.UpdateUsers([]string{"bob", "alice"}, []string{keys[1], keys[0]})
					}
					if err != nil {
						t.Error(err)
						return
					}
				}
			}()
			for n := 0; n < 60; n++ {
				if err := authenticateManagedPacket(h, client, 20000+n); err != nil {
					t.Error(err)
					break
				}
				select {
				case got := <-router.users:
					if got != "alice" {
						t.Errorf("UDP wrong identity %q", got)
					}
				case <-time.After(5 * time.Second):
					t.Error("UDP callback did not finish")
				}
			}
			wg.Wait()
		})
	}
}
