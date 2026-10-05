package libcore

import "testing"

func TestSplitURLTestTimeoutKeepsTotalBudget(t *testing.T) {
	for _, timeout := range []int32{1, 500, 1500, 2000, 3000, 3500, 5000} {
		primary, fallback := splitURLTestTimeout(timeout)
		if primary+fallback > timeout {
			t.Fatalf("timeout=%d primary=%d fallback=%d", timeout, primary, fallback)
		}
		if primary <= 0 {
			t.Fatalf("timeout=%d has no primary budget", timeout)
		}
	}
}
