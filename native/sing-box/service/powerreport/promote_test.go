package powerreport

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPromoteDirectoryKeepsDraftWhenRenameFails(t *testing.T) {
	basePath := t.TempDir()
	draftPath := filepath.Join(basePath, "draft")
	reportsPath := filepath.Join(draftPath, "reports")
	markerPath := filepath.Join(draftPath, "timeline", "events")
	if err := os.MkdirAll(filepath.Dir(markerPath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(markerPath, []byte("diagnostic data"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := promoteDirectory(draftPath, reportsPath); err == nil {
		t.Fatal("promoteDirectory succeeded when destination was inside the draft")
	}
	if _, err := os.Stat(markerPath); err != nil {
		t.Fatalf("draft marker was not preserved after rename failure: %v", err)
	}
}
