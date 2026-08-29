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
}

type Client struct {
	baseURL       string
	apiKey        string
	agentID       string
	clientVersion string
	httpClient    *http.Client
}

func New(cfg Config) *Client {
	baseURL := strings.TrimRight(cfg.BaseURL, "/")
	httpClient := cfg.HTTPClient
	if httpClient == nil {
		httpClient = &http.Client{
			Timeout: 30 * time.Second,
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
