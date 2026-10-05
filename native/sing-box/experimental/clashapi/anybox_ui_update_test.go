package clashapi

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"
)

type uiTestOutbound struct{ adapter.Outbound }

func (uiTestOutbound) Type() string { return "direct" }
func (uiTestOutbound) Tag() string  { return "test" }
func (uiTestOutbound) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, address.String())
}

type uiTestOutbounds struct{ adapter.OutboundManager }

func (uiTestOutbounds) Default() adapter.Outbound { return uiTestOutbound{} }

func uiArchive(t *testing.T, names ...string) []byte {
	t.Helper()
	var b bytes.Buffer
	z := zip.NewWriter(&b)
	for _, name := range names {
		w, err := z.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = w.Write([]byte("new")); err != nil {
			t.Fatal(err)
		}
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}
func uiServer(t *testing.T, handler http.HandlerFunc) (*Server, context.CancelFunc) {
	t.Helper()
	origin := httptest.NewServer(handler)
	t.Cleanup(origin.Close)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	output := filepath.Join(t.TempDir(), "panel")
	if err := os.Mkdir(output, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(output, "index.html"), []byte("old"), 0600); err != nil {
		t.Fatal(err)
	}
	return &Server{ctx: ctx, logger: log.NewNOPFactory().Logger(), outbound: uiTestOutbounds{}, externalUI: output, externalUIDownloadURL: origin.URL}, cancel
}
func assertOldUI(t *testing.T, s *Server) {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(s.externalUI, "index.html"))
	if err != nil || string(b) != "old" {
		t.Fatalf("old UI lost: %q, %v", b, err)
	}
}
func TestAnyBoxUIFailedDownloadPreservesPanel(t *testing.T) {
	s, _ := uiServer(t, func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "not a zip") })
	if err := s.downloadExternalUI(); err == nil {
		t.Fatal("invalid zip accepted")
	}
	assertOldUI(t, s)
}
func TestAnyBoxUICancelDownload(t *testing.T) {
	entered := make(chan struct{})
	release := make(chan struct{})
	s, cancel := uiServer(t, func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		select {
		case <-r.Context().Done():
		case <-release:
		}
	})
	defer close(release)
	done := make(chan error, 1)
	go func() { done <- s.downloadExternalUI() }()
	<-entered
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("canceled download succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("download ignored cancellation")
	}
	assertOldUI(t, s)
}
func TestAnyBoxUIArchiveSafety(t *testing.T) {
	for _, names := range [][]string{{"index.html"}, {"site/index.html", "site/a.js"}, {"../escaped"}, {"site/index.html", "site/../../escaped"}, {"/absolute"}, {"site/a.js"}, {}} {
		name := "empty"
		if len(names) > 0 {
			name = names[0]
		}
		t.Run(name, func(t *testing.T) {
			data := uiArchive(t, names...)
			s, _ := uiServer(t, func(w http.ResponseWriter, r *http.Request) { w.Write(data) })
			err := s.downloadExternalUI()
			valid := len(names) > 0 && (names[0] == "index.html" || (len(names) == 2 && names[1] == "site/a.js"))
			if valid {
				if err != nil {
					t.Fatal(err)
				}
				b, e := os.ReadFile(filepath.Join(s.externalUI, "index.html"))
				if e != nil || string(b) != "new" {
					t.Fatalf("new panel missing: %q %v", b, e)
				}
			} else {
				if err == nil {
					t.Fatal("unsafe/incomplete zip accepted")
				}
				assertOldUI(t, s)
			}
			if _, err := os.Stat(filepath.Join(filepath.Dir(s.externalUI), "escaped")); !os.IsNotExist(err) {
				t.Fatal("zip escaped output")
			}
		})
	}
}
func TestAnyBoxUIConcurrentUpdates(t *testing.T) {
	data := uiArchive(t, "site/index.html", "site/a.js")
	s, _ := uiServer(t, func(w http.ResponseWriter, r *http.Request) { w.Write(data) })
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := s.downloadExternalUI(); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	b, e := os.ReadFile(filepath.Join(s.externalUI, "index.html"))
	if e != nil || string(b) != "new" {
		t.Fatalf("new panel missing: %q %v", b, e)
	}
}

func TestAnyBoxUIBudgetsAndTruncation(t *testing.T) {
	expanded := uiArchive(t, "site/index.html")
	central := bytes.Index(expanded, []byte{'P', 'K', 1, 2})
	binary.LittleEndian.PutUint32(expanded[central+24:], externalUIMaxExpanded+1)
	cases := map[string]http.HandlerFunc{
		"declared download": func(w http.ResponseWriter, r *http.Request) { w.Header().Set("Content-Length", "33554433") },
		"streamed download": func(w http.ResponseWriter, r *http.Request) {
			w.(http.Flusher).Flush()
			block := make([]byte, 1<<20)
			for i := 0; i < 33; i++ {
				if _, err := w.Write(block); err != nil {
					return
				}
			}
		},
		"expanded size": func(w http.ResponseWriter, r *http.Request) { w.Write(expanded) },
		"truncated body": func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Length", "1000")
			w.Write([]byte("short"))
		},
	}
	for name, handler := range cases {
		t.Run(name, func(t *testing.T) {
			s, _ := uiServer(t, handler)
			if err := s.downloadExternalUI(); err == nil {
				t.Fatal("invalid download accepted")
			}
			assertOldUI(t, s)
		})
	}
}

type uiRenameFailure struct {
	filemanager.Manager
	failRollback bool
	previous     string
}

func (m *uiRenameFailure) Rename(oldPath, newPath string) error {
	if filepath.Base(oldPath) == "new" {
		return errors.New("injected install failure")
	}
	if filepath.Base(oldPath) == "old" && m.failRollback {
		m.previous = oldPath
		return errors.New("injected rollback failure")
	}
	return m.Manager.Rename(oldPath, newPath)
}
func TestAnyBoxUIRenameRollback(t *testing.T) {
	for _, failRollback := range []bool{false, true} {
		t.Run(map[bool]string{false: "restored", true: "retained"}[failRollback], func(t *testing.T) {
			data := uiArchive(t, "index.html")
			s, _ := uiServer(t, func(w http.ResponseWriter, r *http.Request) { w.Write(data) })
			s.ctx = filemanager.WithDefault(s.ctx, "", "", os.Getuid(), os.Getgid())
			manager := &uiRenameFailure{Manager: service.FromContext[filemanager.Manager](s.ctx), failRollback: failRollback}
			s.ctx = service.ContextWith[filemanager.Manager](s.ctx, manager)
			err := s.downloadExternalUI()
			if err == nil {
				t.Fatal("injected rename failure ignored")
			}
			if failRollback {
				b, e := os.ReadFile(filepath.Join(manager.previous, "index.html"))
				if e != nil || string(b) != "old" {
					t.Fatalf("backup lost: %q %v", b, e)
				}
				if !strings.Contains(err.Error(), manager.previous) {
					t.Fatal("retained backup not reported")
				}
			} else {
				assertOldUI(t, s)
			}
		})
	}
}
func TestAnyBoxUISymlinkRejected(t *testing.T) {
	var data bytes.Buffer
	z := zip.NewWriter(&data)
	h := &zip.FileHeader{Name: "index.html"}
	h.SetMode(os.ModeSymlink | 0777)
	w, err := z.CreateHeader(h)
	if err != nil {
		t.Fatal(err)
	}
	w.Write([]byte("../outside"))
	z.Close()
	s, _ := uiServer(t, func(w http.ResponseWriter, r *http.Request) { w.Write(data.Bytes()) })
	if err = s.downloadExternalUI(); err == nil {
		t.Fatal("symlink accepted")
	}
	assertOldUI(t, s)
}

func TestAnyBoxUIEntryCountAndDuplicate(t *testing.T) {
	names := make([]string, externalUIMaxEntries+1)
	for i := range names {
		names[i] = "site/" + strconv.Itoa(i)
	}
	for _, entries := range [][]string{names, {"site/index.html", "site/index.html"}} {
		data := uiArchive(t, entries...)
		s, _ := uiServer(t, func(w http.ResponseWriter, r *http.Request) { w.Write(data) })
		if err := s.downloadExternalUI(); err == nil {
			t.Fatal("too many or duplicate entries accepted")
		}
		assertOldUI(t, s)
	}
}
