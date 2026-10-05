package libcore

import "sync"

// NetworkPlatformInterface adds network lifecycle callbacks without changing the
// legacy BoxPlatformInterface. Each returned token belongs to one box monitor.
type NetworkPlatformInterface interface {
	StartDefaultInterfaceMonitor(listener InterfaceUpdateListener) (int64, error)
	CloseDefaultInterfaceMonitor(token int64) error
	GetInterfaces() (NetworkInterfaceIterator, error)
}

var networkPlatformAccess sync.RWMutex
var networkPlatform NetworkPlatformInterface

// SetNetworkPlatformInterface is called once after InitCore in each app process.
// Existing boxes retain the platform captured during Initialize.
func SetNetworkPlatformInterface(platform NetworkPlatformInterface) {
	networkPlatformAccess.Lock()
	networkPlatform = platform
	networkPlatformAccess.Unlock()
}

func currentNetworkPlatform() NetworkPlatformInterface {
	networkPlatformAccess.RLock()
	defer networkPlatformAccess.RUnlock()
	return networkPlatform
}
