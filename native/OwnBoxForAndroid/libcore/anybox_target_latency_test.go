package libcore

import "testing"

func TestParseURLTestResults(t *testing.T) {
	got := parseURLTestResults("node-1\t40\nnode-2\t0\nbad\nnode-3\tx\n\tnode-4\nnode-5\t70000\nnode-6\t12")
	want := map[string]uint16{"node-1": 40, "node-2": 0, "node-3": 0, "node-5": 0, "node-6": 12}
	if len(got) != len(want) {
		t.Fatal(got)
	}
	for tag, delay := range want {
		if got[tag] != delay {
			t.Fatal(tag, got[tag], delay)
		}
	}
}
