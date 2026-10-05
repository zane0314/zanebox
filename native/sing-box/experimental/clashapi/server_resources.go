package clashapi

import (
	"archive/zip"
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/ntp"
	"github.com/sagernet/sing/service/filemanager"
)

func (s *Server) checkAndDownloadExternalUI() {
	if s.externalUI == "" {
		return
	}
	entries, err := filemanager.ReadDir(s.ctx, s.externalUI)
	if err != nil {
		filemanager.MkdirAll(s.ctx, s.externalUI, 0o755)
	}
	if len(entries) == 0 {
		err = s.downloadExternalUI()
		if err != nil {
			s.logger.Error("download external ui error: ", err)
		}
	}
}

const (
	externalUIMaxDownload = 32 << 20
	externalUIMaxExpanded = 128 << 20
	externalUIMaxEntries  = 10000
)

// ponytail: infrequent UI updates share one slot; use per-directory slots only
// if independent panel updates need to run concurrently.
var externalUIUpdate = make(chan struct{}, 1)

func (s *Server) downloadExternalUI() error {
	ctx, cancel := context.WithTimeout(s.ctx, time.Minute)
	defer cancel()
	select {
	case externalUIUpdate <- struct{}{}:
		defer func() { <-externalUIUpdate }()
	case <-ctx.Done():
		return ctx.Err()
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	var downloadURL string
	if s.externalUIDownloadURL != "" {
		downloadURL = s.externalUIDownloadURL
	} else {
		downloadURL = "https://github.com/MetaCubeX/Yacd-meta/archive/gh-pages.zip"
	}
	var detour adapter.Outbound
	if s.externalUIDownloadDetour != "" {
		outbound, loaded := s.outbound.Outbound(s.externalUIDownloadDetour)
		if !loaded {
			return E.New("detour outbound not found: ", s.externalUIDownloadDetour)
		}
		detour = outbound
	} else {
		outbound := s.outbound.Default()
		detour = outbound
	}
	s.logger.Info("downloading external ui using outbound/", detour.Type(), "[", detour.Tag(), "]")
	httpClient := &http.Client{
		Transport: &http.Transport{
			ForceAttemptHTTP2:     true,
			TLSHandshakeTimeout:   C.TCPTimeout,
			ResponseHeaderTimeout: 15 * time.Second,
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return detour.DialContext(ctx, network, M.ParseSocksaddr(addr))
			},
			TLSClientConfig: &tls.Config{
				Time:    ntp.TimeFuncFromContext(s.ctx),
				RootCAs: adapter.RootPoolFromContext(s.ctx),
			},
		},
	}
	defer httpClient.CloseIdleConnections()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, downloadURL, nil)
	if err != nil {
		return err
	}
	response, err := httpClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return E.New("download external ui failed: ", response.Status)
	}
	if response.ContentLength > externalUIMaxDownload {
		return E.New("external UI download exceeds size limit")
	}
	return s.downloadZIP(ctx, response.Body, s.externalUI)
}

func (s *Server) downloadZIP(ctx context.Context, body io.Reader, output string) error {
	output = filepath.Clean(filemanager.BasePath(ctx, output))
	if output == "." || output == string(filepath.Separator) {
		return E.New("invalid external UI directory")
	}
	if err := filemanager.MkdirAll(ctx, filepath.Dir(output), 0o755); err != nil {
		return err
	}
	stage, err := os.MkdirTemp(filepath.Dir(output), ".anybox-ui-")
	if err != nil {
		return err
	}
	keepStage := false
	defer func() {
		if !keepStage {
			_ = os.RemoveAll(stage)
		}
	}()
	if err = filemanager.Chown(ctx, stage); err != nil {
		return err
	}
	tempFile, err := os.Create(filepath.Join(stage, "download.zip"))
	if err != nil {
		return err
	}
	n, copyErr := io.Copy(tempFile, io.LimitReader(body, externalUIMaxDownload+1))
	closeErr := tempFile.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if n > externalUIMaxDownload {
		return E.New("external UI download exceeds size limit")
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	reader, err := zip.OpenReader(tempFile.Name())
	if err != nil {
		return err
	}
	defer reader.Close()
	if len(reader.File) == 0 || len(reader.File) > externalUIMaxEntries {
		return E.New("invalid external UI entry count")
	}
	var expanded uint64
	for _, file := range reader.File {
		name := strings.TrimSuffix(file.Name, "/")
		if !filepath.IsLocal(name) || strings.Contains(name, "\\") || filepath.ToSlash(filepath.Clean(name)) != name {
			return E.New("invalid external UI archive path")
		}
		if !file.Mode().IsRegular() && !file.FileInfo().IsDir() {
			return E.New("unsupported external UI entry type")
		}
		if file.UncompressedSize64 > externalUIMaxExpanded-expanded {
			return E.New("external UI expanded size exceeds limit")
		}
		expanded += file.UncompressedSize64
	}
	trimDir := zipIsInSingleDirectory(reader.File)
	extracted := filepath.Join(stage, "new")
	if err = filemanager.Mkdir(ctx, extracted, 0o755); err != nil {
		return err
	}
	for _, file := range reader.File {
		if err = ctx.Err(); err != nil {
			return err
		}
		if file.FileInfo().IsDir() {
			continue
		}
		name := file.Name
		if trimDir {
			_, name, _ = strings.Cut(name, "/")
		}
		target := filepath.Join(extracted, filepath.FromSlash(name))
		if err = filemanager.MkdirAll(ctx, filepath.Dir(target), 0o755); err != nil {
			return err
		}
		if err = downloadZIPEntry(ctx, file, target); err != nil {
			return err
		}
	}
	index, err := os.Stat(filepath.Join(extracted, "index.html"))
	if err != nil || !index.Mode().IsRegular() {
		return E.New("external UI index.html missing")
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	previous := filepath.Join(stage, "old")
	hasPrevious := false
	if info, statErr := os.Lstat(output); statErr == nil {
		if !info.IsDir() {
			return E.New("external UI target is not a directory")
		}
		if err = filemanager.Rename(ctx, output, previous); err != nil {
			return err
		}
		hasPrevious = true
	} else if !os.IsNotExist(statErr) {
		return statErr
	}
	if err = filemanager.Rename(ctx, extracted, output); err != nil {
		if hasPrevious {
			if restoreErr := filemanager.Rename(ctx, previous, output); restoreErr != nil {
				keepStage = true
				return E.Cause(E.Errors(err, restoreErr), "external UI rollback failed; previous panel retained at ", previous)
			}
		}
		return err
	}
	return nil
}

func downloadZIPEntry(ctx context.Context, zipFile *zip.File, savePath string) error {
	reader, err := zipFile.Open()
	if err != nil {
		return err
	}
	defer reader.Close()
	saveFile, err := filemanager.OpenFile(ctx, savePath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
	if err != nil {
		return err
	}
	n, copyErr := io.Copy(saveFile, io.LimitReader(reader, int64(zipFile.UncompressedSize64)+1))
	closeErr := saveFile.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if uint64(n) != zipFile.UncompressedSize64 {
		return E.New("external UI entry size mismatch")
	}
	return nil
}

func zipIsInSingleDirectory(files []*zip.File) bool {
	var singleDirectory string
	for _, file := range files {
		if file.FileInfo().IsDir() {
			continue
		}
		directory, _, found := strings.Cut(file.Name, "/")
		if !found {
			return false
		}
		if singleDirectory == "" {
			singleDirectory = directory
		} else if singleDirectory != directory {
			return false
		}
	}
	return singleDirectory != ""
}
