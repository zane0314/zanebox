package powerreport

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
)

func PromoteDraft(basePath string) error {
	return promoteDirectory(filepath.Join(basePath, DraftDirectoryName), filepath.Join(basePath, ReportsDirectoryName))
}

func finalizeDraft(draftPath string) error {
	return promoteDirectory(draftPath, filepath.Join(filepath.Dir(draftPath), ReportsDirectoryName))
}

func promoteDirectory(draftPath string, reportsPath string) error {
	info, err := os.Stat(draftPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return fmt.Errorf("stat power report draft: %w", err)
	}
	if !info.IsDir() {
		return nil
	}
	entries, err := os.ReadDir(draftPath)
	if err != nil {
		return fmt.Errorf("read power report draft: %w", err)
	}
	if len(entries) == 0 {
		if err = os.RemoveAll(draftPath); err != nil {
			return fmt.Errorf("remove empty power report draft: %w", err)
		}
		return nil
	}
	err = os.MkdirAll(reportsPath, 0o777)
	if err != nil {
		return fmt.Errorf("create power report directory: %w", err)
	}
	destName := info.ModTime().UTC().Format("2006-01-02T15-04-05")
	destPath := filepath.Join(reportsPath, destName)
	for i := 1; ; i++ {
		_, err = os.Stat(destPath)
		if os.IsNotExist(err) {
			break
		}
		if err != nil {
			return fmt.Errorf("check power report destination: %w", err)
		}
		if i > 1000 {
			return fmt.Errorf("power report destination names exhausted")
		}
		destPath = filepath.Join(reportsPath, destName+"-"+strconv.Itoa(i))
	}
	err = os.Rename(draftPath, destPath)
	if err != nil {
		return fmt.Errorf("promote power report draft: %w", err)
	}
	return nil
}
