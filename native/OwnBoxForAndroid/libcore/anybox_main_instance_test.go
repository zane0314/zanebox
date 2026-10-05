package libcore

import (
	"sync"
	"testing"
)

func TestAnyBoxMainInstanceOwnership(t *testing.T) {
	oldPath := protectSocketPath
	protectSocketPath = t.TempDir() + "/protect"
	defer func() { protectSocketPath = oldPath }()
	a, b := &BoxInstance{}, &BoxInstance{}
	a.SetAsMain()
	b.SetAsMain()
	if err := a.Close(); err != nil {
		t.Fatal(err)
	}
	if mainInstance.Load() != b {
		t.Fatal("old close removed current instance")
	}
	a.SetAsMain()
	if mainInstance.Load() != b {
		t.Fatal("closed instance became main")
	}
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				_ = mainInstance.Load()
			}
		}()
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	wg.Wait()
	if mainInstance.Load() != nil {
		t.Fatal("closed main remains published")
	}
}
