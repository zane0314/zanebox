//go:build linux

package process

import (
	"encoding/binary"
	"errors"
	"os"
	"syscall"
	"testing"
	"time"
)

// Real local socket I/O exercises the production request/response and timeout path.
// No process inspection or external network is involved.
func TestSocketDiagBoundedResponse(t *testing.T) {
	for _, delay := range []time.Duration{5 * time.Millisecond, 2 * socketDiagTimeout} {
		t.Run(delay.String(), func(t *testing.T) {
			pair, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_DGRAM|syscall.SOCK_CLOEXEC, 0)
			if err != nil {
				t.Fatal(err)
			}
			defer syscall.Close(pair[0])
			defer syscall.Close(pair[1])
			timeout := syscall.NsecToTimeval(socketDiagTimeout.Nanoseconds())
			if err = syscall.SetsockoptTimeval(pair[0], syscall.SOL_SOCKET, syscall.SO_RCVTIMEO, &timeout); err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() {
				b := make([]byte, 128)
				_, e := syscall.Read(pair[1], b)
				if e != nil {
					done <- e
					return
				}
				time.Sleep(delay)
				response := make([]byte, syscall.SizeofNlMsghdr+socketDiagResponseMinSize)
				binary.NativeEndian.PutUint32(response[0:4], uint32(len(response)))
				binary.NativeEndian.PutUint16(response[4:6], socketDiagByFamily)
				binary.NativeEndian.PutUint32(response[syscall.SizeofNlMsghdr+64:], 1234)
				binary.NativeEndian.PutUint32(response[syscall.SizeofNlMsghdr+68:], 5678)
				_, e = syscall.Write(pair[1], response)
				done <- e
			}()
			start := time.Now()
			inode, uid, err := querySocketDiag(pair[0], []byte("fixture request"))
			elapsed := time.Since(start)
			if delay < socketDiagTimeout {
				if err != nil || inode != 5678 || uid != 1234 {
					t.Fatalf("delayed reply: %d %d %v", inode, uid, err)
				}
			} else {
				if !errors.Is(err, os.ErrDeadlineExceeded) || errors.Is(err, ErrNotFound) {
					t.Fatalf("timeout classified incorrectly: %v", err)
				}
				if elapsed > 500*time.Millisecond {
					t.Fatalf("unbounded wait: %s", elapsed)
				}
			}
			if err := <-done; err != nil {
				t.Fatal(err)
			}
		})
	}
}
func TestSocketDiagTimeoutAndAbsence(t *testing.T) {
	if socketDiagTimeout < time.Millisecond || socketDiagTimeout > 100*time.Millisecond {
		t.Fatal(socketDiagTimeout)
	}
	for _, errno := range []syscall.Errno{syscall.EAGAIN, syscall.ETIMEDOUT} {
		err := socketDiagIOError(errno)
		if !errors.Is(err, os.ErrDeadlineExceeded) || !errors.Is(err, errno) || errors.Is(err, ErrNotFound) {
			t.Fatal(err)
		}
	}
	if !errors.Is(socketDiagIOError(syscall.EPERM), syscall.EPERM) {
		t.Fatal("permission failure changed")
	}
	for _, errno := range []syscall.Errno{syscall.ENOENT, syscall.ESRCH} {
		data := make([]byte, 4)
		binary.NativeEndian.PutUint32(data, uint32(-int32(errno)))
		err := unpackSocketDiagError(&syscall.NetlinkMessage{Data: data})
		if !errors.Is(err, ErrNotFound) || errors.Is(err, os.ErrDeadlineExceeded) {
			t.Fatal(err)
		}
	}
}
