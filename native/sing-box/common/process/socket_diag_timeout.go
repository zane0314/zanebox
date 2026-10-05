package process

import (
	"errors"
	"os"
	"syscall"
	"time"
)

// Engineering budget for the optional netlink fallback, not a measured device optimum.
const socketDiagTimeout = 50 * time.Millisecond

// SO_*TIMEO returns EAGAIN/EWOULDBLOCK on these blocking sockets, not ENOENT.
func socketDiagIOError(err error) error {
	if errors.Is(err, syscall.EAGAIN) || errors.Is(err, syscall.ETIMEDOUT) {
		return errors.Join(os.ErrDeadlineExceeded, err)
	}
	return err
}
