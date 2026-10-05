package outbound

import (
	"context"
	"errors"
	"maps"
	"reflect"
	"slices"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
)

type managerTestOutbound struct {
	adapter.Outbound
	tag          string
	dependencies []string
	closeErr     error
	closes       int
}

func (o *managerTestOutbound) Type() string           { return "test" }
func (o *managerTestOutbound) Tag() string            { return o.tag }
func (o *managerTestOutbound) Dependencies() []string { return o.dependencies }
func (o *managerTestOutbound) Close() error           { o.closes++; return o.closeErr }

type managerTestRegistry struct{ adapter.OutboundRegistry }

func (managerTestRegistry) CreateOutbound(_ context.Context, _ adapter.Router, _ log.ContextLogger, _ string, _ string, options any) (adapter.Outbound, error) {
	return options.(*managerTestOutbound), nil
}
func newTestManager(t *testing.T, defaultTag string, outbounds ...*managerTestOutbound) *Manager {
	t.Helper()
	m := NewManager(log.NewNOPFactory().Logger(), managerTestRegistry{}, nil, defaultTag)
	for _, o := range outbounds {
		createTestOutbound(t, m, o)
	}
	return m
}
func createTestOutbound(t *testing.T, m *Manager, o *managerTestOutbound) {
	t.Helper()
	if err := m.Create(context.Background(), nil, m.logger, o.tag, "test", o); err != nil {
		t.Fatal(err)
	}
}
func TestMutationFailurePreservesState(t *testing.T) {
	for _, reason := range []string{"dependency", "close", "replacement-close"} {
		t.Run(reason, func(t *testing.T) {
			a := &managerTestOutbound{tag: "A", dependencies: []string{"C"}}
			b := &managerTestOutbound{tag: "B"}
			if reason == "dependency" {
				b.dependencies = []string{"A"}
			} else {
				a.closeErr = errors.New("close failed")
			}
			m := newTestManager(t, "A", a, b, &managerTestOutbound{tag: "C"})
			m.started = true
			beforeList, beforeTags, beforeDefault := slices.Clone(m.outbounds), maps.Clone(m.outboundByTag), m.Default()
			beforeDependencies := make(map[string][]string)
			for tag, dependencies := range m.dependByTag {
				beforeDependencies[tag] = slices.Clone(dependencies)
			}
			var err error
			if reason == "replacement-close" {
				err = m.Create(context.Background(), nil, m.logger, "A", "test", &managerTestOutbound{tag: "A", dependencies: []string{"B"}})
			} else {
				err = m.Remove("A")
			}
			if err == nil {
				t.Fatal("mutation must fail")
			}
			if !reflect.DeepEqual(m.outbounds, beforeList) || !reflect.DeepEqual(m.outboundByTag, beforeTags) || m.Default() != beforeDefault || !reflect.DeepEqual(m.dependByTag, beforeDependencies) {
				t.Fatalf("failed mutation changed state: list=%v tags=%v default=%v dependencies=%v", m.outbounds, m.outboundByTag, m.Default(), m.dependByTag)
			}
			if reason == "dependency" && a.closes != 0 {
				t.Fatal("dependency rejection closed outbound")
			}
		})
	}
}
func TestReplacementUpdatesDependenciesAndDefault(t *testing.T) {
	for _, defaultTag := range []string{"", "B"} {
		t.Run("default="+defaultTag, func(t *testing.T) {
			m := newTestManager(t, defaultTag, &managerTestOutbound{tag: "B", dependencies: []string{"A", "C"}}, &managerTestOutbound{tag: "A"}, &managerTestOutbound{tag: "C"}, &managerTestOutbound{tag: "D", dependencies: []string{"C"}})
			replacement := &managerTestOutbound{tag: "B", dependencies: []string{"C"}}
			createTestOutbound(t, m, replacement)
			createTestOutbound(t, m, replacement)
			if m.Default() != replacement {
				t.Error("default retains replaced outbound")
			}
			if _, found := m.dependByTag["A"]; found {
				t.Error("stale dependency A")
			}
			if !reflect.DeepEqual(m.dependByTag["C"], []string{"D", "B"}) {
				t.Errorf("replacement lost sibling or duplicated dependency: %v", m.dependByTag)
			}
			if err := m.Remove("A"); err != nil {
				t.Errorf("old dependency still blocked: %v", err)
			}
			if err := m.Remove("C"); err == nil {
				t.Error("active dependency was removable")
			}
			if err := m.Remove("B"); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(m.dependByTag["C"], []string{"D"}) {
				t.Errorf("removal corrupted sibling dependency: %v", m.dependByTag)
			}
		})
	}
}
