package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"runtime"
	"strings"
	"time"

	"github.com/google/uuid"
)

// Handshake performs agent handshake against POST /api/v1/agent/handshake.
func (c *Client) Handshake(ctx context.Context, lastProcessedEventUUID string) (*HandshakeResponse, error) {
	reqBody := HandshakeRequest{
		AgentID:                c.agentID,
		ClientVersion:          c.Version(),
		LastProcessedEventUUID: lastProcessedEventUUID,
	}

	var resp HandshakeResponse
	usedKey, err := c.postJSONWithKey(ctx, handshakePath, reqBody, &resp)
	if err != nil {
		return nil, wrapCallError("handshake failed", err)
	}
	c.markHandshake()
	// A rotated key (branchDAM S13) is stored through the KeyStore,
	// switched to and adopted right here; the confirming handshake's
	// response replaces this one. See keyrotation.go.
	if resp.PendingRotation != nil {
		if confirmed := c.adoptRotation(ctx, reqBody, usedKey, resp.PendingRotation); confirmed != nil {
			return confirmed, nil
		}
	}
	return &resp, nil
}

// SubmitEvent dispatches an agent lifecycle or lineage event to POST /api/v1/agent/events.
// If eventUUID is provided, it is reused for transport-level idempotency on retries;
// otherwise a fresh UUIDv7 is minted.
func (c *Client) SubmitEvent(ctx context.Context, eventUUID, eventType, payloadJSON string) (*AgentEventResponse, error) {
	if eventUUID == "" {
		u, err := uuid.NewV7()
		if err != nil {
			u = uuid.New()
		}
		eventUUID = u.String()
	}

	reqBody := AgentEventRequest{
		EventUUID: eventUUID,
		AgentID:   c.agentID,
		EventType: eventType,
		Payload:   payloadJSON,
	}

	var resp AgentEventResponse
	if err := c.postJSON(ctx, "/api/v1/agent/events", reqBody, &resp); err != nil {
		return nil, wrapCallError("submit event failed", err)
	}
	return &resp, nil
}

// MaxNodeStatusBatch is the largest number of node UUIDs the server accepts
// in one POST /api/v1/agent/node-status request (branchDAM's
// agentNodeStatusMaxUUIDs). A larger request is rejected outright with a
// 400, so GetNodeStatuses splits its input into chunks of at most this size.
const MaxNodeStatusBatch = 400

// GetNodeStatuses queries verification state, tier and lifecycle state for
// nodes via POST /api/v1/agent/node-status. The input is sent in chunks of at
// most MaxNodeStatusBatch UUIDs and the per-chunk results are concatenated in
// request order. An empty input returns an empty result without a request
// (the server rejects an empty nodeUuids list). Any chunk failing fails the
// whole call: a partial answer must never be mistaken for a complete one.
func (c *Client) GetNodeStatuses(ctx context.Context, nodeUUIDs []string) ([]NodeStatusItem, error) {
	if len(nodeUUIDs) == 0 {
		return []NodeStatusItem{}, nil
	}

	out := make([]NodeStatusItem, 0, len(nodeUUIDs))
	for start := 0; start < len(nodeUUIDs); start += MaxNodeStatusBatch {
		end := min(start+MaxNodeStatusBatch, len(nodeUUIDs))
		reqBody := NodeStatusRequest{
			NodeUUIDs: nodeUUIDs[start:end],
		}

		var resp NodeStatusResponse
		if err := c.postJSON(ctx, "/api/v1/agent/node-status", reqBody, &resp); err != nil {
			return nil, wrapCallError("get node status failed", err)
		}
		out = append(out, resp.Statuses...)
	}
	return out, nil
}

// SendTelemetry dispatches mobile storage telemetry via POST /api/v1/agent/telemetry.
func (c *Client) SendTelemetry(ctx context.Context, telemetry MobileTelemetry) error {
	agentID := c.agentID
	if agentID == "" {
		agentID = telemetry.DeviceID
	}
	clientVersion := c.clientVersion
	if clientVersion == "" {
		clientVersion = telemetry.ClientVersion
	}
	if clientVersion == "" {
		clientVersion = "0.1.0"
	}
	ts := telemetry.TimestampUnix
	if ts <= 0 {
		ts = time.Now().Unix()
	}

	mountPath := telemetry.MountPath
	if mountPath == "" {
		if strings.EqualFold(telemetry.Platform, "android") {
			mountPath = DefaultAndroidMountPath
		} else if strings.EqualFold(telemetry.Platform, "ios") || runtime.GOOS == "darwin" || runtime.GOOS == "ios" {
			mountPath = DefaultIOSMountPath
		} else {
			mountPath = DefaultAndroidMountPath
		}
	}

	payload := TelemetryInput{
		AgentID:       agentID,
		ClientVersion: clientVersion,
		TimestampUnix: ts,
		ScratchStorage: ScratchStorageDTO{
			MountPath:     mountPath,
			TotalBytes:    telemetry.TotalBytes,
			FreeBytes:     telemetry.FreeBytes,
			UsedBytes:     telemetry.UsedBytes,
			PrunableBytes: telemetry.SafeToFreeBytes,
		},
	}

	var resp map[string]any
	if err := c.postJSON(ctx, "/api/v1/agent/telemetry", payload, &resp); err != nil {
		return wrapCallError("send telemetry failed", err)
	}
	return nil
}

// wrapCallError wraps a low-level error with a call-site prefix while
// preserving a *ClientError inner type so callers can errors.As to it.
// Returns the prefix-wrapped *ClientError directly when the inner error
// is already a *ClientError.
func wrapCallError(prefix string, err error) error {
	var ce *ClientError
	if errors.As(err, &ce) {
		// Replace the message with the prefix context but keep the
		// structured Code so the branchdam FFI can still match on it.
		return &ClientError{
			Code:       ce.Code,
			Message:    prefix + ": " + ce.Message,
			Cause:      ce.Cause,
			StatusCode: ce.StatusCode,
			Body:       ce.Body,
		}
	}
	return fmt.Errorf("%s: %w", prefix, err)
}

// readResponseBody reads at most MaxResponseBodyBytes+1 bytes from r so
// the caller can detect an oversized response. Returns
// CodeResponseTooLarge if the body exceeds the limit.
func readResponseBody(r io.Reader) ([]byte, error) {
	limited := io.LimitReader(r, MaxResponseBodyBytes+1)
	body, err := io.ReadAll(limited)
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > MaxResponseBodyBytes {
		return nil, &ClientError{
			Code:    CodeResponseTooLarge,
			Message: fmt.Sprintf("response body exceeds %d bytes", MaxResponseBodyBytes),
		}
	}
	return body, nil
}

// doRequest is the shared HTTP lifecycle for postJSON and get. It builds
// the request, attaches signing headers, executes, reads the response,
// and unmarshals into out. bodyReader is nil for GET requests.
func (c *Client) doRequest(ctx context.Context, method, path string, bodyReader io.Reader, bodyBytes []byte, out any) error {
	_, err := c.doRequestWithKey(ctx, method, path, bodyReader != nil, bodyBytes, out)
	return err
}

// doRequestWithKey runs doRequest's lifecycle and returns the API key the
// final attempt used. After each response observeAuth may ask for one
// retry with a different key: when a freshly rotated key is refused, or
// when the key changed while the request was in flight. The body is
// rebuilt from bodyBytes for the retry.
func (c *Client) doRequestWithKey(ctx context.Context, method, path string, hasBody bool, bodyBytes []byte, out any) (string, error) {
	key := c.currentKey()
	for attempt := 0; ; attempt++ {
		var bodyReader io.Reader
		if hasBody {
			bodyReader = bytes.NewReader(bodyBytes)
		}
		status, err := c.sendOnce(ctx, method, path, key, bodyReader, bodyBytes, out)
		if status != 0 {
			if retry := c.observeAuth(ctx, key, status); retry != "" && retry != key && attempt == 0 {
				key = retry
				continue
			}
		}
		return key, err
	}
}

// sendOnce performs one round trip with apiKey. It returns the HTTP status
// (0 when no response arrived) and the same errors doRequest documents.
func (c *Client) sendOnce(ctx context.Context, method, path, apiKey string, bodyReader io.Reader, bodyBytes []byte, out any) (int, error) {
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, bodyReader)
	if err != nil {
		return 0, fmt.Errorf("create request failed: %w", err)
	}
	c.setHeaders(req, apiKey)
	if method == http.MethodGet {
		req.Header.Del("Content-Type")
	}

	timestamp, nonce, rerr := newReplayProtectionFields()
	if rerr != nil {
		return 0, fmt.Errorf("build replay protection fields: %w", rerr)
	}
	req.Header.Set("X-Timestamp", timestamp)
	req.Header.Set("X-Nonce", nonce)
	req.Header.Set("X-Signature", signRequest(apiKey, req.Method, path, nonce, timestamp, bodyBytes))

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return 0, &ClientError{
			Code:    CodeNetworkError,
			Message: "http execute failed",
			Cause:   err,
		}
	}
	defer resp.Body.Close()

	respBody, err := readResponseBody(resp.Body)
	if err != nil {
		// Surface ClientError directly (preserves the Code) rather than
		// wrapping it; the branchdam FFI layer maps Code to its own.
		if ce, ok := err.(*ClientError); ok {
			return resp.StatusCode, ce
		}
		return resp.StatusCode, fmt.Errorf("read response failed: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body := respBody
		if len(body) > MaxErrorBodyBytes {
			body = body[:MaxErrorBodyBytes]
		}
		return resp.StatusCode, &ClientError{
			Code:       CodeForStatus(resp.StatusCode),
			Message:    fmt.Sprintf("http error %d", resp.StatusCode),
			StatusCode: resp.StatusCode,
			Body:       strings.TrimSpace(strings.ToValidUTF8(string(body), "")),
		}
	}

	if out != nil && len(respBody) > 0 {
		if err := json.Unmarshal(respBody, out); err != nil {
			return resp.StatusCode, fmt.Errorf("unmarshal response failed: %w", err)
		}
	}

	return resp.StatusCode, nil
}

func (c *Client) postJSON(ctx context.Context, path string, reqData any, respData any) error {
	_, err := c.postJSONWithKey(ctx, path, reqData, respData)
	return err
}

// postJSONWithKey is postJSON that also returns the API key the request
// was finally sent with (Handshake opens a sealed rotated key with it).
func (c *Client) postJSONWithKey(ctx context.Context, path string, reqData any, respData any) (string, error) {
	jsonData, err := json.Marshal(reqData)
	if err != nil {
		return "", fmt.Errorf("marshal request failed: %w", err)
	}
	return c.doRequestWithKey(ctx, http.MethodPost, path, true, jsonData, respData)
}

// CheckContent calls GET /api/v1/agent/check-content.
// fastHash is optional (empty string = skip fast pre-screen).
// Returns (result, nil) on success; (zero, err) on network failure.
// Callers treat any network error as "not found" (fail-open) so ingest
// proceeds rather than blocking on server unavailability.
func (c *Client) CheckContent(ctx context.Context, fastHash, fullHash string) (ContentCheckResult, error) {
	params := url.Values{}
	if fastHash != "" {
		params.Set("fastHash", fastHash)
	}
	if fullHash != "" {
		params.Set("fullHash", fullHash)
	}
	path := "/api/v1/agent/check-content"
	if q := params.Encode(); q != "" {
		path += "?" + q
	}
	var out ContentCheckResult
	if err := c.doRequest(ctx, http.MethodGet, path, nil, nil, &out); err != nil {
		return ContentCheckResult{}, err
	}
	return out, nil
}
