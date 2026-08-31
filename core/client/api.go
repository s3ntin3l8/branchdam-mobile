package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
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

// SendTelemetry dispatches mobile battery/storage telemetry via POST /api/v1/mobile/telemetry.
func (c *Client) SendTelemetry(ctx context.Context, telemetry MobileTelemetry) error {
	var resp map[string]any
	if err := c.postJSON(ctx, "/api/v1/mobile/telemetry", telemetry, &resp); err != nil {
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

func (c *Client) postJSON(ctx context.Context, path string, reqData any, respData any) error {
	jsonData, err := json.Marshal(reqData)
	if err != nil {
		return fmt.Errorf("marshal request failed: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+path, bytes.NewReader(jsonData))
	if err != nil {
		return fmt.Errorf("create request failed: %w", err)
	}
	c.setHeaders(req)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return &ClientError{
			Code:    CodeNetworkError,
			Message: "http execute failed",
			Cause:   err,
		}
	}
	defer resp.Body.Close()

	bodyBytes, err := readResponseBody(resp.Body)
	if err != nil {
		// Surface ClientError directly (preserves the Code) rather than
		// wrapping it; the branchdam FFI layer maps Code to its own.
		var ce *ClientError
		if errors.As(err, &ce) {
			return ce
		}
		return fmt.Errorf("read response failed: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &ClientError{
			Code:    CodeNetworkError,
			Message: fmt.Sprintf("http error %d: %s", resp.StatusCode, string(bodyBytes)),
		}
	}

	if respData != nil && len(bodyBytes) > 0 {
		if err := json.Unmarshal(bodyBytes, respData); err != nil {
			return fmt.Errorf("unmarshal response failed: %w", err)
		}
	}

	return nil
}
