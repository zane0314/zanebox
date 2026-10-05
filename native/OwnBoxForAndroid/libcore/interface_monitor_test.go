package libcore

import (
	"context"
	"errors"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/route"
	"github.com/sagernet/sing-box/route/rule"
	"github.com/sagernet/sing/common/json/badjson"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
	"net"
	"net/netip"
	"runtime"
	"sync"
	"testing"

	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
)

type fixtureNetworkPlatform struct {
	sync.Mutex
	next        int64
	listeners   map[int64]InterfaceUpdateListener
	closed      int
	fail        bool
	networkType int32
	metered     bool
	address     string
}

func (p *fixtureNetworkPlatform) StartDefaultInterfaceMonitor(listener InterfaceUpdateListener) (int64, error) {
	p.Lock()
	if p.fail {
		p.Unlock()
		return 0, errors.New("fixture registration failed")
	}
	if p.listeners == nil {
		p.listeners = make(map[int64]InterfaceUpdateListener)
	}
	p.next++
	p.listeners[p.next] = listener
	token := p.next
	p.Unlock()
	listener.UpdateDefaultInterface("fixture-wifi", 2147483000)
	return token, nil
}
func (p *fixtureNetworkPlatform) CloseDefaultInterfaceMonitor(id int64) error {
	p.Lock()
	defer p.Unlock()
	delete(p.listeners, id)
	p.closed++
	return nil
}
func (p *fixtureNetworkPlatform) GetInterfaces() (NetworkInterfaceIterator, error) {
	p.Lock()
	defer p.Unlock()
	if p.address == "" {
		return nil, nil
	}
	return &fixtureInterfaces{values: []*NetworkInterface{{Name: "fixture-wifi", Index: 2147483000, MTU: 1500, Flags: int32(net.FlagUp | net.FlagRunning), Type: p.networkType, Metered: p.metered, Addresses: &fixtureStrings{values: []string{p.address}}}}}, nil
}
func (p *fixtureNetworkPlatform) emit(name string, index int32) {
	p.Lock()
	var listeners []InterfaceUpdateListener
	for _, l := range p.listeners {
		listeners = append(listeners, l)
	}
	p.Unlock()
	for _, l := range listeners {
		l.UpdateDefaultInterface(name, index)
	}
}
func newFixtureMonitor(p *fixtureNetworkPlatform) *interfaceMonitor {
	SetNetworkPlatformInterface(p)
	w := &boxPlatformInterfaceWrapper{}
	_ = w.Initialize(nil)
	return newInterfaceMonitor(w, logger.NOP())
}
func TestInterfaceMonitorInitialAndBoxLifecycle(t *testing.T) {
	defer SetNetworkPlatformInterface(nil)
	p := &fixtureNetworkPlatform{}
	main, temp := newFixtureMonitor(p), newFixtureMonitor(p)
	var events int
	main.RegisterCallback(func(i *control.Interface, _ int) { events++ })
	if err := main.Start(); err != nil {
		t.Fatal(err)
	}
	if got := main.DefaultInterface(); got == nil || got.Name != "fixture-wifi" {
		t.Fatal("initial platform snapshot missing")
	}
	if err := temp.Start(); err != nil {
		t.Fatal(err)
	}
	p.emit("fixture-cell", 2147483001)
	if main.DefaultInterface().Name != "fixture-cell" || temp.DefaultInterface().Name != "fixture-cell" {
		t.Fatal("switch not delivered to both boxes")
	}
	p.emit("", -1)
	if main.DefaultInterface() != nil || temp.DefaultInterface() != nil {
		t.Fatal("lost network retained")
	}
	p.emit("fixture-wifi", 2147483000)
	if err := temp.Close(); err != nil {
		t.Fatal(err)
	}
	before := events
	p.emit("fixture-cell", 2147483001)
	if events != before+1 || main.DefaultInterface().Name != "fixture-cell" {
		t.Fatal("temporary close detached main box")
	}
	temp.UpdateDefaultInterface("late", 2147483002)
	if temp.DefaultInterface() != nil {
		t.Fatal("closed monitor accepted late callback")
	}
	_ = temp.Close()
	_ = main.Close()
	if len(p.listeners) != 0 || p.closed != 2 {
		t.Fatalf("listener leak/duplicate close: %d/%d", len(p.listeners), p.closed)
	}
}
func TestInterfaceMonitorRegistrationFailure(t *testing.T) {
	defer SetNetworkPlatformInterface(nil)
	p := &fixtureNetworkPlatform{fail: true}
	m := newFixtureMonitor(p)
	if err := m.Start(); err == nil {
		t.Fatal("registration failure ignored")
	}
	_ = m.Close()
	if p.closed != 0 {
		t.Fatal("closed nonexistent subscription")
	}
}

type fixtureInterfaces struct {
	values []*NetworkInterface
	index  int
}

func (i *fixtureInterfaces) Length() int32           { return int32(len(i.values)) }
func (i *fixtureInterfaces) HasNext() bool           { return i.index < len(i.values) }
func (i *fixtureInterfaces) Next() *NetworkInterface { v := i.values[i.index]; i.index++; return v }

type fixtureStrings struct {
	values []string
	index  int
}

func (i *fixtureStrings) Length() int32 { return int32(len(i.values)) }
func (i *fixtureStrings) HasNext() bool { return i.index < len(i.values) }
func (i *fixtureStrings) Next() string  { v := i.values[i.index]; i.index++; return v }

func TestInterfaceMonitorNetworkConditions(t *testing.T) {
	defer SetNetworkPlatformInterface(nil)
	p := &fixtureNetworkPlatform{networkType: InterfaceTypeWIFI, address: "192.0.2.10/24"}
	SetNetworkPlatformInterface(p)
	ctx := pause.WithDefaultManager(service.ContextWithDefaultRegistry(context.Background()))
	w := &boxPlatformInterfaceWrapper{}
	service.MustRegister[adapter.PlatformInterface](ctx, w)
	nm, err := route.NewNetworkManager(ctx, logger.NOP(), option.RouteOptions{}, option.DNSOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if err = w.Initialize(nm); err != nil {
		t.Fatal(err)
	}
	if err = nm.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	defer nm.Close()
	wifi := rule.NewNetworkTypeItem(nm, []C.InterfaceType{C.InterfaceTypeWIFI})
	cell := rule.NewNetworkTypeItem(nm, []C.InterfaceType{C.InterfaceTypeCellular})
	expensive := rule.NewNetworkIsExpensiveItem(nm)
	constrained := rule.NewNetworkIsConstrainedItem(nm)
	prefix := badoption.Prefixable(netip.MustParsePrefix("192.0.2.0/24"))
	prefixes := badoption.Listable[*badoption.Prefixable]{&prefix}
	address := rule.NewDefaultInterfaceAddressItem(nm, prefixes)
	types := new(badjson.TypedMap[option.InterfaceType, badoption.Listable[*badoption.Prefixable]])
	types.Put(option.InterfaceType(C.InterfaceTypeWIFI), prefixes)
	byType := rule.NewNetworkInterfaceAddressItem(nm, types)
	names := new(badjson.TypedMap[string, badoption.Listable[*badoption.Prefixable]])
	names.Put("fixture-wifi", prefixes)
	byName := rule.NewInterfaceAddressItem(nm, names)
	if !wifi.Match(nil) || cell.Match(nil) || expensive.Match(nil) || constrained.Match(nil) || !address.Match(nil) || !byType.Match(nil) || (runtime.GOOS != "darwin" && !byName.Match(nil)) {
		t.Fatalf("initial platform network rules mismatch wifi=%v cell=%v expense=%v constrained=%v defaultAddress=%v typeAddress=%v nameAddress=%v default=%+v list=%+v", wifi.Match(nil), cell.Match(nil), expensive.Match(nil), constrained.Match(nil), address.Match(nil), byType.Match(nil), byName.Match(nil), nm.DefaultNetworkInterface(), nm.NetworkInterfaces())
	}
	if runtime.GOOS == "darwin" {
		// Darwin's actual NetworkManager keeps the OS finder; Android instead
		// publishes the platform addresses. Exercise that exact finder input.
		var interfaces []control.Interface
		for _, item := range nm.NetworkInterfaces() {
			interfaces = append(interfaces, item.Interface)
		}
		nm.InterfaceFinder().(*control.DefaultInterfaceFinder).UpdateInterfaces(interfaces)
		if !byName.Match(nil) {
			t.Fatal("Android platform finder input did not match interface address")
		}
	}
	p.Lock()
	p.networkType = InterfaceTypeCellular
	p.metered = true
	p.address = "2001:db8::2/64"
	p.Unlock()
	p.emit("fixture-wifi", 2147483000) // Same interface: capabilities and addresses must still refresh.
	if wifi.Match(nil) || !cell.Match(nil) || !expensive.Match(nil) || address.Match(nil) || byType.Match(nil) || byName.Match(nil) {
		t.Fatal("same-interface metadata/address refresh missing")
	}
	p.emit("", -1)
	if cell.Match(nil) || expensive.Match(nil) || address.Match(nil) {
		t.Fatal("lost network still matches default conditions")
	}
	p.emit("fixture-wifi", 2147483000)
	if !cell.Match(nil) || !expensive.Match(nil) {
		t.Fatal("restored network did not match")
	}
}

func TestInterfaceMonitorConcurrentCloseAndUpdate(t *testing.T) {
	defer SetNetworkPlatformInterface(nil)
	p := &fixtureNetworkPlatform{}
	m := newFixtureMonitor(p)
	if err := m.Start(); err != nil {
		t.Fatal(err)
	}
	var workers sync.WaitGroup
	for i := 0; i < 8; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for n := 0; n < 25; n++ {
				p.emit("fixture-wifi", 2147483000)
				_ = m.DefaultInterface()
			}
		}()
	}
	if err := m.Close(); err != nil {
		t.Fatal(err)
	}
	workers.Wait()
	if m.DefaultInterface() != nil {
		t.Fatal("late update restored closed monitor")
	}
}
