package bindings

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBindingsLifecycle(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "bindings_test.db")

	if err := InitCore(dbPath, "http://localhost:8080", "test-key", "agent-bind", "0.1.0"); err != nil {
		t.Fatalf("InitCore failed: %v", err)
	}

	// Test hashing
	testFile := filepath.Join(tempDir, "test.raw")
	if err := os.WriteFile(testFile, []byte("raw file content"), 0644); err != nil {
		t.Fatalf("write file failed: %v", err)
	}

	fast, full, size, err := ComputeFileHashes(testFile)
	if err != nil {
		t.Fatalf("ComputeFileHashes failed: %v", err)
	}
	if fast == "" || len(full) != 64 || size == 0 {
		t.Fatalf("invalid hashes: fast=%s, full=%s, size=%d", fast, full, size)
	}

	// Test Enqueue
	id, err := EnqueueMedia(testFile, "test.raw", 1724000000, "local_uri_bind")
	if err != nil || id <= 0 {
		t.Fatalf("EnqueueMedia failed: %v", err)
	}

	// Test Lineage Event
	evtUUID, err := EnqueueLineageEvent("parent-uuid", "child-uuid", "DERIVED_FROM", "camera_pair", 1.00)
	if err != nil || evtUUID == "" {
		t.Fatalf("EnqueueLineageEvent failed: %v", err)
	}

	// Test Delete Event
	delUUID, err := EnqueueDeleteEvent("node-to-delete")
	if err != nil || delUUID == "" {
		t.Fatalf("EnqueueDeleteEvent failed: %v", err)
	}

	// Test Offload State
	if err := SetMediaOffloaded("local_uri_bind", true); err != nil {
		t.Fatalf("SetMediaOffloaded failed: %v", err)
	}

	offloaded, err := IsMediaOffloaded("local_uri_bind")
	if err != nil || !offloaded {
		t.Fatalf("expected offloaded true, got %v", offloaded)
	}
}

func TestBindingsUninitialized(t *testing.T) {
	// Temporarily reset global state
	engineMu.Lock()
	savedEngine := globalEngine
	savedQueue := globalQueue
	globalEngine = nil
	globalQueue = nil
	engineMu.Unlock()

	defer func() {
		engineMu.Lock()
		globalEngine = savedEngine
		globalQueue = savedQueue
		engineMu.Unlock()
	}()

	if _, err := EnqueueMedia("/tmp/fake", "fake.jpg", 1724000000, "local_1"); err == nil {
		t.Fatal("expected error on uninitialized EnqueueMedia")
	}

	if _, err := EnqueueLineageEvent("p", "c", "DERIVED_FROM", "test", 1.0); err == nil {
		t.Fatal("expected error on uninitialized EnqueueLineageEvent")
	}

	if _, err := EnqueueDeleteEvent("node"); err == nil {
		t.Fatal("expected error on uninitialized EnqueueDeleteEvent")
	}

	if _, _, err := SyncBatch(5, 5); err == nil {
		t.Fatal("expected error on uninitialized SyncBatch")
	}

	if _, err := IsMediaOffloaded("local_1"); err == nil {
		t.Fatal("expected error on uninitialized IsMediaOffloaded")
	}

	if err := SetMediaOffloaded("local_1", true); err == nil {
		t.Fatal("expected error on uninitialized SetMediaOffloaded")
	}
}

func TestBindingsSyncBatch(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "bindings_sync.db")

	if err := InitCore(dbPath, "http://localhost:9999", "key", "agent", "0.1.0"); err != nil {
		t.Fatalf("InitCore failed: %v", err)
	}

	// Sync on empty queue
	u, e, err := SyncBatch(2, 5)
	if err != nil {
		t.Fatalf("SyncBatch on empty queue failed: %v", err)
	}
	if u != 0 || e != 0 {
		t.Fatalf("expected 0 uploads and 0 events, got %d, %d", u, e)
	}
}
