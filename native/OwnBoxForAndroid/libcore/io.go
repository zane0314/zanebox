package libcore

import (
	"archive/zip"
	"fmt"
	"github.com/ulikunitz/xz"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const maxAssetBytes int64 = 256 << 20

func Unxz(archive string, path string) error {
	input, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer input.Close()
	reader, err := xz.NewReader(input)
	if err != nil {
		return err
	}
	output, err := os.CreateTemp(filepath.Dir(path), ".unxz-*")
	if err != nil {
		return err
	}
	defer os.Remove(output.Name())
	defer output.Close()
	n, err := io.Copy(output, io.LimitReader(reader, maxAssetBytes+1))
	if err != nil {
		return err
	}
	if n > maxAssetBytes {
		return fmt.Errorf("expanded asset exceeds size limit")
	}
	if err = output.Sync(); err != nil {
		return err
	}
	if err = output.Close(); err != nil {
		return err
	}
	return os.Rename(output.Name(), path)
}

func Unzip(archive string, path string) error {
	reader, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer reader.Close()
	if len(reader.File) > 10000 {
		return fmt.Errorf("too many zip entries")
	}
	seen := make(map[string]bool)
	var declared uint64
	for _, file := range reader.File {
		name := filepath.Clean(file.Name)
		if !filepath.IsLocal(file.Name) || strings.Contains(file.Name, "\\") || file.Mode()&os.ModeSymlink != 0 || seen[name] {
			return fmt.Errorf("invalid zip entry")
		}
		seen[name] = true
		if file.UncompressedSize64 > uint64(maxAssetBytes)-declared {
			return fmt.Errorf("zip exceeds size limit")
		}
		declared += file.UncompressedSize64
	}
	if err = os.MkdirAll(path, 0700); err != nil {
		return err
	}
	root, err := os.OpenRoot(path)
	if err != nil {
		return err
	}
	defer root.Close()
	remaining := maxAssetBytes
	for _, file := range reader.File {
		if file.FileInfo().IsDir() {
			if err = root.MkdirAll(file.Name, 0700); err != nil {
				return err
			}
			continue
		}
		if err = root.MkdirAll(filepath.Dir(file.Name), 0700); err != nil {
			return err
		}
		input, err := file.Open()
		if err != nil {
			return err
		}
		output, err := root.OpenFile(file.Name, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if err != nil {
			input.Close()
			return err
		}
		n, copyErr := io.Copy(output, io.LimitReader(input, remaining+1))
		input.Close()
		closeErr := output.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
		remaining -= n
		if remaining < 0 {
			return fmt.Errorf("expanded zip exceeds size limit")
		}
	}
	return nil
}
