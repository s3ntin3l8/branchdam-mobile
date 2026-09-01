package client

// ClientError is a structured error type for the core client. The Code
// field is the source of truth for programmatic handling; the branchdam
// FFI surface maps these to branchdam.Error codes.
type ClientError struct {
	Code    string
	Message string
	Cause   error
}

// Error implements the error interface.
func (e *ClientError) Error() string {
	if e == nil {
		return ""
	}
	if e.Cause != nil {
		return "branchdam client: " + e.Code + ": " + e.Message + ": " + e.Cause.Error()
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
)

// MaxResponseBodyBytes caps the size of any HTTP response body the
// client will buffer. Above this, the response is treated as
// RESPONSE_TOO_LARGE rather than OOM-ing the shell. 1 MiB is generous
// for the JSON / metadata responses the branchdam client handles; the
// upload path streams the request body (not the response).
const MaxResponseBodyBytes = 1 << 20 // 1 MiB
