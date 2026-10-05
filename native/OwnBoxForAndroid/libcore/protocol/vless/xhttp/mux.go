package xhttp

import (
	"context"
	"crypto/rand"
	"math"
	"math/big"
	"slices"
	"sync"
	"sync/atomic"
	"time"
)

type XmuxConn interface {
	IsClosed() bool
}

type XmuxClient struct {
	XmuxConn     XmuxConn
	OpenUsage    atomic.Int32
	leftUsage    int32
	LeftRequests atomic.Int32
	UnreusableAt time.Time
	manager      *XmuxManager
	retired      atomic.Bool
	closeOnce    sync.Once
	closeErr     error
}

type XmuxManager struct {
	options     V2RayXHTTPXmuxOptions
	concurrency int32
	connections int32
	newConnFunc func() XmuxConn
	xmuxClients []*XmuxClient
	retired     []*XmuxClient
	mtx         sync.Mutex
}

func NewXmuxManager(options V2RayXHTTPXmuxOptions, newConnFunc func() XmuxConn) *XmuxManager {
	return &XmuxManager{
		options:     options,
		concurrency: options.GetNormalizedMaxConcurrency().Rand(),
		connections: options.GetNormalizedMaxConnections().Rand(),
		newConnFunc: newConnFunc,
		xmuxClients: make([]*XmuxClient, 0),
	}
}

func (m *XmuxManager) newXmuxClient() *XmuxClient {
	xmuxClient := &XmuxClient{
		XmuxConn:  m.newConnFunc(),
		leftUsage: -1,
		manager:   m,
	}
	if x := m.options.GetNormalizedCMaxReuseTimes().Rand(); x > 0 {
		xmuxClient.leftUsage = x - 1
	}
	xmuxClient.LeftRequests.Store(math.MaxInt32)
	if x := m.options.GetNormalizedHMaxRequestTimes().Rand(); x > 0 {
		xmuxClient.LeftRequests.Store(x)
	}
	if x := m.options.GetNormalizedHMaxReusableSecs().Rand(); x > 0 {
		xmuxClient.UnreusableAt = time.Now().Add(time.Duration(x) * time.Second)
	}
	m.xmuxClients = append(m.xmuxClients, xmuxClient)
	return xmuxClient
}

func (c *XmuxClient) Release() {
	if c == nil {
		return
	}
	if c.OpenUsage.Add(-1) == 0 && c.retired.Load() {
		c.manager.onXmuxClientIdle(c)
	}
}

func (c *XmuxClient) retire() {
	c.retired.Store(true)
	if c.OpenUsage.Load() == 0 {
		if c.manager != nil {
			c.manager.onXmuxClientIdle(c)
		} else {
			_ = c.Close()
		}
	}
}

func (c *XmuxClient) Close() error {
	c.closeOnce.Do(func() {
		if closer, ok := c.XmuxConn.(interface{ Close() error }); ok {
			c.closeErr = closer.Close()
		}
	})
	return c.closeErr
}

func (m *XmuxManager) onXmuxClientIdle(client *XmuxClient) {
	m.mtx.Lock()
	for i, retired := range m.retired {
		if retired == client {
			m.retired = slices.Delete(m.retired, i, i+1)
			break
		}
	}
	m.mtx.Unlock()
	_ = client.Close()
}

func (m *XmuxManager) getXmuxClient(ctx context.Context, acquire bool) *XmuxClient {
	m.mtx.Lock()
	var retired []*XmuxClient
	for i := 0; i < len(m.xmuxClients); {
		xmuxClient := m.xmuxClients[i]
		if xmuxClient.XmuxConn.IsClosed() ||
			xmuxClient.leftUsage == 0 ||
			xmuxClient.LeftRequests.Load() <= 0 ||
			(xmuxClient.UnreusableAt != time.Time{} && time.Now().After(xmuxClient.UnreusableAt)) {
			m.xmuxClients = slices.Delete(m.xmuxClients, i, i+1)
			m.retired = append(m.retired, xmuxClient)
			retired = append(retired, xmuxClient)
		} else {
			i++
		}
	}
	var result *XmuxClient
	if len(m.xmuxClients) == 0 {
		result = m.newXmuxClient()
	} else if m.connections > 0 && len(m.xmuxClients) < int(m.connections) {
		result = m.newXmuxClient()
	} else {
		xmuxClients := make([]*XmuxClient, 0)
		if m.concurrency > 0 {
			for _, xmuxClient := range m.xmuxClients {
				if xmuxClient.OpenUsage.Load() < m.concurrency {
					xmuxClients = append(xmuxClients, xmuxClient)
				}
			}
		} else {
			xmuxClients = m.xmuxClients
		}
		if len(xmuxClients) == 0 {
			result = m.newXmuxClient()
		} else {
			i, _ := rand.Int(rand.Reader, big.NewInt(int64(len(xmuxClients))))
			result = xmuxClients[i.Int64()]
			if result.leftUsage > 0 {
				result.leftUsage -= 1
			}
		}
	}
	if acquire {
		result.OpenUsage.Add(1)
	}
	m.mtx.Unlock()
	for _, xmuxClient := range retired {
		xmuxClient.retire()
	}
	return result
}

func (m *XmuxManager) GetXmuxClient(ctx context.Context) *XmuxClient {
	return m.getXmuxClient(ctx, false)
}

func (m *XmuxManager) AcquireXmuxClient(ctx context.Context) *XmuxClient {
	return m.getXmuxClient(ctx, true)
}

// Close releases all current and previously retired transports. The manager
// remains usable; a later GetXmuxClient creates a fresh transport.
func (m *XmuxManager) Close() error {
	m.mtx.Lock()
	clients := append(append([]*XmuxClient(nil), m.xmuxClients...), m.retired...)
	m.xmuxClients = nil
	m.retired = nil
	m.mtx.Unlock()
	var firstErr error
	for _, client := range clients {
		client.retired.Store(true)
		if err := client.Close(); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}
