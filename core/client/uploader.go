package client

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
)

type ProgressCallback func(bytesSent int64, totalBytes int64)

type UploadOptions struct {
	CameraModel    string
	FastHash       string
	Blake3Hash     string
	CapturedAtUnix int64
	ProgressFn     ProgressCallback
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

// UploadStream streams file bytes directly to POST /api/v1/agent/upload using the stream-optimized client.
func (c *Client) UploadStream(ctx context.Context, r io.Reader, sizeBytes int64, filename string, opts UploadOptions) (*UploadResponse, error) {
	var bodyReader io.Reader = r
	if opts.ProgressFn != nil {
		bodyReader = &progressReader{
			reader:     r,
			totalBytes: sizeBytes,
			progressFn: opts.ProgressFn,
		}
	}

	reqURL := c.baseURL + "/api/v1/agent/upload"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, reqURL, bodyReader)
	if err != nil {
		return nil, fmt.Errorf("failed to create upload request: %w", err)
	}

	c.setHeaders(req)
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("X-Filename", filename)
	req.ContentLength = sizeBytes

	if opts.CameraModel != "" {
		req.Header.Set("X-Camera-Model", opts.CameraModel)
	}
	if opts.Blake3Hash != "" {
		req.Header.Set("X-Blake3-Hash", opts.Blake3Hash)
	}
	if opts.FastHash != "" {
		req.Header.Set("X-Fast-Hash", opts.FastHash)
	}
	if opts.CapturedAtUnix > 0 {
		req.Header.Set("X-Capture-Timestamp", strconv.FormatInt(opts.CapturedAtUnix, 10))
	}

	resp, err := c.uploadClient.Do(req)
	if err != nil {
		return nil, &ClientError{
			Code:    CodeNetworkError,
			Message: "upload transfer failed",
			Cause:   err,
		}
	}
	defer resp.Body.Close()

	// B.2.4: bound the response body. 1 MiB is generous for the
	// metadata responses /api/v1/agent/upload returns.
	respBody, err := readResponseBody(resp.Body)
	if err != nil {
		var ce *ClientError
		if errors.As(err, &ce) {
			return nil, ce
		}
		return nil, fmt.Errorf("failed to read upload response: %w", err)
	}

	// B.2.6: 409 handling. The audit found the pre-B behaviour
	// treated 409 as a soft dedup even with an empty NodeUUID, which
	// was the source of "asset permanently orphaned" reports. Now:
	//  - 409 + parseable NodeUUID  -> DedupError (existing soft case)
	//  - 409 + empty NodeUUID      -> DEDUP_NO_NODE_UUID (new hard error)
	if resp.StatusCode == http.StatusConflict {
		var dedupResp UploadResponse
		_ = json.Unmarshal(respBody, &dedupResp)
		if dedupResp.NodeUUID == "" {
			return nil, &ClientError{
				Code:    CodeDedupNoNodeUUID,
				Message: "server returned 409 without a parseable nodeUuid",
			}
		}
		return nil, &DedupError{
			DedupResponse: DedupResponse{
				NodeUUID: dedupResp.NodeUUID,
				FilePath: dedupResp.FilePath,
			},
		}
	}

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		return nil, &ClientError{
			Code:    CodeNetworkError,
			Message: fmt.Sprintf("upload rejected with status %d: %s", resp.StatusCode, string(respBody)),
		}
	}

	var uploadResp UploadResponse
	if err := json.Unmarshal(respBody, &uploadResp); err != nil {
		return nil, fmt.Errorf("failed to decode upload response: %w", err)
	}

	// B.2.6: server-claimed BLAKE3 verification. If the server
	// returns a hash and it doesn't match what we sent, refuse
	// the response.
	if uploadResp.Blake3Hash != "" && opts.Blake3Hash != "" && uploadResp.Blake3Hash != opts.Blake3Hash {
		return nil, &ClientError{
			Code:    CodeHashMismatch,
			Message: fmt.Sprintf("server returned blake3 %q, expected %q", uploadResp.Blake3Hash, opts.Blake3Hash),
		}
	}

	if resp.Header.Get("X-Dedup") == "true" || resp.Header.Get("X-Dedup") == "1" || uploadResp.Status == "DEDUPLICATED" {
		uploadResp.IsDedup = true
	}

	return &uploadResp, nil
}
