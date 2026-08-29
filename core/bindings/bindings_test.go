package bindings

import (
	"net/http"
	"net/http/httptest"
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

	hashes, err := ComputeFileHashes(testFile)
	if err != nil {
		t.Fatalf("ComputeFileHashes failed: %v", err)
	}
	if hashes.FastHash == "" || len(hashes.FullHash) != 64 || hashes.SizeBytes == 0 {
		t.Fatalf("invalid hashes: fast=%s, full=%s, size=%d", hashes.FastHash, hashes.FullHash, hashes.SizeBytes)
	}

	// Test Enqueue
	id, err := EnqueueMedia(testFile, "test.raw", 1724000000, "local_uri_bind", "")
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

	offloaded := IsMediaOffloaded("local_uri_bind")
	if !offloaded {
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

	if _, err := EnqueueMedia("/tmp/fake", "fake.jpg", 1724000000, "local_1", ""); err == nil {
		t.Fatal("expected error on uninitialized EnqueueMedia")
	}

	if _, err := EnqueueLineageEvent("p", "c", "DERIVED_FROM", "test", 1.0); err == nil {
		t.Fatal("expected error on uninitialized EnqueueLineageEvent")
	}

	if _, err := EnqueueDeleteEvent("node"); err == nil {
		t.Fatal("expected error on uninitialized EnqueueDeleteEvent")
	}

	if _, err := SyncBatch(5, 5); err == nil {
		t.Fatal("expected error on uninitialized SyncBatch")
	}

	if offloaded := IsMediaOffloaded("local_1"); offloaded {
		t.Fatal("expected offloaded false on uninitialized IsMediaOffloaded")
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
	res, err := SyncBatch(2, 5)
	if err != nil {
		t.Fatalf("SyncBatch on empty queue failed: %v", err)
	}
	if res.Uploaded != 0 || res.EventsSent != 0 {
		t.Fatalf("expected 0 uploads and 0 events, got %d, %d", res.Uploaded, res.EventsSent)
	}
}

func TestFetchNamingTemplate(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/handshake" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"ok": true,
			"serverVersion": "0.12.0",
			"namingTemplate": "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
		}`))
	}))
	defer server.Close()

	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "bindings_template.db")
	if err := InitCore(dbPath, server.URL, "test-key", "agent-test", "0.1.0"); err != nil {
		t.Fatalf("InitCore failed: %v", err)
	}

	tmpl, err := FetchNamingTemplate()
	if err != nil {
		t.Fatalf("FetchNamingTemplate failed: %v", err)
	}
	if tmpl != "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}" {
		t.Errorf("got template %q, want '{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}'", tmpl)
	}
}

func TestFetchNamingTemplate_Uninitialized(t *testing.T) {
	engineMu.Lock()
	savedClient := globalClient
	globalClient = nil
	engineMu.Unlock()

	defer func() {
		engineMu.Lock()
		globalClient = savedClient
		engineMu.Unlock()
	}()

	if _, err := FetchNamingTemplate(); err == nil {
		t.Fatal("expected error on uninitialized FetchNamingTemplate")
	}
}
