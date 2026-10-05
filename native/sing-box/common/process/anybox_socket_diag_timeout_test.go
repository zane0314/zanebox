package process

import (
	"errors"
	"os"
	"syscall"
	"testing"
	"time"
)

func TestSocketDiagTimeoutClassification(t *testing.T) {
	if socketDiagTimeout < time.Millisecond || socketDiagTimeout > 100*time.Millisecond {
		t.Fatal(socketDiagTimeout)
	}
	for _, errno := range []syscall.Errno{syscall.EAGAIN, syscall.ETIMEDOUT} {
		err := socketDiagIOError(errno)
		if !errors.Is(err, os.ErrDeadlineExceeded) || !errors.Is(err, errno) || errors.Is(err, ErrNotFound) {
			t.Fatalf("bad timeout classification: %v", err)
		}
	}
	for _, err := range []error{nil, syscall.EPERM, ErrNotFound} {
		if socketDiagIOError(err) != err {
			t.Fatalf("unrelated error changed: %v", err)
		}
	}
}
