package libcore

import (
	"reflect"
	"strings"
	"testing"
)

func TestNewTestSingBoxInstanceDoesNotEnablePersistentServices(t *testing.T) {
	config := `{"outbounds":[{"type":"direct","tag":"direct"}]}`
	instance, err := NewTestSingBoxInstance(config, nil)
	if err != nil {
		t.Fatalf("NewTestSingBoxInstance() error = %v", err)
	}
	defer func() { _ = instance.Close() }()

	if !instance.isURLTest {
		t.Fatal("temporary URLTest config retained the main-instance logging mode")
	}

	services := reflect.ValueOf(instance.Box).Elem().FieldByName("internalService")
	for i := 0; i < services.Len(); i++ {
		service := services.Index(i)
		if service.IsNil() {
			continue
		}
		serviceType := strings.ToLower(service.Elem().Type().String())
		if strings.Contains(serviceType, "cachefile") || strings.Contains(serviceType, "clash") {
			t.Fatalf("temporary URLTest enabled persistent service %s", serviceType)
		}
	}
}

func TestNewSingBoxInstanceKeepsMainPersistentServices(t *testing.T) {
	config := `{"experimental":{"cache_file":{"enabled":true}},"outbounds":[{"type":"direct","tag":"direct"}]}`
	instance, err := NewSingBoxInstance(config, nil)
	if err != nil {
		t.Fatalf("NewSingBoxInstance() error = %v", err)
	}
	defer func() { _ = instance.Close() }()

	if instance.isURLTest {
		t.Fatal("main instance was classified as URLTest")
	}
	services := reflect.ValueOf(instance.Box).Elem().FieldByName("internalService")
	var hasCache, hasClash bool
	for i := 0; i < services.Len(); i++ {
		service := services.Index(i)
		if service.IsNil() {
			continue
		}
		serviceType := strings.ToLower(service.Elem().Type().String())
		hasCache = hasCache || strings.Contains(serviceType, "cachefile")
		hasClash = hasClash || strings.Contains(serviceType, "clash")
	}
	if !hasCache || !hasClash {
		t.Fatalf("main instance lost persistent services: cache=%v clash=%v", hasCache, hasClash)
	}
}
