package client

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

// Fixture made by branchDAM's own pairing.SealForDevice (the S13 reference
// implementation), not by this package: opening it proves this client
// matches the server's algorithm, not merely itself.
const (
	fixtureCurrentKey = "bdk_fixture_current_key_0123456789abcdefABCDEF"                                                          // pragma: allowlist secret
	fixtureRotatedKey = "bdk_fixture_rotated_key_fedcba9876543210ZYXWVU"                                                          // pragma: allowlist secret
	fixtureSealed     = "v1:1R76zdX0mH2rB3zFFC9SAiRhyuH7DwE4+VqJ46XgxIjomz54uhhxYHa2VBracHGtMtqe/1MsI4+aPq3pMvroAg1Fmsn+57aUREo=" // pragma: allowlist secret
)

func TestOpenRotatedKeyServerFixture(t *testing.T) {
	got, err := OpenRotatedKey(fixtureCurrentKey, fixtureSealed)
	if err != nil {
		t.Fatalf("OpenRotatedKey(server fixture): %v", err)
	}
	if got != fixtureRotatedKey {
		t.Fatalf("OpenRotatedKey = %q, want %q", got, fixtureRotatedKey)
	}
}

func TestOpenRotatedKeyRejects(t *testing.T) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(fixtureSealed, "v1:"))
	if err != nil {
		t.Fatal(err)
	}
	tampered := slices.Clone(raw)
	tampered[len(tampered)-1] ^= 0x01

	cases := map[string]struct{ key, sealed string }{
		"wrong current key": {"bdk_some_other_key_0123456789abcdefABCDEF", fixtureSealed}, // pragma: allowlist secret
		"empty current key": {"", fixtureSealed},
		"tampered tag":      {fixtureCurrentKey, "v1:" + base64.StdEncoding.EncodeToString(tampered)},
		"missing prefix":    {fixtureCurrentKey, strings.TrimPrefix(fixtureSealed, "v1:")},
		"other version":     {fixtureCurrentKey, "v2:" + strings.TrimPrefix(fixtureSealed, "v1:")},
		"bad base64":        {fixtureCurrentKey, "v1:not base64!"},
		"too short":         {fixtureCurrentKey, "v1:" + base64.StdEncoding.EncodeToString(raw[:20])},
		"empty":             {fixtureCurrentKey, ""},
		"not a header value": {
			fixtureCurrentKey, sealForTest(t, fixtureCurrentKey, "has a space"),
		},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			got, err := OpenRotatedKey(tc.key, tc.sealed)
			if !errors.Is(err, ErrSealedKeyInvalid) {
				t.Fatalf("OpenRotatedKey = (%q, %v), want ErrSealedKeyInvalid", got, err)
			}
			if got != "" {
				t.Fatalf("OpenRotatedKey returned %q alongside an error", got)
			}
		})
	}
}

func TestOpenRotatedKeyRoundTrip(t *testing.T) {
	for i := 0; i < 8; i++ {
		cur, next := randomKey(t), randomKey(t)
		got, err := OpenRotatedKey(cur, sealForTest(t, cur, next))
		if err != nil || got != next {
			t.Fatalf("round trip %d: got (%q, %v)", i, got, err)
		}
	}
}

// sealForTest is branchDAM's pairing.SealForDevice written out with the
// standard library: AES-256-GCM under HMAC-SHA256(currentKey,
// "branchdam-key-rotation-v1"), "v1:" + base64(nonce || ciphertext || tag).
func sealForTest(t *testing.T, currentKey, newKey string) string {
	t.Helper()
	mac := hmac.New(sha256.New, []byte(currentKey))
	mac.Write([]byte("branchdam-key-rotation-v1"))
	block, err := aes.NewCipher(mac.Sum(nil))
	if err != nil {
		t.Fatal(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		t.Fatal(err)
	}
	return "v1:" + base64.StdEncoding.EncodeToString(gcm.Seal(nonce, nonce, []byte(newKey), nil))
}

func randomKey(t *testing.T) string {
	t.Helper()
	b := make([]byte, 24)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return "bdk_" + hex.EncodeToString(b)
}

// rotationServer is a small model of branchDAM's S13 server: keys of one
// pairing, the newest one delivered sealed on a handshake with an older
// one, and adoption -- the first accepted request on the newest key
// revokes every older key.
type rotationServer struct {
	t *testing.T

	mu       sync.Mutex
	ids      map[string]int64 // active key -> key id
	names    map[string]string
	newest   string
	refuse   map[string]bool // keys answered 401 even though active
	undeliv  bool            // no BRANCHDAM_SECRET_KEY: sealedApiKey omitted
	sealWith string          // seal for this key instead of the presented one
	failNext map[string]int  // one-shot status override per key (non-auth failure)
	log      []string
}

func newRotationServer(t *testing.T, keys ...string) (*rotationServer, *httptest.Server) {
	t.Helper()
	rs := &rotationServer{t: t, ids: map[string]int64{}, names: map[string]string{}, refuse: map[string]bool{}, failNext: map[string]int{}}
	for i, k := range keys {
		rs.ids[k] = int64(i + 1)
		rs.names[k] = "k" + string(rune('1'+i))
		rs.newest = k
	}
	srv := httptest.NewServer(http.HandlerFunc(rs.serve))
	t.Cleanup(srv.Close)
	return rs, srv
}

func (rs *rotationServer) name(k string) string {
	if n, ok := rs.names[k]; ok {
		return n
	}
	return "unknown"
}

func (rs *rotationServer) record(entry string) {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	rs.log = append(rs.log, entry)
}

func (rs *rotationServer) entries() []string {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	return slices.Clone(rs.log)
}

func (rs *rotationServer) serve(w http.ResponseWriter, r *http.Request) {
	key := r.Header.Get("X-API-Key")
	body, _ := io.ReadAll(r.Body)

	rs.mu.Lock()
	defer rs.mu.Unlock()
	rs.log = append(rs.log, r.URL.Path+" "+rs.name(key))

	// The signature must be made with the same key the request presents
	// (uploads are not signed).
	if r.URL.Path != "/api/v1/agent/upload" {
		want := signRequest(key, r.Method, r.URL.RequestURI(), r.Header.Get("X-Nonce"), r.Header.Get("X-Timestamp"), body)
		if r.Header.Get("X-Signature") != want {
			rs.t.Errorf("%s: X-Signature not made with the presented key", r.URL.Path)
		}
	}

	if status, ok := rs.failNext[key]; ok {
		delete(rs.failNext, key)
		http.Error(w, "unavailable", status)
		return
	}
	id, active := rs.ids[key]
	if !active || rs.refuse[key] {
		http.Error(w, "invalid or missing X-API-Key", http.StatusUnauthorized)
		return
	}
	if key == rs.newest {
		for k := range rs.ids {
			if k != key {
				delete(rs.ids, k) // adoption revokes every older key
			}
		}
	}

	switch r.URL.Path {
	case handshakePath:
		out := map[string]any{"ok": true, "serverVersion": "test", "currentKeyId": id}
		if key != rs.newest {
			sealed := ""
			if !rs.undeliv {
				sealFor := key
				if rs.sealWith != "" {
					sealFor = rs.sealWith
				}
				sealed = sealForTest(rs.t, sealFor, rs.newest)
			}
			out["pendingRotation"] = map[string]any{
				"keyId": rs.ids[rs.newest], "apiKey": "", "sealedApiKey": sealed, "previousKeyExpiresAtUnix": 1900000000,
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
	case "/api/v1/agent/upload":
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"nodeUuid":"n"}`))
	default:
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"eventId":"e"}`))
	}
}

// recordingPersister is a KeyStore that logs into the server's log, so
// tests can check a key is stored before it is first sent.
func recordingPersister(rs *rotationServer, stored *[]string, fail error) KeyStore {
	return func(_ context.Context, apiKey string) error {
		rs.record("persist " + rs.name(apiKey))
		if fail != nil {
			return fail
		}
		*stored = append(*stored, apiKey)
		return nil
	}
}

func postSomething(t *testing.T, c *Client) error {
	t.Helper()
	_, err := c.SubmitEvent(context.Background(), "", "EVENT_NODE_DELETED", `{"nodeUuid":"0190f1a2-3b4c-7d5e-8f6a-1b2c3d4e5f60"}`)
	return err
}

// newRotationClient builds a Client on key with store installed (nil: none).
func newRotationClient(baseURL, key string, store KeyStore) *Client {
	c := New(Config{BaseURL: baseURL, APIKey: key, AgentID: "agent-1"})
	if store != nil {
		c.SetKeyStore(store)
	}
	return c
}

func handshake(t *testing.T, c *Client) *HandshakeResponse {
	t.Helper()
	hs, err := c.Handshake(context.Background(), "")
	if err != nil {
		t.Fatalf("Handshake: %v", err)
	}
	return hs
}

func TestHandshakeAdoptsRotatedKey(t *testing.T) {
	k1, k2 := randomKey(t), randomKey(t)
	rs, srv := newRotationServer(t, k1, k2)
	var stored []string
	c := newRotationClient(srv.URL, k1, recordingPersister(rs, &stored, nil))

	hs := handshake(t, c)
	if hs.CurrentKeyID != 2 || hs.PendingRotation != nil {
		t.Fatalf("returned handshake = currentKeyId %d, pendingRotation %v; want the confirming handshake on key 2", hs.CurrentKeyID, hs.PendingRotation)
	}
	if !slices.Equal(stored, []string{k2}) {
		t.Fatalf("persisted %d keys, want exactly the rotated one", len(stored))
	}
	want := []string{handshakePath + " k1", "persist k2", handshakePath + " k2"}
	if got := rs.entries(); !slices.Equal(got, want) {
		t.Fatalf("sequence = %v, want %v (stored before first use)", got, want)
	}
	if c.currentKey() != k2 {
		t.Fatal("client is not on the rotated key")
	}

	// Later requests use the new key; the old one is revoked server-side.
	if err := postSomething(t, c); err != nil {
		t.Fatalf("request after adoption: %v", err)
	}
	if got := rs.entries(); got[len(got)-1] != "/api/v1/agent/events k2" {
		t.Fatalf("last request = %q, want events on k2", got[len(got)-1])
	}
	if _, active := rs.ids[k1]; active {
		t.Fatal("old key still active after adoption")
	}
}

func TestHandshakeRotationNotAdopted(t *testing.T) {
	cases := map[string]struct {
		setup     func(rs *rotationServer, k1 string)
		persister bool
		failStore error
		persists  int
	}{
		"no key store":           {persister: false},
		"store fails":            {persister: true, failStore: errors.New("disk full"), persists: 1},
		"undeliverable":          {persister: true, setup: func(rs *rotationServer, _ string) { rs.undeliv = true }},
		"sealed for another key": {persister: true, setup: func(rs *rotationServer, _ string) { rs.sealWith = "bdk_not_this_devices_key_0123456789" }},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			k1, k2 := randomKey(t), randomKey(t)
			rs, srv := newRotationServer(t, k1, k2)
			if tc.setup != nil {
				tc.setup(rs, k1)
			}
			var stored []string
			var store KeyStore
			if tc.persister {
				store = recordingPersister(rs, &stored, tc.failStore)
			}
			c := newRotationClient(srv.URL, k1, store)

			hs := handshake(t, c)
			if hs.CurrentKeyID != 1 || hs.PendingRotation == nil {
				t.Fatalf("want the first handshake's response back, got currentKeyId %d", hs.CurrentKeyID)
			}
			if c.currentKey() != k1 {
				t.Fatal("client left its current key without a stored replacement")
			}
			persists := 0
			for _, e := range rs.entries() {
				if strings.HasPrefix(e, "persist") {
					persists++
				}
				if strings.HasSuffix(e, " k2") && !strings.HasPrefix(e, "persist") {
					t.Fatalf("rotated key was sent: %v", rs.entries())
				}
			}
			if persists != tc.persists {
				t.Fatalf("persist calls = %d, want %d", persists, tc.persists)
			}
			if err := postSomething(t, c); err != nil {
				t.Fatalf("old key must keep working: %v", err)
			}
		})
	}
}

func TestRotatedKeyRefusedFallsBackOnce(t *testing.T) {
	k1, k2 := randomKey(t), randomKey(t)
	rs, srv := newRotationServer(t, k1, k2)
	rs.refuse[k2] = true
	var stored []string
	c := newRotationClient(srv.URL, k1, recordingPersister(rs, &stored, nil))

	hs := handshake(t, c)
	if hs.CurrentKeyID != 1 {
		t.Fatalf("currentKeyId = %d, want 1 (the retry on the old key)", hs.CurrentKeyID)
	}
	want := []string{
		handshakePath + " k1", "persist k2", handshakePath + " k2", // refused
		handshakePath + " k1", "persist k1", // retried on the old key, which is written back
	}
	if got := rs.entries(); !slices.Equal(got, want) {
		t.Fatalf("sequence = %v, want %v", got, want)
	}
	if !slices.Equal(stored, []string{k2, k1}) {
		t.Fatal("the old key was not written back after the rotated key was refused")
	}
	if c.currentKey() != k1 {
		t.Fatal("client is not back on the old key")
	}

	// Offered the same refused key again: not re-adopted.
	handshake(t, c)
	if got := rs.entries(); len(got) != len(want)+1 || got[len(got)-1] != handshakePath+" k1" {
		t.Fatalf("refused key retried: %v", got)
	}
}

// TestRotatedKeyFallbackSurvivesTransientFailure: a 5xx on the retry with
// the old key is not a refusal. observeAuth returns before touching the
// restore state for any status other than 2xx or 401, so the old key is
// still written back once the next request with it is accepted.
func TestRotatedKeyFallbackSurvivesTransientFailure(t *testing.T) {
	k1, k2 := randomKey(t), randomKey(t)
	rs, srv := newRotationServer(t, k1, k2)
	rs.refuse[k2] = true
	var stored []string
	persist := recordingPersister(rs, &stored, nil)
	c := newRotationClient(srv.URL, k1, func(ctx context.Context, apiKey string) error {
		err := persist(ctx, apiKey)
		if apiKey == k2 { // pragma: allowlist secret
			// The retry on the old key, after k2 is refused, hits a 503.
			rs.mu.Lock()
			rs.failNext[k1] = http.StatusServiceUnavailable
			rs.mu.Unlock()
		}
		return err
	})

	_, _ = c.Handshake(context.Background(), "") // the fallback retry fails with 503
	if c.currentKey() != k1 {
		t.Fatal("client is not back on the old key")
	}
	if !slices.Equal(stored, []string{k2}) {
		t.Fatalf("store = %d keys before any accepted request on the old key, want only the rotated key", len(stored))
	}
	if err := postSomething(t, c); err != nil {
		t.Fatalf("request on the old key: %v", err)
	}
	if !slices.Equal(stored, []string{k2, k1}) {
		t.Fatal("a 503 on the fallback retry dropped the write-back of the old key")
	}
}

func TestRotatedKeyBothRefusedKeepsBothKeys(t *testing.T) {
	k1, k2 := randomKey(t), randomKey(t)
	rs, srv := newRotationServer(t, k1, k2)
	var stored []string
	persist := recordingPersister(rs, &stored, nil)
	c := newRotationClient(srv.URL, k1, func(ctx context.Context, apiKey string) error {
		err := persist(ctx, apiKey)
		// The pairing is revoked between storing and using the new key.
		rs.mu.Lock()
		rs.refuse[k1], rs.refuse[k2] = true, true
		rs.mu.Unlock()
		return err
	})

	handshake(t, c) // the first handshake succeeded, so no error
	// The store holds the new key, memory the old one: neither is lost.
	if !slices.Equal(stored, []string{k2}) {
		t.Fatalf("store = %d keys, want only the rotated key (not overwritten by an unverified fallback)", len(stored))
	}
	if c.currentKey() != k1 {
		t.Fatal("client should be back on the old key")
	}
}

// TestUploadRefusalRevertsKey: when the confirming handshake does not get
// an answer, the next request adopts. An upload refused on the new key
// cannot be replayed, but it still sends the client back to the old key,
// and the old key is written back once the server accepts it.
func TestUploadRefusalRevertsKey(t *testing.T) {
	k1, k2 := randomKey(t), randomKey(t)
	rs, srv := newRotationServer(t, k1, k2)
	rs.failNext[k2] = http.StatusServiceUnavailable
	var stored []string
	c := newRotationClient(srv.URL, k1, recordingPersister(rs, &stored, nil))

	handshake(t, c)
	if c.currentKey() != k2 {
		t.Fatal("client should stay on the stored rotated key when the confirmation got no answer")
	}

	rs.mu.Lock()
	rs.refuse[k2] = true
	rs.mu.Unlock()
	_, err := c.UploadStream(context.Background(), bytes.NewReader([]byte("data")), 4, "a.jpg", UploadOptions{})
	var ce *ClientError
	if !errors.As(err, &ce) || ce.StatusCode != http.StatusUnauthorized {
		t.Fatalf("UploadStream err = %v, want 401", err)
	}
	if c.currentKey() != k1 {
		t.Fatal("refused upload did not revert the client to the old key")
	}
	if err := postSomething(t, c); err != nil {
		t.Fatalf("request on the old key: %v", err)
	}
	if !slices.Equal(stored, []string{k2, k1}) {
		t.Fatal("old key not written back after the server accepted it")
	}
}

func TestStaleKeyRequestRetriedWithCurrentKey(t *testing.T) {
	// A request that left with the old key after the client switched gets
	// one retry with the current key.
	k1, k2 := randomKey(t), randomKey(t)
	rs, srv := newRotationServer(t, k1, k2)
	c := newRotationClient(srv.URL, k1, nil)
	delete(rs.ids, k1) // already revoked server-side
	c.keys.mu.Lock()
	c.apiKey = k2 // pragma: allowlist secret
	c.keys.mu.Unlock()
	if retry := c.observeAuth(context.Background(), k1, http.StatusUnauthorized); retry != k2 {
		t.Fatal("stale-key 401 should be retried with the current key")
	}
	if retry := c.observeAuth(context.Background(), k2, http.StatusUnauthorized); retry != "" {
		t.Fatal("a 401 on the current key must not be retried")
	}
}

func TestHandshakeIgnoresCleartextRotationKey(t *testing.T) {
	const cleartext = "bdk_cleartext_key_must_never_be_used_0123" // pragma: allowlist secret
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-API-Key") == cleartext {
			t.Error("a cleartext pendingRotation.apiKey was sent")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true,"currentKeyId":1,"pendingRotation":{"keyId":2,"apiKey":"` + cleartext + `","previousKeyExpiresAtUnix":1}}`))
	}))
	t.Cleanup(srv.Close)
	var stored []string
	c := newRotationClient(srv.URL, randomKey(t), func(_ context.Context, k string) error {
		stored = append(stored, k)
		return nil
	})
	hs := handshake(t, c)
	if hs.PendingRotation == nil || len(stored) != 0 {
		t.Fatalf("cleartext key must not be stored or adopted (stored %d)", len(stored))
	}
}

func TestHandshakeDue(t *testing.T) {
	k1 := randomKey(t)
	_, srv := newRotationServer(t, k1)
	c := newRotationClient(srv.URL, k1, nil)
	if !c.HandshakeDue(time.Hour) {
		t.Fatal("a client that never handshook is due")
	}
	handshake(t, c)
	if c.HandshakeDue(time.Hour) {
		t.Fatal("not due right after a handshake")
	}
	if !c.HandshakeDue(0) {
		t.Fatal("always due with a zero interval")
	}
}

// TestRepairDeadlineUndeliverableRotation (#218): a rotation the server
// cannot deliver (empty sealedApiKey) keeps its previousKeyExpiresAtUnix
// as the re-pair deadline, and the next handshake whose response has no
// pendingRotation clears it.
func TestRepairDeadlineUndeliverableRotation(t *testing.T) {
	k1, k2 := randomKey(t), randomKey(t)
	rs, srv := newRotationServer(t, k1, k2)
	rs.undeliv = true
	var stored []string
	c := newRotationClient(srv.URL, k1, recordingPersister(rs, &stored, nil))

	if got := c.RepairDeadlineUnix(); got != 0 {
		t.Fatalf("deadline before any handshake = %d, want 0", got)
	}
	handshake(t, c)
	if got := c.RepairDeadlineUnix(); got != 1900000000 {
		t.Fatalf("deadline after an undeliverable rotation = %d, want 1900000000", got)
	}
	handshake(t, c) // still pending: still set
	if got := c.RepairDeadlineUnix(); got != 1900000000 {
		t.Fatalf("deadline after a second undeliverable handshake = %d, want 1900000000", got)
	}

	// The operator withdraws the rotation (or the device was re-paired
	// onto the newest key): the handshake has no pendingRotation.
	rs.mu.Lock()
	delete(rs.ids, k2)
	rs.newest = k1
	rs.mu.Unlock()
	hs := handshake(t, c)
	if hs.PendingRotation != nil {
		t.Fatal("test server still announces a rotation")
	}
	if got := c.RepairDeadlineUnix(); got != 0 {
		t.Fatalf("deadline after a handshake without pendingRotation = %d, want 0", got)
	}
}

// TestRepairDeadlineClearedByAdoption (#218): once the server can deliver
// the rotated key and the client adopts it, the re-pair deadline is gone.
// The first handshake of that round still carries pendingRotation, so it
// is the accepted request on the new key that clears it.
func TestRepairDeadlineClearedByAdoption(t *testing.T) {
	k1, k2 := randomKey(t), randomKey(t)
	rs, srv := newRotationServer(t, k1, k2)
	rs.undeliv = true
	var stored []string
	c := newRotationClient(srv.URL, k1, recordingPersister(rs, &stored, nil))

	handshake(t, c)
	if got := c.RepairDeadlineUnix(); got != 1900000000 {
		t.Fatalf("deadline after an undeliverable rotation = %d, want 1900000000", got)
	}

	rs.mu.Lock()
	rs.undeliv = false // the server now has BRANCHDAM_SECRET_KEY
	rs.mu.Unlock()
	hs := handshake(t, c)
	if hs.CurrentKeyID != 2 || c.currentKey() != k2 {
		t.Fatalf("rotated key not adopted (currentKeyId %d)", hs.CurrentKeyID)
	}
	if got := c.RepairDeadlineUnix(); got != 0 {
		t.Fatalf("deadline after adopting the rotated key = %d, want 0", got)
	}
}

// TestRepairDeadlineNotSetByDeliverableRotation: a rotation the server
// can deliver is adopted, not a reason to re-pair.
func TestRepairDeadlineNotSetByDeliverableRotation(t *testing.T) {
	k1, k2 := randomKey(t), randomKey(t)
	rs, srv := newRotationServer(t, k1, k2)
	var stored []string
	c := newRotationClient(srv.URL, k1, recordingPersister(rs, &stored, nil))
	handshake(t, c)
	if got := c.RepairDeadlineUnix(); got != 0 {
		t.Fatalf("deadline after a deliverable rotation = %d, want 0", got)
	}
}
