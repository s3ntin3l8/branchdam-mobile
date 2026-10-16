package branchdam

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// Made by branchDAM's pairing.SealForDevice (the server's reference
// implementation): rotNewKey sealed for rotCurrentKey.
const (
	rotCurrentKey = "bdk_fixture_current_key_0123456789abcdefABCDEF"                                                          // pragma: allowlist secret
	rotNewKey     = "bdk_fixture_rotated_key_fedcba9876543210ZYXWVU"                                                          // pragma: allowlist secret
	rotSealed     = "v1:1R76zdX0mH2rB3zFFC9SAiRhyuH7DwE4+VqJ46XgxIjomz54uhhxYHa2VBracHGtMtqe/1MsI4+aPq3pMvroAg1Fmsn+57aUREo=" // pragma: allowlist secret
)

// fakeShellStore is a KeyStore like the shells': it records what it was
// asked to store and can be told to fail.
type fakeShellStore struct {
	mu     sync.Mutex
	stored []string
	fail   error
}

func (s *fakeShellStore) StoreAPIKey(apiKey string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.fail != nil {
		return s.fail
	}
	s.stored = append(s.stored, apiKey)
	return nil
}

// rotatingServer offers rotNewKey (sealed) to rotCurrentKey, accepts both,
// and records the key of every request in order.
func rotatingServer(t *testing.T) (string, func() []string) {
	t.Helper()
	var mu sync.Mutex
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		key := r.Header.Get("X-API-Key")
		mu.Lock()
		seen = append(seen, r.URL.Path+" "+map[string]string{rotCurrentKey: "old", rotNewKey: "new"}[key])
		mu.Unlock()
		out := map[string]any{"ok": true, "serverVersion": "test", "namingTemplate": "{original_name}"}
		switch key {
		case rotCurrentKey:
			out["currentKeyId"] = 1
			out["pendingRotation"] = map[string]any{"keyId": 2, "apiKey": "", "sealedApiKey": rotSealed, "previousKeyExpiresAtUnix": 1900000000}
		case rotNewKey:
			out["currentKeyId"] = 2
		default:
			http.Error(w, "invalid or missing X-API-Key", http.StatusUnauthorized)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
	}))
	t.Cleanup(srv.Close)
	return srv.URL, func() []string {
		mu.Lock()
		defer mu.Unlock()
		return append([]string(nil), seen...)
	}
}

func openRotationEngine(t *testing.T, baseURL string, store KeyStore) {
	t.Helper()
	BindingSetKeyStore(store)
	t.Cleanup(func() { BindingSetKeyStore(nil) })
	if err := BindingOpen(filepath.Join(t.TempDir(), "engine.db"), baseURL, rotCurrentKey, "agent-1", "test", "127.0.0.1"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	t.Cleanup(func() { _ = BindingClose() })
}

func TestBindingKeyStoreReceivesRotatedKey(t *testing.T) {
	url, seen := rotatingServer(t)
	store := &fakeShellStore{}
	openRotationEngine(t, url, store)

	tpl, err := BindingFetchNamingTemplate()
	if err != nil {
		t.Fatalf("BindingFetchNamingTemplate: %v", err)
	}
	if tpl != "{original_name}" {
		t.Fatalf("template = %q", tpl)
	}
	if len(store.stored) != 1 || store.stored[0] != rotNewKey {
		t.Fatalf("shell store got %d keys, want exactly the rotated one", len(store.stored))
	}
	want := []string{"/api/v1/agent/handshake old", "/api/v1/agent/handshake new"}
	if got := seen(); len(got) != 2 || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("requests = %v, want %v", got, want)
	}
	_, _ = BindingCheckContent("", "") // only the key it is sent with matters
	if got := seen(); got[len(got)-1] != "/api/v1/agent/check-content new" {
		t.Fatalf("later request = %q, want it on the new key", got[len(got)-1])
	}
}

func TestBindingKeyStoreFailureKeepsOldKey(t *testing.T) {
	for name, store := range map[string]KeyStore{
		"store fails": &fakeShellStore{fail: errors.New("keystore unavailable")},
		"no store":    nil,
	} {
		t.Run(name, func(t *testing.T) {
			url, seen := rotatingServer(t)
			openRotationEngine(t, url, store)
			if _, err := BindingFetchNamingTemplate(); err != nil {
				t.Fatalf("BindingFetchNamingTemplate: %v", err)
			}
			for _, r := range seen() {
				if r == "/api/v1/agent/handshake new" {
					t.Fatal("rotated key used without being stored")
				}
			}
		})
	}
}

// TestSyncBatchPicksUpRotation: a device that only syncs in the background
// still adopts a rotated key, because SyncBatch handshakes when none
// succeeded recently.
func TestSyncBatchPicksUpRotation(t *testing.T) {
	url, seen := rotatingServer(t)
	store := &fakeShellStore{}
	openRotationEngine(t, url, store)

	if _, err := BindingSyncBatch(5, 1); err != nil {
		t.Fatalf("BindingSyncBatch: %v", err)
	}
	if len(store.stored) != 1 || store.stored[0] != rotNewKey {
		t.Fatalf("shell store got %d keys, want the rotated one", len(store.stored))
	}
	n := len(seen())
	if _, err := BindingSyncBatch(5, 1); err != nil {
		t.Fatalf("second BindingSyncBatch: %v", err)
	}
	if got := seen(); len(got) != n {
		t.Fatalf("second batch handshook again within the check interval: %v", got[n:])
	}
}

// TestSyncBatchRotationCheckWithinBatchTimeout: the pre-sync handshake
// spends the batch's own TimeoutSecs, not a separate 10 s on top of it.
func TestSyncBatchRotationCheckWithinBatchTimeout(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select { // a handshake that never answers
		case <-r.Context().Done():
		case <-release:
		}
	}))
	t.Cleanup(srv.Close)
	t.Cleanup(func() { close(release) })
	openRotationEngine(t, srv.URL, &fakeShellStore{})

	start := time.Now()
	_, _ = BindingSyncBatch(1, 1)
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Fatalf("SyncBatch(TimeoutSecs=1) took %v, want the rotation check bounded by the batch timeout", elapsed)
	}
}
