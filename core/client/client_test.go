package client

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
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
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/events" {
			http.NotFound(w, r)
			return
		}
		var req AgentEventRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.EventType != "EVENT_EDGE_ATTACHED" {
			t.Errorf("unexpected event type: %s", req.EventType)
		}
		_ = json.NewEncoder(w).Encode(AgentEventResponse{EventID: "018f-evt"})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})
	resp, err := c.SubmitEvent(context.Background(), "EVENT_EDGE_ATTACHED", `{"relation":"DERIVED_FROM"}`)
	if err != nil {
		t.Fatalf("SubmitEvent failed: %v", err)
	}
	if resp.EventID != "018f-evt" {
		t.Fatalf("unexpected eventID: %s", resp.EventID)
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

func TestSendTelemetry(t *testing.T) {
	var received bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/mobile/telemetry" {
			http.NotFound(w, r)
			return
		}
		received = true
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
	}))
	defer server.Close()

	c := New(Config{BaseURL: server.URL, APIKey: "key", AgentID: "agent-1"})
	err := c.SendTelemetry(context.Background(), MobileTelemetry{
		DeviceID:     "pixel-10-fold",
		BatteryLevel: 85,
		IsCharging:   true,
	})
	if err != nil {
		t.Fatalf("SendTelemetry failed: %v", err)
	}
	if !received {
		t.Fatal("expected telemetry to be received")
	}
}

func TestUploadStream(t *testing.T) {
	testPayload := []byte("branchdam raw image payload bytes for testing upload")
	var receivedBytes []byte
	var capturedBlake3 string
	var capturedCamera string

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/upload" {
			http.NotFound(w, r)
			return
		}
		capturedBlake3 = r.Header.Get("X-Blake3-Hash")
		capturedCamera = r.Header.Get("X-Camera-Model")
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
	// Server returns 409 but no nodeUuid in body — must still be treated as DedupError
	// so the engine can mark the item complete instead of failing it.
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
	_, ok := AsDedupResponse(err)
	if !ok {
		t.Fatalf("expected 409-without-nodeUuid to still be AsDedupResponse, got false (err: %v)", err)
	}
}
