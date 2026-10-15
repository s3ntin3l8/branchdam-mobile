package client

import "errors"

// ClientError is a structured error type for the core client. The Code
// field is the source of truth for programmatic handling; the branchdam
// FFI surface maps these to branchdam.Error codes.
type ClientError struct {
	Code    string
	Message string
	Cause   error
	// StatusCode is the HTTP status of a non-2xx response; 0 when the
	// request never produced a response (transport failure) or the
	// error is not HTTP-related.
	StatusCode int
	// Body is the start of a non-2xx response body (at most
	// MaxErrorBodyBytes), kept so callers can record why the server
	// rejected a request. Like Cause, it is not part of Error().
	Body string
}

// Error implements the error interface. The body/cause is intentionally
// omitted from the message to prevent API key re-leakage via logs (S-7).
// The Cause is still accessible via Unwrap for errors.Is/errors.As.
func (e *ClientError) Error() string {
	if e == nil {
		return ""
	}
	return "branchdam client: " + e.Code + ": " + e.Message
}

// Unwrap exposes the underlying cause for errors.Is / errors.As.
func (e *ClientError) Unwrap() error {
	return e.Cause
}

// Client error codes. The branchdam FFI surface maps these to
// branchdam.Error codes with the same string value.
const (
	CodeResponseTooLarge = "RESPONSE_TOO_LARGE"
	CodeDedupNoNodeUUID  = "DEDUP_NO_NODE_UUID"
	CodeHashMismatch     = "HASH_MISMATCH"
	CodeNetworkError     = "NETWORK_ERROR"
	CodeIOError          = "IO_ERROR"
	CodeInvalidInput     = "INVALID_INPUT"
	// CodeAuthFailed: the server answered 401 or 403. The device's
	// credentials were rejected (key revoked or rotated, pairing removed,
	// agent ID mismatch); retrying will not help until it is re-paired.
	CodeAuthFailed = "AUTH_FAILED"
)

// CodeForStatus returns the ClientError code for a non-2xx HTTP status:
// CodeAuthFailed for 401 and 403, CodeNetworkError for everything else.
func CodeForStatus(status int) string {
	if IsAuthStatus(status) {
		return CodeAuthFailed
	}
	return CodeNetworkError
}

// IsAuthStatus reports whether status means the server rejected the
// device's credentials (401 or 403).
func IsAuthStatus(status int) bool {
	return status == 401 || status == 403
}

// IsAuthError reports whether err is (or wraps) a ClientError for a
// credential rejection.
func IsAuthError(err error) bool {
	var ce *ClientError
	return errors.As(err, &ce) && ce.Code == CodeAuthFailed
}

// MaxErrorBodyBytes caps how much of a non-2xx response body is kept on
// ClientError.Body.
const MaxErrorBodyBytes = 512

// IsRetryableStatus reports whether a request that failed with HTTP status
// code should be retried as-is. 5xx, 401 (credentials may be rotated or
// re-paired), 403 (pairing may be restored), 408 and 429 are transient;
// every other 4xx means the request itself is unacceptable and resending it
// unchanged will fail the same way.
func IsRetryableStatus(code int) bool {
	switch {
	case code >= 500:
		return true
	case code == 401, code == 403, code == 408, code == 429:
		return true
	case code >= 400:
		return false
	}
	return true
}

// MaxResponseBodyBytes caps the size of any HTTP response body the
// client will buffer. Above this, the response is treated as
// RESPONSE_TOO_LARGE rather than OOM-ing the shell. 1 MiB is generous
// for the JSON / metadata responses the branchdam client handles; the
// upload path streams the request body (not the response).
const MaxResponseBodyBytes = 1 << 20 // 1 MiB
