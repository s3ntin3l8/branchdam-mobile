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
	"time"
)

// Handshake performs agent handshake against POST /api/v1/agent/handshake.
func (c *Client) Handshake(ctx context.Context, lastProcessedEventUUID string) (*HandshakeResponse, error) {
	reqBody := HandshakeRequest{
		AgentID:                c.agentID,
		ClientVersion:          c.clientVersion,
		LastProcessedEventUUID: lastProcessedEventUUID,
	}

	var resp HandshakeResponse
	if err := c.postJSON(ctx, "/api/v1/agent/handshake", reqBody, &resp); err != nil {
		return nil, wrapCallError("handshake failed", err)
	}
	return &resp, nil
}

// SubmitEvent dispatches an agent lifecycle or lineage event to POST /api/v1/agent/events.
func (c *Client) SubmitEvent(ctx context.Context, eventType, payloadJSON string) (*AgentEventResponse, error) {
	reqBody := AgentEventRequest{
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

// GetNodeStatuses queries verification state and tier for nodes via POST /api/v1/agent/node-status.
func (c *Client) GetNodeStatuses(ctx context.Context, nodeUUIDs []string) ([]NodeStatusItem, error) {
	reqBody := NodeStatusRequest{
		NodeUUIDs: nodeUUIDs,
	}

	var resp NodeStatusResponse
	if err := c.postJSON(ctx, "/api/v1/agent/node-status", reqBody, &resp); err != nil {
		return nil, wrapCallError("get node status failed", err)
	}
	return resp.Statuses, nil
}

// SendTelemetry dispatches mobile storage telemetry via POST /api/v1/agent/telemetry.
func (c *Client) SendTelemetry(ctx context.Context, telemetry MobileTelemetry) error {
	agentID := c.agentID
	if agentID == "" {
		agentID = telemetry.DeviceID
	}
	clientVersion := telemetry.ClientVersion
	if clientVersion == "" {
		clientVersion = c.clientVersion
	}
	ts := telemetry.TimestampUnix
	if ts <= 0 {
		ts = time.Now().Unix()
	}

	payload := TelemetryInput{
		AgentID:       agentID,
		ClientVersion: clientVersion,
		TimestampUnix: ts,
		ScratchStorage: ScratchStorageDTO{
			MountPath:     DefaultMobileMountPath,
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
			Code:    ce.Code,
			Message: prefix + ": " + ce.Message,
			Cause:   ce.Cause,
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
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, bodyReader)
	if err != nil {
		return fmt.Errorf("create request failed: %w", err)
	}
	c.setHeaders(req)
	if method == http.MethodGet {
		req.Header.Del("Content-Type")
	}

	timestamp, nonce, rerr := newReplayProtectionFields()
	if rerr != nil {
		return fmt.Errorf("build replay protection fields: %w", rerr)
	}
	req.Header.Set("X-Timestamp", timestamp)
	req.Header.Set("X-Nonce", nonce)
	req.Header.Set("X-Signature", c.signRequest(req.Method, path, nonce, timestamp, bodyBytes))

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return &ClientError{
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
			return ce
		}
		return fmt.Errorf("read response failed: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &ClientError{
			Code:    CodeNetworkError,
			Message: fmt.Sprintf("http error %d", resp.StatusCode),
		}
	}

	if out != nil && len(respBody) > 0 {
		if err := json.Unmarshal(respBody, out); err != nil {
			return fmt.Errorf("unmarshal response failed: %w", err)
		}
	}

	return nil
}

func (c *Client) postJSON(ctx context.Context, path string, reqData any, respData any) error {
	jsonData, err := json.Marshal(reqData)
	if err != nil {
		return fmt.Errorf("marshal request failed: %w", err)
	}
	return c.doRequest(ctx, http.MethodPost, path, bytes.NewReader(jsonData), jsonData, respData)
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
