package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"
)

type requestFunc func(context.Context) (*http.Response, error)
type httpCleanupBody struct {
	io.ReadCloser
	once    sync.Once
	cleanup func()
}

func (body *httpCleanupBody) Close() error {
	err := body.ReadCloser.Close()
	body.once.Do(body.cleanup)
	return err
}

// A response owns its deadline and transport until its body is closed.
func raceHTTPRequests(parent context.Context, requests []requestFunc, timeout time.Duration) (*http.Response, error) {
	ctx, cancel := context.WithTimeout(parent, timeout)
	finished := make(chan struct{})
	type outcome struct {
		response *http.Response
		err      error
		index    int
	}
	results := make(chan outcome)
	cancels := make([]context.CancelFunc, len(requests))
	for index, request := range requests {
		workerContext, workerCancel := context.WithCancel(ctx)
		cancels[index] = workerCancel
		go func(index int, request requestFunc) {
			response, err := request(workerContext)
			if err == nil && response == nil {
				err = errors.New("empty HTTP response")
			}
			if err == nil && response.StatusCode >= 400 {
				err = fmt.Errorf("HTTP status %d", response.StatusCode)
			}
			if err != nil && response != nil {
				response.Body.Close()
				response = nil
			}
			select {
			case results <- outcome{response, err, index}:
			case <-finished:
				if response != nil {
					response.Body.Close()
				}
			}
		}(index, request)
	}
	var failures error
	for range requests {
		select {
		case result := <-results:
			if result.err == nil {
				close(finished)
				for index, stop := range cancels {
					if index != result.index {
						stop()
					}
				}
				result.response.Body = &httpCleanupBody{ReadCloser: result.response.Body, cleanup: cancel}
				return result.response, nil
			}
			failures = errors.Join(failures, result.err)
		case <-ctx.Done():
			close(finished)
			cancel()
			return nil, ctx.Err()
		}
	}
	close(finished)
	cancel()
	if failures == nil {
		failures = errors.New("no HTTP transports available")
	}
	return nil, failures
}
