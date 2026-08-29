package client

import (
	"bytes"
	"context"
	"encoding/json"
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
		return nil, fmt.Errorf("handshake failed: %w", err)
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
		return nil, fmt.Errorf("submit event failed: %w", err)
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
		return nil, fmt.Errorf("get node status failed: %w", err)
	}
	return resp.Statuses, nil
}

// SendTelemetry dispatches mobile battery/storage telemetry via POST /api/v1/mobile/telemetry.
func (c *Client) SendTelemetry(ctx context.Context, telemetry MobileTelemetry) error {
	var resp map[string]any
	if err := c.postJSON(ctx, "/api/v1/mobile/telemetry", telemetry, &resp); err != nil {
		return fmt.Errorf("send telemetry failed: %w", err)
	}
	return nil
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
		return fmt.Errorf("http execute failed: %w", err)
	}
	defer resp.Body.Close()

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("read response failed: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("http error %d: %s", resp.StatusCode, string(bodyBytes))
	}

	if respData != nil && len(bodyBytes) > 0 {
		if err := json.Unmarshal(bodyBytes, respData); err != nil {
			return fmt.Errorf("unmarshal response failed: %w", err)
		}
	}

	return nil
}
