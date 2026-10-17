package client

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"github.com/s3ntin3l8/branchdam-mobile/core/contracttest"
)

// clientContractCases maps the pinned server fixtures this package's DTOs
// carry. Event payloads are checked in core/engine.
func clientContractCases() map[string]contracttest.Case {
	return map[string]contracttest.Case{
		"handshake_request": {
			DTO: &HandshakeRequest{},
			NotModelled: map[string]string{
				"currentKeyId": "deprecated: the server ignores it and names the key from X-API-Key",
			},
		},
		"handshake_response":  {DTO: &HandshakeResponse{}},
		"events_request":      {DTO: &AgentEventRequest{}},
		"events_response":     {DTO: &AgentEventResponse{}},
		"node_status_request": {DTO: &NodeStatusRequest{}},
		"node_status_response": {
			DTO: &NodeStatusResponse{},
			NotModelled: map[string]string{
				"statuses[].canonicalNodeUuid": "informational alias target; reclaim keys on the queue row's own nodeUuid",
			},
		},
		"telemetry_request": {
			DTO: &TelemetryInput{},
			NotModelled: map[string]string{
				"pruneStats": "the app has no prune pass",
			},
		},
		"check_content_response": {
			DTO: &ContentCheckResult{},
			NotModelled: map[string]string{
				"possibleMatch":  "the app only runs full-hash lookups, where possibleMatch equals found",
				"indexingStatus": "not consumed",
			},
		},
		"upload_response": {
			DTO: &UploadResponse{},
			ClientOnly: map[string]string{
				"ok":        "never sent by the server and never read",
				"sizeBytes": "never sent by the server and never read",
				"filePath":  "read only from a 409 dedup body; the server answers a duplicate with 200 DEDUPLICATED instead, so it is always empty",
				"isDedup":   "set by UploadStream from X-Dedup / status DEDUPLICATED, never decoded from the body",
			},
		},
	}
}

// engineContractFixtures are checked by core/engine's own conformance test
// (the payload types are unexported there).
var engineContractFixtures = []string{"event_edge_attached", "event_node_deleted"}

// contractNotUsed lists pinned fixtures the app has no DTO for, with why.
var contractNotUsed = map[string]string{
	"hello_response":             "the app never calls POST /api/v1/agent/hello",
	"rebase_request":             "the app never rebases: uploads register the node server-side",
	"rebase_response":            "the app never rebases",
	"resolve_snapshot_request":   "DaVinci Resolve snapshots are workstation-only",
	"resolve_snapshot_response":  "DaVinci Resolve snapshots are workstation-only",
	"source_status_query":        "the app never calls GET /api/v1/agent/source-status",
	"source_status_response":     "the app never calls GET /api/v1/agent/source-status",
	"event_node_created":         "the app registers nodes by upload, not EVENT_NODE_CREATED",
	"event_node_moved":           "the app never sends EVENT_NODE_MOVED",
	"event_path_rebased":         "the app never sends EVENT_PATH_REBASED",
	"event_virtual_node_created": "virtual nodes are workstation-only",
	"telemetry_response":         "SendTelemetry reads the response as map[string]any and consumes nothing",
	"check_content_query":        "checked by TestContractCheckContentQuery",
	"upload_request_headers":     "checked by TestContractUploadHeaders",
}

func TestContractFixturesRoundTripThroughClientDTOs(t *testing.T) {
	for name, c := range clientContractCases() {
		t.Run(name, func(t *testing.T) { contracttest.RoundTrip(t, name, c) })
	}
}

// TestContractFixturesAllAccountedFor: every pinned fixture is checked or
// listed as unused with a reason, so a new server route is noticed at the
// next contract-sync.
func TestContractFixturesAllAccountedFor(t *testing.T) {
	known := map[string]bool{}
	for name := range clientContractCases() {
		known[name] = true
	}
	for _, name := range engineContractFixtures {
		known[name] = true
	}
	for name := range contractNotUsed {
		known[name] = true
	}
	for _, stem := range contracttest.Stems(t) {
		if !known[stem] {
			t.Errorf("pinned server fixture %s has no conformance check and no reason it is unused", stem)
		}
		delete(known, stem)
	}
	for name := range known {
		t.Errorf("%s is listed but not pinned", name)
	}
}

// TestContractCheckContentQuery: every query parameter CheckContent sends
// is one the server reads, under the fixture's name.
func TestContractCheckContentQuery(t *testing.T) {
	var fx map[string]any
	if err := json.Unmarshal(contracttest.Read(t, "check_content_query"), &fx); err != nil {
		t.Fatal(err)
	}
	notSent := map[string]string{"sizeBytes": "optional pre-screen narrowing; the app only runs full-hash lookups"}
	var got map[string][]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.Query()
		_, _ = w.Write([]byte(`{"found":false}`))
	}))
	defer srv.Close()

	c := New(Config{BaseURL: srv.URL, APIKey: "k", AgentID: "pixel"})
	if _, err := c.CheckContent(context.Background(), mustString(t, fx, "fastHash"), mustString(t, fx, "fullHash")); err != nil {
		t.Fatal(err)
	}
	for name := range got {
		if _, ok := fx[name]; !ok {
			t.Errorf("CheckContent sends query %s, which the server does not read", name)
		}
	}
	for name, v := range fx {
		if _, skip := notSent[name]; skip {
			continue
		}
		if q := got[name]; len(q) != 1 || q[0] != v {
			t.Errorf("query %s = %v, want [%v]", name, q, v)
		}
	}
}

// TestContractUploadHeaders: UploadStream sends every header the server's
// upload route reads, and no metadata header it doesn't.
func TestContractUploadHeaders(t *testing.T) {
	var fx map[string]string
	if err := json.Unmarshal(contracttest.Read(t, "upload_request_headers"), &fx); err != nil {
		t.Fatal(err)
	}
	clientOnly := map[string]string{
		"X-Fast-Hash": "sent for older servers; branchDAM's upload route does not read it",
	}
	transport := map[string]bool{"X-Api-Key": true, "X-Timestamp": true, "X-Nonce": true, "X-Signature": true}

	var got http.Header
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Clone()
		_, _ = io.Copy(io.Discard, r.Body)
		_, _ = w.Write(contracttest.Read(t, "upload_response"))
	}))
	defer srv.Close()

	ts, err := strconv.ParseInt(fx["X-Capture-Timestamp"], 10, 64)
	if err != nil {
		t.Fatal(err)
	}
	c := New(Config{BaseURL: srv.URL, APIKey: "k", AgentID: "pixel"})
	opts := UploadOptions{
		CameraModel:    fx["X-Camera-Model"],
		FastHash:       "cdcdcdcdcdcdcdcd",
		Blake3Hash:     fx["X-Blake3-Hash"],
		SourcePathHash: fx["X-Source-Path-Hash"],
		CapturedAtUnix: ts,
	}
	if _, err := c.UploadStream(context.Background(), strings.NewReader("data"), 4, fx["X-Filename"], opts); err != nil {
		t.Fatal(err)
	}
	for name, want := range fx {
		if v := got.Get(name); v != want {
			t.Errorf("header %s = %q, want %q", name, v, want)
		}
	}
	for name := range got {
		if !strings.HasPrefix(name, "X-") || transport[name] {
			continue
		}
		if _, ok := fx[http.CanonicalHeaderKey(name)]; ok {
			continue
		}
		if _, ok := clientOnly[name]; !ok {
			t.Errorf("UploadStream sends %s, which the server does not read (drift, or list it in clientOnly with a reason)", name)
		}
	}
}

// mustString returns fx[key] as a string, failing the test (not panicking)
// when a fixture changes shape.
func mustString(t *testing.T, fx map[string]any, key string) string {
	t.Helper()
	v, ok := fx[key].(string)
	if !ok {
		t.Fatalf("fixture field %s = %#v, want a string", key, fx[key])
	}
	return v
}
