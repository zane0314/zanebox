//go:build linux

package fdbased

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/link/stopfd"
	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	"golang.org/x/sys/unix"
)

type discardDispatcher struct{}

func (discardDispatcher) DeliverNetworkPacket(tcpip.NetworkProtocolNumber, *stack.PacketBuffer) {}
func (discardDispatcher) DeliverLinkPacket(tcpip.NetworkProtocolNumber, *stack.PacketBuffer)    {}

func eventFDs(t *testing.T) int {
	t.Helper()
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Fatal(err)
	}
	count := 0
	for _, entry := range entries {
		target, _ := os.Readlink(filepath.Join("/proc/self/fd", entry.Name()))
		if target == "anon_inode:[eventfd]" {
			count++
		}
	}
	return count
}

func socketPair(t *testing.T) [2]int {
	t.Helper()
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_SEQPACKET|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { unix.Close(fds[0]); unix.Close(fds[1]) })
	return fds
}

func checkEventFDs(t *testing.T, want int) {
	t.Helper()
	if got := eventFDs(t); got != want {
		t.Fatalf("eventfds = %d, want %d", got, want)
	}
}

func TestEndpointLifetime(t *testing.T) {
	for _, mode := range []PacketDispatchMode{Readv, RecvMMsg} {
		t.Run(mode.String(), func(t *testing.T) {
			for cycle := 0; cycle < 20; cycle++ {
				fds := socketPair(t)
				baseline := eventFDs(t)
				ep, err := New(&Options{FDs: []int{fds[0]}, MTU: 1500, ProcessorsPerChannel: 1, PacketDispatchMode: mode})
				if err != nil {
					t.Fatal(err)
				}
				checkEventFDs(t, baseline+1)
				if cycle%2 == 0 {
					ep.Attach(discardDispatcher{})
				}
				var wg sync.WaitGroup
				for i := 0; i < 4; i++ {
					wg.Go(ep.Close)
				}
				wg.Wait()
				ep.Attach(nil)
				ep.Attach(discardDispatcher{})
				if ep.IsAttached() {
					t.Fatal("terminal endpoint attached again")
				}
				checkEventFDs(t, baseline)
				if _, err := unix.FcntlInt(uintptr(fds[0]), unix.F_GETFD, 0); err != nil {
					t.Fatalf("caller-owned fd closed: %v", err)
				}
			}
		})
	}
}

func TestEndpointNaturalExitThenDetach(t *testing.T) {
	fds := socketPair(t)
	baseline := eventFDs(t)
	ep, err := New(&Options{FDs: []int{fds[0]}, MTU: 1500, ProcessorsPerChannel: 1})
	if err != nil {
		t.Fatal(err)
	}
	ep.Attach(discardDispatcher{})
	if err := unix.Shutdown(fds[1], unix.SHUT_WR); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { ep.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("read loop did not exit on EOF")
	}
	checkEventFDs(t, baseline)
	ep.Attach(nil)
	ep.Close()
	checkEventFDs(t, baseline)
}

func TestConstructorRollback(t *testing.T) {
	fds := socketPair(t)
	for _, opts := range []*Options{
		{FDs: []int{fds[0], -1}},
		{FDs: []int{fds[0]}, PacketDispatchMode: PacketDispatchMode(100)},
		{FDs: []int{fds[0]}, PacketDispatchMode: PacketMMap},
		{FDs: []int{fds[0], fds[1]}, PreConfigured: true, IsPacketSocket: []bool{false}},
	} {
		opts.ProcessorsPerChannel = 1
		baseline := eventFDs(t)
		if ep, err := New(opts); err == nil {
			ep.Close()
			t.Fatal("expected construction failure")
		}
		checkEventFDs(t, baseline)
	}
}

func TestStopFDCloseAndReuse(t *testing.T) {
	baseline := eventFDs(t)
	for cycle := 0; cycle < 100; cycle++ {
		sf, err := stopfd.New()
		if err != nil {
			t.Fatal(err)
		}
		original := sf.EFD
		var wg sync.WaitGroup
		for i := 0; i < 4; i++ {
			wg.Go(sf.Stop)
			wg.Go(func() {
				if err := sf.Close(); err != nil {
					t.Error(err)
				}
			})
		}
		wg.Wait()
		replacement, err := unix.Eventfd(0, unix.EFD_NONBLOCK)
		if err != nil {
			t.Fatal(err)
		}
		if replacement != original {
			if err := unix.Dup3(replacement, original, unix.O_CLOEXEC); err != nil {
				t.Fatal(err)
			}
			unix.Close(replacement)
		}
		sf.Stop()
		if err := sf.Close(); err != nil {
			t.Fatal(err)
		}
		var value [8]byte
		if _, err := unix.Read(original, value[:]); err != unix.EAGAIN {
			t.Fatalf("reused fd changed by Stop/Close: %v", err)
		}
		unix.Close(original)
	}
	checkEventFDs(t, baseline)
}
