package libcore

import (
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

func TestAnyBoxProtectAck(t *testing.T) {
	dir, err := os.MkdirTemp("/tmp", "ab-protect-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	for _, fail := range []bool{false, true} {
		path := filepath.Join(dir, "protect")
		server := serveProtect(path, func(fd int) error {
			if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil {
				return err
			}
			if fail {
				return errors.New("injected protection rejection")
			}
			return nil
		})
		if server == nil {
			t.Fatal("protect server did not listen")
		}
		fd, err := unix.Socket(unix.AF_INET, unix.SOCK_STREAM, 0)
		if err != nil {
			server.Close()
			t.Fatal(err)
		}
		err = sendFdToProtect(fd, path)
		// Closing the transferred descriptor must never close the sender's copy.
		_, originalErr := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0)
		unix.Close(fd)
		server.Close()
		if originalErr != nil {
			t.Fatal(originalErr)
		}
		if fail {
			if err == nil || !strings.Contains(err.Error(), "protection rejected") {
				t.Fatalf("rejection lost: %v", err)
			}
		} else if err != nil {
			t.Fatalf("successful protection rejected: %v", err)
		}
	}
}

func TestAnyBoxProtectSCMRightsClosesReceivedFD(t *testing.T) {
	for _, tc := range []struct {
		name      string
		callback  func(int) error
		wantError bool
	}{
		{name: "success", callback: func(int) error { return nil }},
		{name: "callback error", callback: func(int) error {
			return errors.New("injected callback error")
		}, wantError: true},
		{name: "callback panic", callback: func(int) error {
			panic("injected callback panic")
		}, wantError: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir, err := os.MkdirTemp("/tmp", "ab-protect-")
			if err != nil {
				t.Fatal(err)
			}
			defer os.RemoveAll(dir)
			path := filepath.Join(dir, "protect")
			listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()

			senderFD, err := unix.Socket(unix.AF_INET, unix.SOCK_STREAM, 0)
			if err != nil {
				t.Fatal(err)
			}
			defer unix.Close(senderFD)

			receivedFD := -1
			sendDone := make(chan error, 1)
			go func() { sendDone <- sendFdToProtect(senderFD, path) }()

			conn, err := listener.AcceptUnix()
			if err != nil {
				t.Fatal(err)
			}
			callback := func(fd int) error {
				receivedFD = fd
				if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil {
					return err
				}
				return tc.callback(fd)
			}
			// Synchronously run the real SCM_RIGHTS receiver so all deferred
			// closes have completed before the received fd is inspected.
			handleProtectConn(conn, callback)
			sendErr := <-sendDone

			if receivedFD < 0 {
				t.Fatal("protect callback did not receive an fd")
			}
			if _, err := unix.FcntlInt(uintptr(receivedFD), unix.F_GETFD, 0); !errors.Is(err, unix.EBADF) {
				t.Fatalf("received fd = %d, after handler return F_GETFD error = %v, want EBADF", receivedFD, err)
			}
			if _, err := unix.FcntlInt(uintptr(senderFD), unix.F_GETFD, 0); err != nil {
				t.Fatalf("sender fd was closed: %v", err)
			}
			if tc.wantError {
				if sendErr == nil {
					t.Fatal("protect unexpectedly succeeded")
				}
			} else if sendErr != nil {
				t.Fatalf("protect failed: %v", sendErr)
			}
		})
	}
}

type anyboxProtectPlatform struct {
	BoxPlatformInterface
	err error
}

func (p anyboxProtectPlatform) AutoDetectInterfaceControl(int32) error { return p.err }

func TestAnyBoxProtectErrorAbortsDial(t *testing.T) {
	previousBg, previousBox := isBgProcess, intfBox
	defer func() { isBgProcess, intfBox = previousBg, previousBox }()
	isBgProcess = true
	denied := errors.New("injected VPN protect false")
	for _, protectErr := range []error{denied, nil} {
		intfBox = anyboxProtectPlatform{err: protectErr}
		listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			t.Fatal(err)
		}
		platform := &boxPlatformInterfaceWrapper{}
		dialer := net.Dialer{Timeout: time.Second, Control: func(_, _ string, raw syscall.RawConn) error {
			var result error
			if err := raw.Control(func(fd uintptr) { result = platform.AutoDetectInterfaceControl(int(fd)) }); err != nil {
				return err
			}
			return result
		}}
		conn, err := dialer.DialContext(context.Background(), "tcp4", listener.Addr().String())
		if protectErr != nil {
			if conn != nil {
				conn.Close()
				listener.Close()
				t.Fatal("rejected socket connected")
			}
			if !errors.Is(err, denied) {
				listener.Close()
				t.Fatalf("protection error not propagated: %v", err)
			}
			listener.SetDeadline(time.Now().Add(20 * time.Millisecond))
			accepted, acceptErr := listener.Accept()
			listener.Close()
			if accepted != nil {
				accepted.Close()
				t.Fatal("rejected dial reached TCP listener")
			}
			if nerr, ok := acceptErr.(net.Error); !ok || !nerr.Timeout() {
				t.Fatalf("unexpected accept result: %v", acceptErr)
			}
		} else {
			if err != nil {
				listener.Close()
				t.Fatal(err)
			}
			listener.SetDeadline(time.Now().Add(time.Second))
			accepted, acceptErr := listener.Accept()
			conn.Close()
			listener.Close()
			if acceptErr != nil {
				t.Fatal(acceptErr)
			}
			accepted.Close()
		}
	}
}
