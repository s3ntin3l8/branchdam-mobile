package client

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
)

type ProgressCallback func(bytesSent int64, totalBytes int64)

type UploadOptions struct {
	FastHash           string
	Blake3Hash         string
	CapturedAtUnix     int64
	ProgressFn         ProgressCallback
}

type progressReader struct {
	reader     io.Reader
	totalBytes int64
	bytesSent  int64
	progressFn ProgressCallback
}

func (pr *progressReader) Read(p []byte) (int, error) {
	n, err := pr.reader.Read(p)
	if n > 0 {
		pr.bytesSent += int64(n)
		if pr.progressFn != nil {
			pr.progressFn(pr.bytesSent, pr.totalBytes)
		}
	}
	return n, err
}

// UploadStream streams file bytes directly to POST /api/v1/staging/upload.
func (c *Client) UploadStream(ctx context.Context, r io.Reader, sizeBytes int64, filename string, opts UploadOptions) (*UploadResponse, error) {
	var bodyReader io.Reader = r
	if opts.ProgressFn != nil {
		bodyReader = &progressReader{
			reader:     r,
			totalBytes: sizeBytes,
			progressFn: opts.ProgressFn,
		}
	}

	reqURL := c.baseURL + "/api/v1/staging/upload"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, reqURL, bodyReader)
	if err != nil {
		return nil, fmt.Errorf("failed to create upload request: %w", err)
	}

	c.setHeaders(req)
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("X-Filename", filename)
	req.ContentLength = sizeBytes

	if opts.Blake3Hash != "" {
		req.Header.Set("X-Blake3-Hash", opts.Blake3Hash)
	}
	if opts.FastHash != "" {
		req.Header.Set("X-Fast-Hash", opts.FastHash)
	}
	if opts.CapturedAtUnix > 0 {
		req.Header.Set("X-Capture-Timestamp", strconv.FormatInt(opts.CapturedAtUnix, 10))
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("upload transfer failed: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read upload response: %w", err)
	}

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		return nil, fmt.Errorf("upload rejected with status %d: %s", resp.StatusCode, string(respBody))
	}

	var uploadResp UploadResponse
	if err := json.Unmarshal(respBody, &uploadResp); err != nil {
		return nil, fmt.Errorf("failed to decode upload response: %w", err)
	}

	return &uploadResp, nil
}
