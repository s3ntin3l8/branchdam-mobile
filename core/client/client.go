package client

import (
	"fmt"
	"net/http"
	"strings"
	"time"
)

type Config struct {
	BaseURL       string
	APIKey        string
	AgentID       string
	ClientVersion string
	HTTPClient    *http.Client
	UploadClient  *http.Client
}

type Client struct {
	baseURL       string
	apiKey        string
	agentID       string
	clientVersion string
	httpClient    *http.Client
	uploadClient  *http.Client
}

func New(cfg Config) *Client {
	baseURL := strings.TrimRight(cfg.BaseURL, "/")
	httpClient := cfg.HTTPClient
	if httpClient == nil {
		httpClient = &http.Client{
			Timeout: 30 * time.Second,
		}
	}
	uploadClient := cfg.UploadClient
	if uploadClient == nil {
		// Upload transfers rely on request context for deadline/cancellation
		// rather than a static 30s client-level timeout that would cut off large media streams.
		uploadClient = &http.Client{
			Timeout: 0,
		}
	}
	version := cfg.ClientVersion
	if version == "" {
		version = "0.1.0"
	}
	return &Client{
		baseURL:       baseURL,
		apiKey:        cfg.APIKey,
		agentID:       cfg.AgentID,
		clientVersion: version,
		httpClient:    httpClient,
		uploadClient:  uploadClient,
	}
}

func (c *Client) setHeaders(req *http.Request) {
	if c.apiKey != "" {
		req.Header.Set("X-API-Key", c.apiKey)
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
	req.Header.Set("User-Agent", fmt.Sprintf("branchdam-mobile/%s (%s)", c.clientVersion, c.agentID))
	req.Header.Set("Content-Type", "application/json")
}
