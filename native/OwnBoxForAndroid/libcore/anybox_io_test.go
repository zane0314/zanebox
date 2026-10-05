package libcore

import (
	"archive/zip"
	"bytes"
	"github.com/ulikunitz/xz"
	"os"
	"path/filepath"
	"testing"
)

func TestAnyBoxAssetIO(t *testing.T) {
	dir := t.TempDir()
	for _, name := range []string{"../escape", "/absolute", "safe/file"} {
		archive := filepath.Join(dir, "test.zip")
		f, _ := os.Create(archive)
		z := zip.NewWriter(f)
		w, _ := z.Create(name)
		w.Write([]byte("ok"))
		z.Close()
		f.Close()
		err := Unzip(archive, filepath.Join(dir, "out"))
		if name == "safe/file" && err != nil {
			t.Fatal(err)
		}
		if name != "safe/file" && err == nil {
			t.Fatal("unsafe entry accepted", name)
		}
	}
	target := filepath.Join(dir, "geo.db")
	os.WriteFile(target, []byte("old"), 0600)
	source := filepath.Join(dir, "geo.xz")
	os.WriteFile(source, []byte("invalid"), 0600)
	if Unxz(source, target) == nil {
		t.Fatal("invalid xz accepted")
	}
	current, _ := os.ReadFile(target)
	if string(current) != "old" {
		t.Fatal("previous asset damaged")
	}
	var data bytes.Buffer
	writer, _ := xz.NewWriter(&data)
	writer.Write([]byte("new"))
	writer.Close()
	os.WriteFile(source, data.Bytes(), 0600)
	if err := Unxz(source, target); err != nil {
		t.Fatal(err)
	}
	current, _ = os.ReadFile(target)
	if string(current) != "new" {
		t.Fatal("asset mismatch")
	}
}
