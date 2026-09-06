package client

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Config defines the client configuration.
// HTTPClient and UploadClient are optional overrides for testing or power-users.
// When nil, the default clients use TLS 1.2 minimum (MinVersion: tls.VersionTLS12).
// If injected, the caller MUST ensure the client enforces TLS 1.2+; the
// injected client is used as-is without validation.
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
			Transport: &http.Transport{
				TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12},
			},
		}
	}
	uploadClient := cfg.UploadClient
	if uploadClient == nil {
		// Upload transfers rely on request context for deadline/cancellation
		// rather than a static 30s client-level timeout that would cut off large media streams.
		uploadClient = &http.Client{
			Timeout: 0,
			Transport: &http.Transport{
				TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12},
			},
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

func (c *Client) AgentID() string {
	return c.agentID
}

func (c *Client) setHeaders(req *http.Request) {
	// T2-6: send the API key only in the X-API-Key header. The
	// Authorization header is intentionally NOT set here; it is
	// reserved for future short-lived bearer tokens issued by the
	// handshake endpoint. Previously both X-API-Key and
	// "Authorization: Bearer <key>" were set, which doubled the
	// key's surface area in any log line, proxy log, or error
	// message.
	if c.apiKey != "" {
		req.Header.Set("X-API-Key", c.apiKey)
	}
	req.Header.Set("User-Agent", fmt.Sprintf("branchdam-mobile/%s (%s)", c.clientVersion, c.agentID))
	req.Header.Set("Content-Type", "application/json")
}

// signRequest computes the HMAC-SHA256 signature over the canonical string
// "method\npath\nnonce\ntimestamp\n" followed by the raw request body bytes,
// keyed by the API key. For GET requests, path includes the full query string.
func (c *Client) signRequest(method, path, nonce, timestamp string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(c.apiKey))
	mac.Write([]byte(method + "\n" + path + "\n" + nonce + "\n" + timestamp + "\n"))
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

// newReplayProtectionFields returns a fresh (timestamp, nonce) pair for
// a single outgoing request. Timestamp is UnixNano as a base-10 string.
// Nonce is 16 random bytes hex-encoded. Returns error on crypto/rand failure
// rather than falling back to a deterministic nonce.
func newReplayProtectionFields() (timestamp, nonce string, err error) {
	ts := strconv.FormatInt(time.Now().UnixNano(), 10)
	var nonceBytes [16]byte
	if _, rerr := rand.Read(nonceBytes[:]); rerr != nil {
		return "", "", fmt.Errorf("branchdam: read crypto/rand for replay nonce: %w", rerr)
	}
	return ts, hex.EncodeToString(nonceBytes[:]), nil
}
