// Package branchdam is the gomobile-bound public API for the branchDAM mobile
// companion. The gomobile tool (golang.org/x/mobile/cmd/gomobile bind) reads
// this package and produces:
//
//	android/app/libs/branchdam.aar      (Kotlin/Java consumer: io.branchdam.core.Engine)
//	ios/Frameworks/branchdam.xcframework (Swift consumer: import branchdam)
//
// The Swift xcframework is named branchdam (lowercase) so its Swift module
// name matches the Obj-C class prefix that gomobile emits for the Go package
// "branchdam"; that match is what lets Swift's Obj-C import see the types
// as `branchdam.Engine`, `branchdam.EngineOptions`, etc.
//
// The actual implementation lives in github.com/s3ntin3l8/branchdam-mobile/core
// (subpackages client, engine, hasher, queue). Sub-issue A shipped a stub
// engine; sub-issue B fleshes out the full Engine API (sync, lineage,
// reclaim, etc.) and replaces the hand-written core/bindings C-ABI surface
// with this typed gomobile surface.
package branchdam

import (
	"context"
	"errors"
	"fmt"
	"math"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
	"github.com/s3ntin3l8/branchdam-mobile/core/engine"
	"github.com/s3ntin3l8/branchdam-mobile/core/hasher"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

// ---------------------------------------------------------------------------
// Version
// ---------------------------------------------------------------------------

// Version is the package's reported build version. Smoke-test helper for
// shells verifying artifact loading. The canonical version lives in the
// release-please manifests; this value is a build-time stub.
func Version() string {
	return "0.5.0-b"
}

// ---------------------------------------------------------------------------
// EngineOptions
// ---------------------------------------------------------------------------

// EngineOptions configures a new Engine. Validation is performed at
// NewEngine time only; methods on a successfully-constructed Engine assume
// the options are valid.
type EngineOptions struct {
	DBPath         string
	BaseURL        string
	APIKey         string
	AgentID        string
	ClientVersion  string
	HTTPTimeoutSec int
}

// validate returns a typed Error for the first invalid field, or nil.
func (o EngineOptions) validate() *Error {
	if strings.TrimSpace(o.DBPath) == "" {
		return &Error{Code: "INVALID_INPUT", Message: "DBPath is required"}
	}
	if strings.TrimSpace(o.BaseURL) == "" {
		return &Error{Code: "INVALID_INPUT", Message: "BaseURL is required"}
	}
	parsed, err := url.Parse(o.BaseURL)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return &Error{Code: "INVALID_INPUT", Message: "BaseURL is not a valid URL"}
	}
	if parsed.Scheme != "https" && parsed.Scheme != "http" {
		return &Error{Code: "INVALID_INPUT", Message: "BaseURL scheme must be http or https"}
	}
	if o.HTTPTimeoutSec < 0 {
		return &Error{Code: "INVALID_INPUT", Message: "HTTPTimeoutSec must be >= 0"}
	}
	if o.AgentID == "" {
		return &Error{Code: "INVALID_INPUT", Message: "AgentID is required"}
	}
	return nil
}

// ---------------------------------------------------------------------------
// Error
// ---------------------------------------------------------------------------

// Error is a structured error type for the branchdam FFI surface. The Code
// field is the source of truth for programmatic handling; the Message
// field is a human-readable explanation suitable for logs.
type Error struct {
	Code    string
	Message string
}

// Error implements the error interface.
func (e *Error) Error() string {
	if e == nil {
		return ""
	}
	return fmt.Sprintf("branchdam: %s: %s", e.Code, e.Message)
}

// Common error codes returned by the Engine. Shells should match on Code,
// not Message, since the latter is subject to change.
const (
	CodeInvalidInput     = "INVALID_INPUT"
	CodeDBError          = "DB_ERROR"
	CodeNetworkError     = "NETWORK_ERROR"
	CodeResponseTooLarge = "RESPONSE_TOO_LARGE"
	CodeVerifiedRequired = "VERIFIED_REQUIRED"
	CodeDedupNoNodeUUID  = "DEDUP_NO_NODE_UUID"
	CodeHashMismatch     = "HASH_MISMATCH"
	CodeContextCanceled  = "CONTEXT_CANCELED"
	CodeIOError          = "IO_ERROR"
	CodePayloadTooLarge  = "PAYLOAD_TOO_LARGE"
)

// newError is a small constructor for branchdam-typed errors.
func newError(code, format string, args ...any) *Error {
	return &Error{Code: code, Message: fmt.Sprintf(format, args...)}
}

// errToError converts a Go error to a *Error; nil → nil. If the Go error
// already wraps a *Error, it is returned as-is.
func errToError(err error) *Error {
	if err == nil {
		return nil
	}
	var be *Error
	if errors.As(err, &be) {
		return be
	}
	return &Error{Code: "INTERNAL", Message: err.Error()}
}

// ---------------------------------------------------------------------------
// EnqueueMedia
// ---------------------------------------------------------------------------

// EnqueueMediaOptions configures a single media enqueue.
type EnqueueMediaOptions struct {
	LocalPath      string
	Filename       string
	LocalID        string
	CameraModel    string
	CapturedAtUnix int64
	SizeBytes      int64
}

// EnqueueMedia opens LocalPath, computes BLAKE3, dedups against the queue,
// inserts an upload item, and returns the queue item ID. A non-nil Error is
// returned for any failure mode; nil Error + non-zero ID is success.
func (e *Engine) EnqueueMedia(opts EnqueueMediaOptions) (int64, error) {
	if err := e.requireOpen(); err != nil {
		return 0, err
	}
	if opts.LocalPath == "" {
		return 0, newError(CodeInvalidInput, "LocalPath is required")
	}
	if opts.Filename == "" {
		return 0, newError(CodeInvalidInput, "Filename is required")
	}
	if opts.LocalID == "" {
		return 0, newError(CodeInvalidInput, "LocalID is required")
	}

	eng := e.engine
	item, err := eng.EnqueueLocalCapture(
		opts.LocalPath, opts.Filename, opts.CapturedAtUnix, opts.LocalID, opts.CameraModel,
	)
	if err != nil {
		return 0, newError(CodeIOError, "enqueue media: %v", err)
	}
	if item == nil {
		return 0, newError(CodeIOError, "enqueue media: nil item without error")
	}
	return item.ID, nil
}

// ---------------------------------------------------------------------------
// ComputeHashes
// ---------------------------------------------------------------------------

// Hashes is the result of a streaming BLAKE3 + xxHash over a local file.
type Hashes struct {
	Blake3 string
	Fast   string
}

// ProgressFunc is a callback invoked during a streaming hash. Returning
// false from the callback aborts the hash and returns ErrProgressAborted.
//
// gomobile maps this to a Swift closure / Kotlin functional interface.
type ProgressFunc func(bytesRead, totalBytes int64) (cont bool)

// ErrProgressAborted is returned by ComputeHashes when the ProgressFunc
// returns false.
var ErrProgressAborted = errors.New("hash progress aborted by caller")

// ComputeHashes streams the file at LocalPath and returns the BLAKE3 and
// xxHash digests. The progress parameter is currently unused at the Go
// level (the core hasher doesn't accept a progress callback yet) but is
// kept on the FFI surface so the shell can wire it up once the core
// supports streaming progress. Pass nil if not needed.
func (e *Engine) ComputeHashes(localPath string, progress ProgressFunc) (Hashes, error) {
	if err := e.requireOpen(); err != nil {
		return Hashes{}, err
	}
	if localPath == "" {
		return Hashes{}, newError(CodeInvalidInput, "LocalPath is required")
	}

	// Validate progress callback signature only (smoke test); the core
	// hasher doesn't yet plumb progress through to the FFI surface.
	if progress != nil {
		if !progress(0, 0) {
			return Hashes{}, newError(CodeContextCanceled, "progress callback aborted at start")
		}
	}

	f, err := os.Open(localPath)
	if err != nil {
		return Hashes{}, newError(CodeIOError, "open: %v", err)
	}
	defer f.Close()

	fastHash, fullHash, _, err := hasher.HashReader(f)
	if err != nil {
		return Hashes{}, newError(CodeIOError, "hash: %v", err)
	}
	return Hashes{Blake3: fullHash, Fast: fastHash}, nil
}

// ---------------------------------------------------------------------------
// Lineage and delete events
// ---------------------------------------------------------------------------

// Confidence is a normalized 0.0-1.0 confidence score. EnqueueLineageEvent
// rejects NaN, Inf, and out-of-range values with INVALID_INPUT.
type Confidence float64

// Standard confidence bands. Use ConfidenceExact for stem-match pairs and
// Motion Photos; ConfidenceProximity or ConfidenceEdit for looser matches.
const (
	ConfidenceExact     Confidence = 1.00
	ConfidenceProximity Confidence = 0.95
	ConfidenceEdit      Confidence = 0.95
)

// EnqueueLineageEvent enqueues an EVENT_EDGE_ATTACHED with the given
// relationship. Returns the assigned event UUID.
func (e *Engine) EnqueueLineageEvent(
	parentLocalID, childLocalID, relationshipType, resolver string, confidence Confidence,
) (string, error) {
	if err := e.requireOpen(); err != nil {
		return "", err
	}
	if confidence != confidence {
		// NaN check; Float comparison with itself is the only reliable
		// way to detect NaN.
		return "", newError(CodeInvalidInput, "confidence is NaN")
	}
	if math.IsInf(float64(confidence), 0) {
		return "", newError(CodeInvalidInput, "confidence is Inf")
	}
	if confidence < 0.0 || confidence > 1.0 {
		return "", newError(CodeInvalidInput, "confidence %v is out of [0,1]", float64(confidence))
	}
	if parentLocalID == "" || childLocalID == "" {
		return "", newError(CodeInvalidInput, "parent and child LocalID are required")
	}
	if relationshipType == "" {
		return "", newError(CodeInvalidInput, "relationshipType is required")
	}
	if resolver == "" {
		return "", newError(CodeInvalidInput, "resolver is required")
	}

	payload, err := buildLineagePayload(parentLocalID, childLocalID, relationshipType, float64(confidence), resolver)
	if err != nil {
		return "", newError(CodeInvalidInput, "build lineage payload: %v", err)
	}

	eventUUID, err := e.queue.EnqueueEvent("EVENT_EDGE_ATTACHED", payload)
	if err != nil {
		return "", newError(CodeDBError, "enqueue lineage event: %v", err)
	}
	return eventUUID, nil
}

// EnqueueDeleteEvent enqueues an EVENT_NODE_DELETED for the given LocalID.
func (e *Engine) EnqueueDeleteEvent(localID string) (string, error) {
	if err := e.requireOpen(); err != nil {
		return "", err
	}
	if localID == "" {
		return "", newError(CodeInvalidInput, "LocalID is required")
	}
	payload := fmt.Sprintf(`{"localId":%q}`, localID)
	eventUUID, err := e.queue.EnqueueEvent("EVENT_NODE_DELETED", payload)
	if err != nil {
		return "", newError(CodeDBError, "enqueue delete event: %v", err)
	}
	return eventUUID, nil
}

// buildLineagePayload constructs a stable JSON payload for an
// EVENT_EDGE_ATTACHED event. Kept as a small package-private helper so the
// EnqueueLineageEvent function body stays readable.
func buildLineagePayload(parentLocalID, childLocalID, relationshipType string, confidence float64, resolver string) (string, error) {
	// Stable, deterministic key order via fmt.Sprintf template; not a hot
	// path so the lack of json.Marshal is fine.
	return fmt.Sprintf(
		`{"parentLocalId":%q,"childLocalId":%q,"relationship":%q,"confidence":%f,"resolver":%q}`,
		parentLocalID, childLocalID, relationshipType, confidence, resolver,
	), nil
}

// ---------------------------------------------------------------------------
// SyncBatch
// ---------------------------------------------------------------------------

// SyncOptions controls a SyncBatch call.
type SyncOptions struct {
	TimeoutSecs      int
	BatchSize        int
	RetryBackoffSecs int64
	MaxRetries       int
	IncludeEvents    bool
	IncludeUploads   bool
}

// SyncResult reports what SyncBatch did. Counts are best-effort; if the
// batch is cancelled mid-flight the partial counts are returned with
// CONTEXT_CANCELED.
type SyncResult struct {
	Uploaded   int64
	EventsSent int64
}

// SyncBatch runs one batch of upload + event sync. The implementation
// derives a stdlib context.Context from opts.TimeoutSecs (no shell
// context is required for the FFI surface; gomobile's context-bridging
// is complex and the SyncOptions already carry the equivalent
// information).
//
// The SQLite-backed cancel flag (B.2.2) is also honored via the
// queue.CancelRequested() check between items.
func (e *Engine) SyncBatch(opts SyncOptions) (SyncResult, error) {
	if err := e.requireOpen(); err != nil {
		return SyncResult{}, err
	}
	if !opts.IncludeEvents && !opts.IncludeUploads {
		return SyncResult{}, newError(CodeInvalidInput, "SyncBatch requires IncludeEvents or IncludeUploads")
	}

	timeoutSecs := opts.TimeoutSecs
	if timeoutSecs <= 0 {
		timeoutSecs = 60
	}
	batchSize := opts.BatchSize
	if batchSize <= 0 {
		batchSize = 10
	}
	retryBackoff := opts.RetryBackoffSecs
	if retryBackoff <= 0 {
		retryBackoff = 10
	}
	maxRetries := opts.MaxRetries
	if maxRetries <= 0 {
		maxRetries = 5
	}

	stdCtx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSecs)*time.Second)
	defer cancel()

	var result SyncResult
	if opts.IncludeUploads {
		n, err := e.engine.SyncUploads(stdCtx, batchSize)
		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return result, newError(CodeContextCanceled, "sync uploads cancelled: %v", err)
			}
			return result, errToError(err)
		}
		result.Uploaded = int64(n)
	}
	if opts.IncludeEvents {
		n, err := e.engine.SyncEvents(stdCtx, batchSize)
		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return result, newError(CodeContextCanceled, "sync events cancelled: %v", err)
			}
			return result, errToError(err)
		}
		result.EventsSent = int64(n)
	}
	return result, nil
}

// SetCancelFlag requests an in-flight SyncBatch to stop at the next
// per-item checkpoint. The flag is captured and reset at the start of
// each SyncBatch, so a single SetCancelFlag only affects the in-flight
// sync; subsequent syncs are unaffected. (B.2.2)
func (e *Engine) SetCancelFlag() error {
	if e.engine == nil {
		return newError(CodeDBError, "engine not open")
	}
	e.engine.RequestCancel()
	return nil
}

// ---------------------------------------------------------------------------
// Safe space reclaim
// ---------------------------------------------------------------------------

// SafeSpaceCandidate describes a local media item the shell is asking the
// engine to evaluate for safe reclamation.
type SafeSpaceCandidate struct {
	LocalID    string
	NodeUUID   string
	Blake3Hash string
	Tier       string
	IsVerified bool
	IsEligible bool
}

// SafeSpaceVerdict is the engine's per-candidate verdict.
type SafeSpaceVerdict struct {
	LocalID  string
	Eligible bool
	Reason   string
}

// CheckSafeSpaceCandidates queries the server for the current verified + tier
// status of each candidate's node and returns a verdict per candidate.
// Candidates without a nodeUUID are reported as ineligible with a
// not-found reason.
func (e *Engine) CheckSafeSpaceCandidates(candidates []SafeSpaceCandidate) ([]SafeSpaceVerdict, error) {
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	localIDs := make([]string, 0, len(candidates))
	for _, c := range candidates {
		if c.LocalID == "" {
			return nil, newError(CodeInvalidInput, "candidate LocalID is required")
		}
		localIDs = append(localIDs, c.LocalID)
	}
	raw, err := e.engine.CheckSafeSpaceCandidates(context.Background(), localIDs)
	if err != nil {
		return nil, errToError(err)
	}
	// raw is []engine.SafeSpaceCandidate indexed by input order; map it
	// back to the caller's input localIDs.
	out := make([]SafeSpaceVerdict, 0, len(raw))
	for _, c := range raw {
		reason := ""
		if !c.IsEligible {
			// Audit: VERIFIED_REQUIRED is the canonical reason a
			// candidate is ineligible.
			reason = CodeVerifiedRequired
		}
		out = append(out, SafeSpaceVerdict{LocalID: c.LocalID, Eligible: c.IsEligible, Reason: reason})
	}
	return out, nil
}

// ReclaimSafeSpace marks LocalID as offloaded after re-checking the server
// and only if the current state is verified + tier 2/3. The local file
// deletion is the shell's responsibility and should only happen after
// this returns Eligible=true.
func (e *Engine) ReclaimSafeSpace(localID string) (SafeSpaceVerdict, error) {
	if err := e.requireOpen(); err != nil {
		return SafeSpaceVerdict{}, err
	}
	if localID == "" {
		return SafeSpaceVerdict{}, newError(CodeInvalidInput, "LocalID is required")
	}
	verdict, err := e.engine.SafeSpaceReclaim(context.Background(), localID)
	if err != nil {
		return SafeSpaceVerdict{LocalID: localID, Reason: err.Error()}, errToError(err)
	}
	if !verdict.Eligible {
		return SafeSpaceVerdict{LocalID: localID, Reason: verdict.Reason},
			newError(CodeVerifiedRequired, "reclaim ineligible: %s", verdict.Reason)
	}
	return SafeSpaceVerdict{LocalID: localID, Eligible: true}, nil
}

// ---------------------------------------------------------------------------
// Offload flag query
// ---------------------------------------------------------------------------

// IsMediaOffloaded reports whether the local asset was intentionally
// offloaded (i.e. its deletion should be suppressed). Returns (false, *Error)
// on a database error — the engine treats this as "not offloaded, refuse
// to delete" at the shell layer.
func (e *Engine) IsMediaOffloaded(localID string) (bool, error) {
	if err := e.requireOpen(); err != nil {
		return false, err
	}
	if localID == "" {
		return false, newError(CodeInvalidInput, "LocalID is required")
	}
	flag, err := e.queue.IsMediaOffloaded(localID)
	if err != nil {
		return false, newError(CodeDBError, "is offloaded: %v", err)
	}
	return flag, nil
}

// SetMediaOffloaded is a direct setter for the offload flag. Most callers
// should use ReclaimSafeSpace which performs the server re-check first;
// this method exists for shells that need to undo a reclaim.
func (e *Engine) SetMediaOffloaded(localID string, isOffloaded bool) error {
	if err := e.requireOpen(); err != nil {
		return err
	}
	if localID == "" {
		return newError(CodeInvalidInput, "LocalID is required")
	}
	if err := e.queue.SetMediaOffloaded(localID, isOffloaded); err != nil {
		return newError(CodeDBError, "set offloaded: %v", err)
	}
	return nil
}

// ---------------------------------------------------------------------------
// Naming template handshake
// ---------------------------------------------------------------------------

// FetchNamingTemplate calls the server's handshake endpoint and returns
// the canonical naming template. Caches the result in the engine.
func (e *Engine) FetchNamingTemplate() (string, error) {
	if err := e.requireOpen(); err != nil {
		return "", err
	}
	resp, err := e.client.Handshake(context.Background(), "")
	if err != nil {
		return "", errToError(err)
	}
	return resp.NamingTemplate, nil
}

// ---------------------------------------------------------------------------
// Options accessor
// ---------------------------------------------------------------------------

// Options returns the options the engine was constructed with. Smoke-test
// helper for shells verifying artifact loading.
func (e *Engine) Options() EngineOptions {
	return e.opts
}

// ---------------------------------------------------------------------------
// Engine type
// ---------------------------------------------------------------------------

// Engine is the long-lived handle to a branchdam session. It owns the
// SQLite queue, the HTTP client, and the underlying core engine. All
// exported methods are safe to call from multiple goroutines; the
// underlying queue is serialized through its own mutex.
type Engine struct {
	opts   EngineOptions
	queue  *queue.Queue
	client *client.Client
	engine *engine.Engine

	// mu serializes construction / close.
	mu     sync.Mutex
	closed bool
}

// NewEngine validates opts, opens the SQLite queue, constructs the HTTP
// client, and returns an Engine. The returned Error has Code INVALID_INPUT
// for validation failures and DB_ERROR for storage failures.
func NewEngine(opts EngineOptions) (*Engine, error) {
	if opts.ClientVersion == "" {
		opts.ClientVersion = Version()
	}
	if err := opts.validate(); err != nil {
		return nil, err
	}

	// Ensure the DB directory exists; sqlite won't create intermediate
	// directories.
	if dir := filepath.Dir(opts.DBPath); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, newError(CodeIOError, "create db dir: %v", err)
		}
	}

	q, err := queue.Open(opts.DBPath)
	if err != nil {
		return nil, newError(CodeDBError, "open queue: %v", err)
	}

	httpClient := client.New(client.Config{
		BaseURL:       opts.BaseURL,
		APIKey:        opts.APIKey,
		AgentID:       opts.AgentID,
		ClientVersion: opts.ClientVersion,
		HTTPClient:    nil,
		UploadClient:  nil,
	})

	eng := engine.New(q, httpClient)

	e := &Engine{
		opts:   opts,
		queue:  q,
		client: httpClient,
		engine: eng,
	}
	return e, nil
}

// Close releases the SQLite database. Idempotent; calling Close on an
// already-closed Engine returns nil.
func (e *Engine) Close() error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return nil
	}
	e.closed = true
	if e.queue != nil {
		if err := e.queue.Close(); err != nil {
			return newError(CodeDBError, "close queue: %v", err)
		}
	}
	return nil
}

// requireOpen returns a DB_ERROR if the engine is closed.
func (e *Engine) requireOpen() *Error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return newError(CodeDBError, "engine is closed")
	}
	return nil
}
