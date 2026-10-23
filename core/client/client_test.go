package client

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/google/uuid"
)

func TestClientHandshake(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/handshake" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("X-API-Key") != "secret-key" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		var req HandshakeRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}

		if req.AgentID != "pixel-fold-01" {
			t.Errorf("unexpected agent ID: %s", req.AgentID)
		}

		resp := HandshakeResponse{
			OK:                 true,
			ServerVersion:      "0.1.0",
			ServerTimeUnix:     1724000000,
			PendingEventsCount: 0,
			NamingTemplate:     "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}",
		}
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer server.Close()

	c := New(Config{
		BaseURL:       server.URL,
		APIKey:        "secret-key",
		AgentID:       "pixel-fold-01",
		ClientVersion: "0.1.0",
	})

	res, err := c.Handshake(context.Background(), "")
	if err != nil {
		t.Fatalf("Handshake failed: %v", err)
	}
	if !res.OK || res.ServerVersion != "0.1.0" || res.NamingTemplate != "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}" {
		t.Fatalf("unexpected handshake response: %+v", res)
	}
}

func TestSubmitEvent(t *testing.T) {
	var receivedUUIDs []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/events" {
			http.NotFound(w, r)
			return
		}
		var req AgentEventRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		receivedUUIDs = append(receivedUUIDs, req.EventUUID)
		if req.EventUUID == "" {
			t.Error("expected non-empty EventUUID in request")
		}
		if req.EventType != "EVENT_EDGE_ATTACHED" {
			t.Errorf("unexpected event type: %s", req.EventType)
		}
		_ = json.NewEncoder(w).Encode(AgentEventResponse{EventID: "018f-evt"})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})

	// 1. Explicit EventUUID is preserved across retries
	const stableUUID = "018f3a9b-8d76-7890-a123-456789abcdef"
	resp, err := c.SubmitEvent(context.Background(), stableUUID, "EVENT_EDGE_ATTACHED", `{"relation":"DERIVED_FROM"}`)
	if err != nil {
		t.Fatalf("SubmitEvent failed: %v", err)
	}
	if resp.EventID != "018f-evt" {
		t.Fatalf("unexpected eventID: %s", resp.EventID)
	}

	// Retry with the same stableUUID
	_, err = c.SubmitEvent(context.Background(), stableUUID, "EVENT_EDGE_ATTACHED", `{"relation":"DERIVED_FROM"}`)
	if err != nil {
		t.Fatalf("SubmitEvent retry failed: %v", err)
	}

	if len(receivedUUIDs) < 2 || receivedUUIDs[0] != stableUUID || receivedUUIDs[1] != stableUUID {
		t.Errorf("expected both attempts to reuse stableUUID %s, got %v", stableUUID, receivedUUIDs)
	}

	// 2. Omitting EventUUID generates a valid UUIDv7
	_, err = c.SubmitEvent(context.Background(), "", "EVENT_EDGE_ATTACHED", `{"relation":"DERIVED_FROM"}`)
	if err != nil {
		t.Fatalf("SubmitEvent with empty UUID failed: %v", err)
	}
	if len(receivedUUIDs) < 3 {
		t.Fatal("expected 3 requests received")
	}
	parsed, err := uuid.Parse(receivedUUIDs[2])
	if err != nil {
		t.Fatalf("auto-generated EventUUID not valid: %v", err)
	}
	if parsed.Version() != 7 {
		t.Errorf("expected UUID version 7, got %d", parsed.Version())
	}
}

func TestGetNodeStatuses(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/node-status" {
			http.NotFound(w, r)
			return
		}
		resp := NodeStatusResponse{
			Statuses: []NodeStatusItem{
				{
					NodeUUID: "node-1",
					Found:    true,
					Tier:     "TIER3_MASTER_ARCHIVE",
					Verified: true,
				},
			},
		}
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})
	statuses, err := c.GetNodeStatuses(context.Background(), []string{"node-1"})
	if err != nil {
		t.Fatalf("GetNodeStatuses failed: %v", err)
	}
	if len(statuses) != 1 || !statuses[0].Verified || statuses[0].Tier != "TIER3_MASTER_ARCHIVE" {
		t.Fatalf("unexpected statuses: %+v", statuses)
	}
}

// TestGetNodeStatuses_DecodesLifecycleState: the reclaim gate depends on
// lifecycleState, so the DTO must decode it from the server's response.
func TestGetNodeStatuses_DecodesLifecycleState(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"statuses":[{"nodeUuid":"n1","found":true,"lifecycleState":"TRASHED","tier":"TIER3_MASTER_ARCHIVE","verified":true}]}`)
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})
	statuses, err := c.GetNodeStatuses(context.Background(), []string{"n1"})
	if err != nil {
		t.Fatalf("GetNodeStatuses failed: %v", err)
	}
	if len(statuses) != 1 || statuses[0].LifecycleState != "TRASHED" {
		t.Fatalf("lifecycleState not decoded: %+v", statuses)
	}
}

// nodeStatusEchoServer answers node-status like branchDAM does (one entry
// per requested UUID, 400 for an empty or >400-UUID request) and records
// each request's size. failOnCall > 0 makes that call (1-based) return 500.
func nodeStatusEchoServer(t *testing.T, failOnCall int) (*httptest.Server, *[]int) {
	t.Helper()
	sizes := []int{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req NodeStatusRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		sizes = append(sizes, len(req.NodeUUIDs))
		if failOnCall > 0 && len(sizes) == failOnCall {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		if len(req.NodeUUIDs) == 0 || len(req.NodeUUIDs) > 400 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		out := make([]NodeStatusItem, 0, len(req.NodeUUIDs))
		for _, id := range req.NodeUUIDs {
			out = append(out, NodeStatusItem{NodeUUID: id, Found: true})
		}
		_ = json.NewEncoder(w).Encode(NodeStatusResponse{Statuses: out})
	}))
	t.Cleanup(srv.Close)
	return srv, &sizes
}

func makeNodeUUIDs(n int) []string {
	ids := make([]string, n)
	for i := range ids {
		ids[i] = uuid.NewString()
	}
	return ids
}

// TestGetNodeStatuses_BatchesAt400: the server rejects more than 400 UUIDs
// per request, so 401 UUIDs must be split into two requests (400 + 1) and
// the results concatenated in input order.
func TestGetNodeStatuses_BatchesAt400(t *testing.T) {
	srv, sizes := nodeStatusEchoServer(t, 0)
	c := New(Config{BaseURL: srv.URL, APIKey: "key", AgentID: "agent-1"})

	ids := makeNodeUUIDs(MaxNodeStatusBatch + 1)
	statuses, err := c.GetNodeStatuses(context.Background(), ids)
	if err != nil {
		t.Fatalf("GetNodeStatuses failed: %v", err)
	}
	if got := *sizes; len(got) != 2 || got[0] != 400 || got[1] != 1 {
		t.Fatalf("request sizes = %v, want [400 1]", got)
	}
	if len(statuses) != len(ids) {
		t.Fatalf("got %d statuses, want %d", len(statuses), len(ids))
	}
	for i, st := range statuses {
		if st.NodeUUID != ids[i] {
			t.Fatalf("status %d NodeUUID = %q, want %q", i, st.NodeUUID, ids[i])
		}
	}
}

// TestGetNodeStatuses_ExactBatchIsOneRequest: exactly 400 UUIDs is one
// request, not two.
func TestGetNodeStatuses_ExactBatchIsOneRequest(t *testing.T) {
	srv, sizes := nodeStatusEchoServer(t, 0)
	c := New(Config{BaseURL: srv.URL, APIKey: "key", AgentID: "agent-1"})

	if _, err := c.GetNodeStatuses(context.Background(), makeNodeUUIDs(MaxNodeStatusBatch)); err != nil {
		t.Fatalf("GetNodeStatuses failed: %v", err)
	}
	if got := *sizes; len(got) != 1 || got[0] != 400 {
		t.Fatalf("request sizes = %v, want [400]", got)
	}
}

// TestGetNodeStatuses_EmptyInputSendsNoRequest: the server rejects an
// empty nodeUuids list, so an empty input must not hit the network.
func TestGetNodeStatuses_EmptyInputSendsNoRequest(t *testing.T) {
	srv, sizes := nodeStatusEchoServer(t, 0)
	c := New(Config{BaseURL: srv.URL, APIKey: "key", AgentID: "agent-1"})

	statuses, err := c.GetNodeStatuses(context.Background(), nil)
	if err != nil {
		t.Fatalf("GetNodeStatuses failed: %v", err)
	}
	if len(statuses) != 0 || len(*sizes) != 0 {
		t.Fatalf("statuses = %v, requests = %v; want none", statuses, *sizes)
	}
}

// TestGetNodeStatuses_FailedBatchFailsWholeCall: a partial answer must
// never be returned as if it were complete.
func TestGetNodeStatuses_FailedBatchFailsWholeCall(t *testing.T) {
	srv, _ := nodeStatusEchoServer(t, 2)
	c := New(Config{BaseURL: srv.URL, APIKey: "key", AgentID: "agent-1"})

	statuses, err := c.GetNodeStatuses(context.Background(), makeNodeUUIDs(MaxNodeStatusBatch+1))
	if err == nil {
		t.Fatalf("expected error when the second batch fails, got %d statuses", len(statuses))
	}
	if statuses != nil {
		t.Fatalf("expected nil statuses on error, got %d", len(statuses))
	}
}

func TestSendTelemetry(t *testing.T) {
	var received bool
	var receivedBody map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/telemetry" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewDecoder(r.Body).Decode(&receivedBody)
		received = true
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "acknowledgedAtUnix": 1724000001})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1", ClientVersion: "1.2.3"})
	err := c.SendTelemetry(context.Background(), MobileTelemetry{
		DeviceID:        "pixel-10-fold",
		Platform:        "android",
		TotalBytes:      256000000000,
		FreeBytes:       128000000000,
		UsedBytes:       128000000000,
		SafeToFreeBytes: 32000000000,
		BatteryLevel:    85,
		IsCharging:      true,
		TimestampUnix:   1724000000,
	})
	if err != nil {
		t.Fatalf("SendTelemetry failed: %v", err)
	}
	if !received {
		t.Fatal("expected telemetry to be received")
	}
	if receivedBody["agentId"] != "agent-1" {
		t.Errorf("agentId = %v, want agent-1", receivedBody["agentId"])
	}
	if receivedBody["clientVersion"] != "1.2.3" {
		t.Errorf("clientVersion = %v, want 1.2.3", receivedBody["clientVersion"])
	}
	scratch, ok := receivedBody["scratchStorage"].(map[string]any)
	if !ok || scratch["mountPath"] != DefaultMobileMountPath {
		t.Errorf("expected scratchStorage with mountPath '%s', got %v", DefaultMobileMountPath, receivedBody["scratchStorage"])
	}
	if scratch["totalBytes"] != float64(256000000000) {
		t.Errorf("totalBytes = %v, want 256000000000", scratch["totalBytes"])
	}
	if scratch["freeBytes"] != float64(128000000000) {
		t.Errorf("freeBytes = %v, want 128000000000", scratch["freeBytes"])
	}
	if scratch["usedBytes"] != float64(128000000000) {
		t.Errorf("usedBytes = %v, want 128000000000", scratch["usedBytes"])
	}
	if scratch["prunableBytes"] != float64(32000000000) {
		t.Errorf("prunableBytes = %v, want 32000000000", scratch["prunableBytes"])
	}
}

func TestSendTelemetry_DeviceIDAndVersionFallback(t *testing.T) {
	var receivedBody map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/telemetry" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewDecoder(r.Body).Decode(&receivedBody)
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "acknowledgedAtUnix": 1724000001})
	}))
	defer server.Close()

	// When c.AgentID is empty, fall back to telemetry.DeviceID; when telemetry.ClientVersion is set, use it
	c := New(Config{BaseURL: server.URL, APIKey: "key"})
	err := c.SendTelemetry(context.Background(), MobileTelemetry{
		DeviceID:      "pixel-fallback",
		ClientVersion: "2.0.0-custom",
		TimestampUnix: 1724000000,
	})
	if err != nil {
		t.Fatalf("SendTelemetry failed: %v", err)
	}
	if receivedBody["agentId"] != "pixel-fallback" {
		t.Errorf("agentId = %v, want pixel-fallback", receivedBody["agentId"])
	}
	if receivedBody["clientVersion"] != "2.0.0-custom" {
		t.Errorf("clientVersion = %v, want 2.0.0-custom", receivedBody["clientVersion"])
	}
}

func TestSendTelemetry_PlatformMountPath(t *testing.T) {
	var receivedBody map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&receivedBody)
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "acknowledgedAtUnix": 1724000001})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key"})

	// 1. iOS platform reports DefaultIOSMountPath
	err := c.SendTelemetry(context.Background(), MobileTelemetry{
		DeviceID:      "iphone-16-pro",
		Platform:      "ios",
		TimestampUnix: 1724000000,
	})
	if err != nil {
		t.Fatalf("SendTelemetry: %v", err)
	}
	scratch := receivedBody["scratchStorage"].(map[string]any)
	if scratch["mountPath"] != DefaultIOSMountPath {
		t.Errorf("mountPath for iOS = %v, want %s", scratch["mountPath"], DefaultIOSMountPath)
	}

	// 2. Custom MountPath takes precedence
	err = c.SendTelemetry(context.Background(), MobileTelemetry{
		DeviceID:      "custom-device",
		MountPath:     "/custom/mount",
		TimestampUnix: 1724000000,
	})
	if err != nil {
		t.Fatalf("SendTelemetry: %v", err)
	}
	scratch = receivedBody["scratchStorage"].(map[string]any)
	if scratch["mountPath"] != "/custom/mount" {
		t.Errorf("mountPath = %v, want /custom/mount", scratch["mountPath"])
	}
}

func TestUploadStream_InvalidSourcePathHash(t *testing.T) {
	c := New(Config{BaseURL: "http://example.com", APIKey: "key"})
	// Test short hash
	_, err := c.UploadStream(context.Background(), bytes.NewReader([]byte("test")), 4, "test.dng", UploadOptions{
		SourcePathHash: "invalid_hash",
	})
	if err == nil {
		t.Fatal("expected error for invalid SourcePathHash, got nil")
	}

	// Test non-hex characters
	_, err = c.UploadStream(context.Background(), bytes.NewReader([]byte("test")), 4, "test.dng", UploadOptions{
		SourcePathHash: "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",
	})
	if err == nil {
		t.Fatal("expected error for non-hex SourcePathHash, got nil")
	}
}

func TestUploadStream(t *testing.T) {
	testPayload := []byte("branchdam raw image payload bytes for testing upload")
	var receivedBytes []byte
	var capturedBlake3 string
	var capturedCamera string
	var capturedSourcePathHash string

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/upload" {
			http.NotFound(w, r)
			return
		}
		capturedBlake3 = r.Header.Get("X-Blake3-Hash")
		capturedCamera = r.Header.Get("X-Camera-Model")
		capturedSourcePathHash = r.Header.Get("X-Source-Path-Hash")
		filename := r.Header.Get("X-Filename")
		if filename != "PXL_TEST.dng" {
			t.Errorf("unexpected filename header: %s", filename)
		}

		var err error
		receivedBytes, err = io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "failed read", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(UploadResponse{
			OK:           true,
			NodeUUID:     "018f-node-uuid",
			FilePath:     "/storage/archive/2026/2026-08-29_Pixel-Fold/PXL_TEST.dng",
			Status:       "UPLOADED",
			SizeBytes:    int64(len(receivedBytes)),
			BytesWritten: int64(len(receivedBytes)),
			Blake3Hash:   capturedBlake3,
			RelativePath: "2026/2026-08-29_Pixel-Fold/PXL_TEST.dng",
		})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})

	var progressCalled int64
	opts := UploadOptions{
		CameraModel:    "Pixel-Fold",
		Blake3Hash:     "fakeblake3hash",
		FastHash:       "fast1234",
		SourcePathHash: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", // pragma: allowlist secret
		CapturedAtUnix: 1724000000,
		ProgressFn: func(bytesSent int64, totalBytes int64) {
			atomic.AddInt64(&progressCalled, 1)
		},
	}

	resp, err := c.UploadStream(
		context.Background(),
		bytes.NewReader(testPayload),
		int64(len(testPayload)),
		"PXL_TEST.dng",
		opts,
	)
	if err != nil {
		t.Fatalf("UploadStream failed: %v", err)
	}

	if !resp.OK || resp.NodeUUID != "018f-node-uuid" || resp.RelativePath != "2026/2026-08-29_Pixel-Fold/PXL_TEST.dng" {
		t.Fatalf("unexpected upload response: %+v", resp)
	}
	if !bytes.Equal(receivedBytes, testPayload) {
		t.Fatal("received payload on server did not match sent payload")
	}
	if capturedBlake3 != "fakeblake3hash" {
		t.Fatalf("blake3 header mismatch: %s", capturedBlake3)
	}
	if capturedCamera != "Pixel-Fold" {
		t.Fatalf("camera header mismatch: %s", capturedCamera)
	}
	if capturedSourcePathHash != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" { // pragma: allowlist secret
		t.Fatalf("source path hash header mismatch: %s", capturedSourcePathHash)
	}
	if atomic.LoadInt64(&progressCalled) == 0 {
		t.Fatal("expected progress callback to be called")
	}
}

func TestUploadStreamError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, `{"error":"blake3 mismatch"}`, http.StatusUnprocessableEntity)
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})
	_, err := c.UploadStream(
		context.Background(),
		bytes.NewReader([]byte("test")),
		4,
		"test.jpg",
		UploadOptions{},
	)
	if err == nil {
		t.Fatal("expected error on 422 Unprocessable Entity, got nil")
	}
}

func TestUploadStream_DedupHeader(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Dedup", "true")
		resp := UploadResponse{
			OK:       true,
			NodeUUID: "existing-node-uuid",
			Status:   "EXISTS",
		}
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})
	resp, err := c.UploadStream(
		context.Background(),
		bytes.NewReader([]byte("duplicate content")),
		17,
		"dup.jpg",
		UploadOptions{},
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !resp.IsDedup || resp.NodeUUID != "existing-node-uuid" {
		t.Fatalf("expected dedup response, got %+v", resp)
	}
}

func TestUploadStream_DedupConflictError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"nodeUuid": "conflict-node-uuid",
			"filePath": "/archive/conflict.jpg",
		})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})
	_, err := c.UploadStream(
		context.Background(),
		bytes.NewReader([]byte("duplicate content")),
		17,
		"conflict.jpg",
		UploadOptions{},
	)
	if err == nil {
		t.Fatal("expected error on 409 conflict, got nil")
	}

	dedup, ok := AsDedupResponse(err)
	if !ok {
		t.Fatalf("expected AsDedupResponse true, got false (err: %v)", err)
	}
	if dedup.NodeUUID != "conflict-node-uuid" {
		t.Fatalf("unexpected node uuid: %s", dedup.NodeUUID)
	}
}

func TestUploadStream_DedupConflictError_NoNodeUUID(t *testing.T) {
	// Server returns 409 with no parseable nodeUuid in the body. The
	// audit found that the pre-B behaviour treated this as a soft
	// dedup (empty NodeUUID), which silently orphaned the asset.
	// Now: 409 + empty NodeUUID is a hard DEDUP_NO_NODE_UUID error
	// so the engine can fail loud and re-queue.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		w.Write([]byte(`{"error":"duplicate"}`))
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})
	_, err := c.UploadStream(
		context.Background(),
		bytes.NewReader([]byte("duplicate")),
		9,
		"dup_no_uuid.jpg",
		UploadOptions{},
	)
	if err == nil {
		t.Fatal("expected error on 409 with no nodeUuid, got nil")
	}
	if _, ok := AsDedupResponse(err); ok {
		t.Fatalf("expected 409-without-nodeUuid NOT to be a DedupError, got one (err: %v)", err)
	}
	ce, ok := err.(*ClientError)
	if !ok {
		t.Fatalf("expected *ClientError, got %T: %v", err, err)
	}
	if ce.Code != CodeDedupNoNodeUUID {
		t.Fatalf("Code = %q, want %q", ce.Code, CodeDedupNoNodeUUID)
	}
}

// TestRequestHeaders_NoAuthorizationHeader: T2-6 — the API key is sent
// only in X-API-Key, never in Authorization: Bearer. Previously both
// were set, doubling the key's surface in any log line.
func TestRequestHeaders_NoAuthorizationHeader(t *testing.T) {
	var sawXAPIKey, sawAuth bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawXAPIKey = r.Header.Get("X-API-Key") == "secret-key"
		sawAuth = r.Header.Get("Authorization") != ""
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(HandshakeResponse{OK: true})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "secret-key", AgentID: "agent"})
	if _, err := c.Handshake(context.Background(), ""); err != nil {
		t.Fatalf("Handshake: %v", err)
	}
	if !sawXAPIKey {
		t.Fatalf("X-API-Key header not set")
	}
	if sawAuth {
		t.Fatalf("Authorization header should not be set (T2-6)")
	}
}

// TestResponseBody_TooLarge: B.2.4 — server returns >1 MiB body; the
// client surfaces RESPONSE_TOO_LARGE rather than OOM-ing.
func TestResponseBody_TooLarge(t *testing.T) {
	// Build a server that returns a body larger than MaxResponseBodyBytes.
	big := bytes.Repeat([]byte("x"), MaxResponseBodyBytes+1024)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(big)
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent"})
	_, err := c.Handshake(context.Background(), "")
	if err == nil {
		t.Fatal("expected error on oversized response, got nil")
	}
	ce, ok := err.(*ClientError)
	if !ok {
		t.Fatalf("error type = %T, want *ClientError", err)
	}
	if ce.Code != CodeResponseTooLarge {
		t.Fatalf("Code = %q, want %q", ce.Code, CodeResponseTooLarge)
	}
}

// TestUploadStream_HashMismatch: B.2.6 — server returns a different
// BLAKE3 than we sent; the client surfaces HASH_MISMATCH.
func TestUploadStream_HashMismatch(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(UploadResponse{
			OK:         true,
			NodeUUID:   "node-uuid-1",
			Blake3Hash: "b3_server_hash",
		})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent"})
	_, err := c.UploadStream(
		context.Background(),
		bytes.NewReader([]byte("payload")),
		7,
		"hash_mismatch.dng",
		UploadOptions{Blake3Hash: "b3_client_hash"},
	)
	if err == nil {
		t.Fatal("expected error on hash mismatch, got nil")
	}
	ce, ok := err.(*ClientError)
	if !ok {
		t.Fatalf("error type = %T, want *ClientError", err)
	}
	if ce.Code != CodeHashMismatch {
		t.Fatalf("Code = %q, want %q", ce.Code, CodeHashMismatch)
	}
}

// TestUploadStream_HashAbsentFromResponse: the server returned no blake3
// hash at all. That is not a pass -- the server confirmed nothing -- so the
// client must refuse the response rather than record the upload as
// verified.
func TestUploadStream_HashAbsentFromResponse(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(UploadResponse{
			OK:       true,
			NodeUUID: "node-uuid-1",
		})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent"})
	_, err := c.UploadStream(
		context.Background(),
		bytes.NewReader([]byte("payload")),
		7,
		"no_hash.dng",
		UploadOptions{Blake3Hash: "b3_client_hash"},
	)
	if err == nil {
		t.Fatal("expected an error when the server confirmed nothing, got nil")
	}
	ce, ok := err.(*ClientError)
	if !ok {
		t.Fatalf("error type = %T, want *ClientError", err)
	}
	if ce.Code != CodeHashMismatch {
		t.Fatalf("Code = %q, want %q", ce.Code, CodeHashMismatch)
	}
}

// TestUploadStream_HashCaseInsensitive: a server that returns the same
// digest in a different case is not claiming a different content hash. The
// client must not refuse the upload over casing alone -- that would break
// ingestion against a server that uppercases its hex.
func TestUploadStream_HashCaseInsensitive(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(UploadResponse{
			OK:         true,
			NodeUUID:   "node-uuid-1",
			Blake3Hash: strings.ToUpper("b3_client_hash"),
		})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent"})
	if _, err := c.UploadStream(
		context.Background(),
		bytes.NewReader([]byte("payload")),
		7,
		"case.dng",
		UploadOptions{Blake3Hash: "b3_client_hash"},
	); err != nil {
		t.Fatalf("UploadStream failed on a case-only difference: %v", err)
	}
}

func TestRequestHeaders_SignaturePresent(t *testing.T) {
	var sawTimestamp, sawNonce, sawSignature bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawTimestamp = r.Header.Get("X-Timestamp") != ""
		sawNonce = r.Header.Get("X-Nonce") != ""
		sawSignature = r.Header.Get("X-Signature") != ""
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(HandshakeResponse{OK: true})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "secret-key", AgentID: "agent"})
	if _, err := c.Handshake(context.Background(), ""); err != nil {
		t.Fatalf("Handshake: %v", err)
	}
	if !sawTimestamp {
		t.Fatal("X-Timestamp header not set")
	}
	if !sawNonce {
		t.Fatal("X-Nonce header not set")
	}
	if !sawSignature {
		t.Fatal("X-Signature header not set")
	}
}

func TestRequestHeaders_NoSignatureOnUpload(t *testing.T) {
	var sawSignature bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawSignature = r.Header.Get("X-Signature") != ""
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(UploadResponse{OK: true, NodeUUID: "node-1"})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent"})
	_, err := c.UploadStream(
		context.Background(),
		bytes.NewReader([]byte("payload")),
		7,
		"test.dng",
		UploadOptions{},
	)
	if err != nil {
		t.Fatalf("UploadStream: %v", err)
	}
	if sawSignature {
		t.Fatal("upload should not carry X-Signature (server exempts /upload)")
	}
}

func TestRequestHeaders_GETSignaturePresent(t *testing.T) {
	var sawTimestamp, sawNonce, sawSignature bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawTimestamp = r.Header.Get("X-Timestamp") != ""
		sawNonce = r.Header.Get("X-Nonce") != ""
		sawSignature = r.Header.Get("X-Signature") != ""
		_ = json.NewEncoder(w).Encode(ContentCheckResult{Found: false})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "secret-key", AgentID: "agent"})
	_, err := c.CheckContent(context.Background(), "", "abc123")
	if err != nil {
		t.Fatalf("CheckContent: %v", err)
	}
	if !sawTimestamp {
		t.Fatal("X-Timestamp header not set on GET request")
	}
	if !sawNonce {
		t.Fatal("X-Nonce header not set on GET request")
	}
	if !sawSignature {
		t.Fatal("X-Signature header not set on GET request")
	}
}

func TestSignRequest_CanonicalString(t *testing.T) {
	apiKey := "test-api-key-1234567890123456" // pragma: allowlist secret
	method := "POST"
	path := "/api/v1/agent/handshake"
	nonce := "aabbccdd"
	timestamp := "1234567890"
	body := []byte(`{"agentId":"test"}`)

	c := New(Config{BaseURL: "http://localhost", APIKey: apiKey, AgentID: "agent"})
	sig := signRequest(c.currentKey(), method, path, nonce, timestamp, body)

	// Compute expected HMAC independently
	mac := hmac.New(sha256.New, []byte(apiKey))
	mac.Write([]byte(method + "\n" + path + "\n" + nonce + "\n" + timestamp + "\n"))
	mac.Write(body)
	expected := hex.EncodeToString(mac.Sum(nil))

	if sig != expected {
		t.Fatalf("signature mismatch:\n  got:  %s\n  want: %s", sig, expected)
	}
}

func TestReplayProtection_NonceUniqueness(t *testing.T) {
	_, nonce1, err := newReplayProtectionFields()
	if err != nil {
		t.Fatalf("first call: %v", err)
	}
	_, nonce2, err := newReplayProtectionFields()
	if err != nil {
		t.Fatalf("second call: %v", err)
	}
	if nonce1 == nonce2 {
		t.Fatal("two consecutive nonces should differ")
	}
}

func TestTLS12Minimum(t *testing.T) {
	c := New(Config{BaseURL: "https://example.com", APIKey: "key", AgentID: "agent"})

	transport, ok := c.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("httpClient.Transport is %T, want *http.Transport", c.httpClient.Transport)
	}
	if transport.TLSClientConfig == nil {
		t.Fatal("TLSClientConfig is nil")
	}
	if transport.TLSClientConfig.MinVersion != tls.VersionTLS12 {
		t.Fatalf("MinVersion = %v, want %v", transport.TLSClientConfig.MinVersion, tls.VersionTLS12)
	}

	uploadTransport, ok := c.uploadClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("uploadClient.Transport is %T, want *http.Transport", c.uploadClient.Transport)
	}
	if uploadTransport.TLSClientConfig == nil {
		t.Fatal("upload TLSClientConfig is nil")
	}
	if uploadTransport.TLSClientConfig.MinVersion != tls.VersionTLS12 {
		t.Fatalf("upload MinVersion = %v, want %v", uploadTransport.TLSClientConfig.MinVersion, tls.VersionTLS12)
	}
}

func TestClientError_Sanitized(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "invalid or missing X-API-Key", http.StatusUnauthorized)
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "wrong-key", AgentID: "agent"})
	_, err := c.Handshake(context.Background(), "")
	if err == nil {
		t.Fatal("expected error on 401, got nil")
	}

	errMsg := err.Error()
	if strings.Contains(errMsg, "invalid or missing X-API-Key") {
		t.Fatalf("error message must not echo response body (S-7): %s", errMsg)
	}
	if !strings.Contains(errMsg, "http error 401") {
		t.Fatalf("error message should contain status code: %s", errMsg)
	}
}

func TestCheckContent_Found(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/check-content" {
			http.NotFound(w, r)
			return
		}
		if r.Method != http.MethodGet {
			t.Errorf("expected GET, got %s", r.Method)
		}
		fullHash := r.URL.Query().Get("fullHash")
		if fullHash == "" {
			t.Error("fullHash query param missing")
		}
		_ = json.NewEncoder(w).Encode(ContentCheckResult{
			Found:    true,
			NodeUUID: "node-uuid-1",
			FilePath: "/archive/photo.dng",
		})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent"})
	result, err := c.CheckContent(context.Background(), "", "abc123")
	if err != nil {
		t.Fatalf("CheckContent: %v", err)
	}
	if !result.Found || result.NodeUUID != "node-uuid-1" {
		t.Fatalf("unexpected result: %+v", result)
	}
}

func TestCheckContent_NotFound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(ContentCheckResult{Found: false})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent"})
	result, err := c.CheckContent(context.Background(), "", "abc123")
	if err != nil {
		t.Fatalf("CheckContent: %v", err)
	}
	if result.Found {
		t.Fatal("expected Found=false")
	}
}

func TestCheckContent_NetworkError(t *testing.T) {
	c := New(Config{BaseURL: "http://127.0.0.1:1", APIKey: "key", AgentID: "agent"})
	_, err := c.CheckContent(context.Background(), "", "abc123")
	if err == nil {
		t.Fatal("expected error on connection refused")
	}
}

// TestSubmitEvent_NonSuccessKeepsStatusAndBody: a non-2xx answer surfaces
// the HTTP status and the (bounded) response body on the ClientError, so
// the engine can tell a terminal rejection from a transient failure and
// record why -- while Error() still never echoes the body (S-7).
func TestSubmitEvent_NonSuccessKeepsStatusAndBody(t *testing.T) {
	long := strings.Repeat("x", 2*MaxErrorBodyBytes)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = io.WriteString(w, `{"detail":"eventType must be one of ..."}`+long)
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})
	_, err := c.SubmitEvent(context.Background(), "", "EVENT_NODE_DELETED", `{"nodeUuid":"x"}`)
	var ce *ClientError
	if !errors.As(err, &ce) {
		t.Fatalf("err = %v, want *ClientError", err)
	}
	if ce.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("StatusCode = %d, want 422", ce.StatusCode)
	}
	if !strings.HasPrefix(ce.Body, `{"detail":"eventType must be one of ..."}`) || len(ce.Body) > MaxErrorBodyBytes {
		t.Errorf("Body = %q (len %d), want the response body truncated to %d bytes", ce.Body[:min(len(ce.Body), 60)], len(ce.Body), MaxErrorBodyBytes)
	}
	if strings.Contains(err.Error(), "eventType must be one of") {
		t.Errorf("Error() echoes the response body: %s", err.Error())
	}
}

func TestIsRetryableStatus(t *testing.T) {
	for code, want := range map[int]bool{
		400: false, 404: false, 409: false, 413: false, 422: false,
		401: true, 403: true, 408: true, 429: true,
		500: true, 502: true, 503: true,
	} {
		if got := IsRetryableStatus(code); got != want {
			t.Errorf("IsRetryableStatus(%d) = %v, want %v", code, got, want)
		}
	}
}

// TestErrorCodeKeepsAuthStatus: 401/403 surface as AUTH_FAILED (the shells
// show "re-pair") on both the JSON calls and the upload; other non-2xx
// answers and transport failures stay NETWORK_ERROR. The status survives
// on the error either way.
func TestErrorCodeKeepsAuthStatus(t *testing.T) {
	cases := []struct {
		status int
		code   string
	}{
		{http.StatusUnauthorized, CodeAuthFailed},
		{http.StatusForbidden, CodeAuthFailed},
		{http.StatusInternalServerError, CodeNetworkError},
		{http.StatusServiceUnavailable, CodeNetworkError},
		{http.StatusUnprocessableEntity, CodeNetworkError},
	}
	for _, c := range cases {
		t.Run(http.StatusText(c.status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(c.status)
				_, _ = io.WriteString(w, `{"error":"nope"}`)
			}))
			defer server.Close()
			cl := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})

			_, hsErr := cl.Handshake(context.Background(), "")
			_, upErr := cl.UploadStream(context.Background(), bytes.NewReader([]byte("abcd")), 4, "a.jpg", UploadOptions{})
			for name, err := range map[string]error{"handshake": hsErr, "upload": upErr} {
				var ce *ClientError
				if !errors.As(err, &ce) {
					t.Fatalf("%s: err = %v, want *ClientError", name, err)
				}
				if ce.Code != c.code || ce.StatusCode != c.status {
					t.Errorf("%s: code=%s status=%d, want %s/%d", name, ce.Code, ce.StatusCode, c.code, c.status)
				}
				if got := IsAuthError(err); got != (c.code == CodeAuthFailed) {
					t.Errorf("%s: IsAuthError = %v", name, got)
				}
				// The shells still match the status number in the message.
				if !strings.Contains(err.Error(), strconv.Itoa(c.status)) {
					t.Errorf("%s: message %q lost the status", name, err.Error())
				}
				if ce.Body != `{"error":"nope"}` {
					t.Errorf("%s: Body = %q", name, ce.Body)
				}
			}
		})
	}

	// Transport failure: no response at all.
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := server.URL
	server.Close()
	cl := New(Config{BaseURL: url, APIKey: "key", AgentID: "agent-1"})
	_, err := cl.Handshake(context.Background(), "")
	var ce *ClientError
	if !errors.As(err, &ce) || ce.Code != CodeNetworkError || ce.StatusCode != 0 {
		t.Fatalf("transport failure = %v, want NETWORK_ERROR with no status", err)
	}
}
