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
	"encoding/json"
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

// version is the release this source tree builds. release-please bumps the
// annotated line together with .release-please-manifest.json (see
// release-please-config.json extra-files), and TestVersionMatchesReleaseManifest
// keeps the two in step.
const version = "0.7.13" // x-release-please-version

// Version is the package's reported build version: the default client
// version sent to the server when the shell doesn't pass its own, and a
// smoke-test helper for shells verifying artifact loading.
func Version() string {
	return version
}

// ---------------------------------------------------------------------------
// EngineOptions
// ---------------------------------------------------------------------------

// EngineOptions configures a new Engine. Validation is performed at
// NewEngine time only; methods on a successfully-constructed Engine assume
// the options are valid.
type EngineOptions struct {
	DBPath            string
	BaseURL           string
	APIKey            string
	AgentID           string
	ClientVersion     string
	HTTPTimeoutSec    int
	DevCleartextHosts []string // debug-only: hosts allowed over HTTP
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
	if parsed.Scheme == "http" && !isHostInCleartextAllowlist(parsed.Hostname(), o.DevCleartextHosts) {
		return &Error{Code: "INVALID_INPUT", Message: "HTTP requires HTTPS or explicit dev allowlist"}
	}
	if o.HTTPTimeoutSec < 0 {
		return &Error{Code: "INVALID_INPUT", Message: "HTTPTimeoutSec must be >= 0"}
	}
	if o.AgentID == "" {
		return &Error{Code: "INVALID_INPUT", Message: "AgentID is required"}
	}
	return nil
}

// isHostInCleartextAllowlist returns true if host appears in the
// DevCleartextHosts list (exact match). Used to gate HTTP in debug
// builds while requiring HTTPS in production (empty list).
func isHostInCleartextAllowlist(host string, allowlist []string) bool {
	for _, h := range allowlist {
		if strings.EqualFold(h, host) {
			return true
		}
	}
	return false
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
	// CodeAuthFailed: the server rejected this device's credentials (HTTP
	// 401 or 403: key revoked or rotated, pairing removed, agent ID
	// mismatch). Retrying cannot help; the shell should ask the user to
	// re-pair. NETWORK_ERROR stays reserved for transport failures and
	// other HTTP errors (5xx and the like).
	CodeAuthFailed = "AUTH_FAILED"
	// CodeReviewRequired: EnqueueLineageEvent was given a lineage pair
	// below confidence 1.00. Such a guess is never sent on the device's
	// own say-so; the user decides it in the review screen
	// (ConfirmLineageEdge / RejectLineageEdge).
	CodeReviewRequired = "REVIEW_REQUIRED"
)

// newError is a small constructor for branchdam-typed errors.
func newError(code, format string, args ...any) *Error {
	return &Error{Code: code, Message: fmt.Sprintf(format, args...)}
}

// errToError converts a Go error to a *Error; nil → nil. If the Go error
// already wraps a *Error, it is returned as-is; one that wraps a
// client.ClientError keeps that error's code (the client codes share the
// FFI codes' names), so a 401/403 reaches the shell as AUTH_FAILED and a
// transport failure or 5xx as NETWORK_ERROR. Anything else is INTERNAL.
func errToError(err error) *Error {
	if err == nil {
		return nil
	}
	var be *Error
	if errors.As(err, &be) {
		return be
	}
	var ce *client.ClientError
	if errors.As(err, &ce) && ce.Code != "" {
		return &Error{Code: ce.Code, Message: err.Error()}
	}
	return &Error{Code: "INTERNAL", Message: err.Error()}
}

// reclaimErrorToBranchdamError maps a non-nil error from the engine's
// SafeSpaceReclaim to a *Error with a code that distinguishes
// transient infrastructure failures from genuine ineligible verdicts.
//
//   - Network/IO errors (client.ClientError with NETWORK_ERROR or
//     IO_ERROR) → INTERNAL (transient; shell should retry)
//   - Credential rejection (client.ClientError with AUTH_FAILED) →
//     AUTH_FAILED (the device must be re-paired; not a verdict)
//   - engine.ErrLocalFlagSetFailed (typed sentinel from
//     core/engine) → DB_ERROR (transient; the local flag write
//     failed, shell should retry)
//   - engine.ErrLocalReadFailed (typed sentinel from
//     core/engine) → DB_ERROR (transient; the local state lookup
//     failed, shell should retry — NOT eligible/ineligible)
//   - Everything else → VERIFIED_REQUIRED (ineligible; the node is
//     not verified, on the wrong tier, or unknown to the server)
func reclaimErrorToBranchdamError(err error) *Error {
	// Already a *Error? Pass through (defensive — shouldn't happen
	// from the engine, but cheap to check).
	var be *Error
	if errors.As(err, &be) {
		return be
	}

	// Network or IO failure from the HTTP client.
	var ce *client.ClientError
	if errors.As(err, &ce) {
		if ce.Code == client.CodeNetworkError || ce.Code == client.CodeIOError {
			return newError("INTERNAL", "reclaim transient: %s", err.Error())
		}
		if ce.Code == client.CodeAuthFailed {
			return newError(CodeAuthFailed, "reclaim: %s", err.Error())
		}
	}

	// Local DB failure when writing the offloaded flag. The engine
	// wraps the queue error with engine.ErrLocalFlagSetFailed; we
	// match on the typed sentinel, not the message string.
	if errors.Is(err, engine.ErrLocalFlagSetFailed) {
		return newError(CodeDBError, "reclaim transient: %s", err.Error())
	}

	// Local DB failure when reading the state (SQLITE_BUSY,
	// corruption, etc.). The engine wraps with ErrLocalReadFailed;
	// this is a transient failure, NOT an eligible/ineligible
	// verdict — the shell should retry.
	if errors.Is(err, engine.ErrLocalReadFailed) {
		return newError(CodeDBError, "reclaim transient: %s", err.Error())
	}

	// Ineligible verdict from the engine (not verified, wrong tier,
	// no record, node not found).
	return newError(CodeVerifiedRequired, "reclaim ineligible: %s", err.Error())
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
	SourcePathHash string
	CapturedAtUnix int64
	// SizeBytes and ModifiedUnix (unix seconds) are the original item's
	// size and modification time as the shell read them from the platform
	// media store before staging LocalPath. They are recorded with the
	// BLAKE3 as the reclaim baseline that ReclaimSafeSpace re-verifies
	// (#229); 0 means the platform does not report that field.
	SizeBytes    int64
	ModifiedUnix int64
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
	if opts.SourcePathHash != "" {
		h := strings.ToLower(strings.TrimSpace(opts.SourcePathHash))
		if len(h) != 64 || !client.IsLowerHex(h) {
			return 0, newError(CodeInvalidInput, "invalid SourcePathHash: must be 64 lowercase hex characters")
		}
		opts.SourcePathHash = h
	}

	eng := e.engine
	item, err := eng.EnqueueLocalCaptureWithBaseline(
		opts.LocalPath, opts.Filename, opts.CapturedAtUnix, opts.LocalID, opts.CameraModel, opts.SourcePathHash,
		engine.LocalFileState{SizeBytes: opts.SizeBytes, ModifiedUnix: opts.ModifiedUnix},
	)
	if err != nil {
		var ce *client.ClientError
		if errors.As(err, &ce) && ce.Code == client.CodeInvalidInput {
			return 0, newError(CodeInvalidInput, "enqueue media: %v", err)
		}
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

// maxHashFileBytes bounds the size of a file ComputeHashes will stream.
//
// A media file is orders of magnitude below this: a photo is megabytes,
// even a long 4K ProRes clip is tens of gigabytes. The bound is not
// about legitimate media -- it is about making the work bounded. The
// hash streams the whole file, so without a cap a path that is not
// really a media file turns into an unbounded read on a phone's query
// thread.
const maxHashFileBytes = 64 << 30 // 64 GiB

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

	// The path arrives over the FFI boundary from the native shell, so it
	// is not necessarily a file. A FIFO (or a device node) is the
	// dangerous case: opening a FIFO read-only blocks until some process
	// opens the write end, which would pin one of the shell's two query
	// threads for good. Stat does not block on a FIFO, so the check comes
	// first and the open is non-blocking where the platform supports it.
	info, err := os.Stat(localPath)
	if err != nil {
		return Hashes{}, newError(CodeIOError, "stat: %v", err)
	}
	if !info.Mode().IsRegular() {
		return Hashes{}, newError(CodeInvalidInput, "not a regular file: %s", localPath)
	}
	if info.Size() > maxHashFileBytes {
		return Hashes{}, newError(CodeInvalidInput, "file too large to hash: %s (%d bytes)", localPath, info.Size())
	}

	f, err := os.OpenFile(localPath, os.O_RDONLY|openFlagsNonblock, 0)
	if err != nil {
		return Hashes{}, newError(CodeIOError, "open: %v", err)
	}
	defer f.Close()

	// The check above is about the PATH; this one is about the file that
	// was actually opened, which is what will be hashed.
	opened, err := f.Stat()
	if err != nil {
		return Hashes{}, newError(CodeIOError, "stat: %v", err)
	}
	if !opened.Mode().IsRegular() {
		return Hashes{}, newError(CodeInvalidInput, "not a regular file: %s", localPath)
	}
	if opened.Size() > maxHashFileBytes {
		return Hashes{}, newError(CodeInvalidInput, "file too large to hash: %s (%d bytes)", localPath, opened.Size())
	}

	fastHash, fullHash, _, err := hasher.HashReader(f, opened.Size())
	if err != nil {
		return Hashes{}, newError(CodeIOError, "hash: %v", err)
	}
	return Hashes{Blake3: fullHash, Fast: fastHash}, nil
}

// ---------------------------------------------------------------------------
// Lineage and delete events
// ---------------------------------------------------------------------------

// Confidence is a normalized 0.0-1.0 confidence score. EnqueueLineageEvent
// and ConfirmLineageEdge reject NaN, Inf, and out-of-range values with
// INVALID_INPUT.
type Confidence float64

// Standard confidence bands. ConfidenceExact is for deterministic
// capture-time pairs (DNG RAW + Ultra HDR JPEG sharing a stem, Motion
// Photos, Live Photos): only those are sent on the device's own say-so,
// through EnqueueLineageEvent. ConfidenceProximity and ConfidenceEdit are
// guesses: they go through the review screen and ConfirmLineageEdge.
const (
	ConfidenceExact     Confidence = 1.00
	ConfidenceProximity Confidence = 0.95
	ConfidenceEdit      Confidence = 0.95
)

// validateLineage checks the arguments EnqueueLineageEvent and
// ConfirmLineageEdge share.
func validateLineage(parentLocalID, childLocalID, relationshipType, resolver string, confidence Confidence) *Error {
	if confidence != confidence {
		// NaN check; Float comparison with itself is the only reliable
		// way to detect NaN.
		return newError(CodeInvalidInput, "confidence is NaN")
	}
	if math.IsInf(float64(confidence), 0) {
		return newError(CodeInvalidInput, "confidence is Inf")
	}
	if confidence < 0.0 || confidence > 1.0 {
		return newError(CodeInvalidInput, "confidence %v is out of [0,1]", float64(confidence))
	}
	if parentLocalID == "" || childLocalID == "" {
		return newError(CodeInvalidInput, "parent and child LocalID are required")
	}
	if parentLocalID == childLocalID {
		return newError(CodeInvalidInput, "parent and child LocalID are the same")
	}
	if relationshipType == "" {
		return newError(CodeInvalidInput, "relationshipType is required")
	}
	if _, err := engine.ServerRelationshipType(relationshipType); err != nil {
		return newError(CodeInvalidInput, "%v", err)
	}
	if resolver == "" {
		return newError(CodeInvalidInput, "resolver is required")
	}
	return nil
}

// EnqueueLineageEvent enqueues an EVENT_EDGE_ATTACHED the device found on
// its own, with no person involved. Returns the assigned event UUID.
//
// Only exact pairs (confidence 1.00, ConfidenceExact) are accepted; a lower
// confidence is REVIEW_REQUIRED: show the pair in the review screen and
// call ConfirmLineageEdge or RejectLineageEdge with the user's answer.
//
// parentLocalID and childLocalID are the same local IDs the media was
// enqueued for upload under (EnqueueMedia's LocalID). The event is queued
// in that local form and translated at sync time: it waits until both
// items have been uploaded and then goes to the server with their node
// UUIDs (parent as source, child as target, tier 1). relationshipType must
// be one of the server's relationship types (DERIVED_FROM, FINAL_EXPORT,
// PROXY_OF, PROJECT_SIDECAR, DUPLICATE_OF) or a known mobile alias
// (MOTION_PHOTO_CONTAINS, sent as DERIVED_FROM); anything else is
// INVALID_INPUT.
//
// The serialised payload is capped at queue.MaxEventPayloadBytes
// (64 KiB); oversized input is rejected with INVALID_INPUT rather
// than DB_ERROR — the queue never saw the row, so the shell must fix
// the input, not retry on a transient DB failure. See the package
// doc on Engine for the rationale.
func (e *Engine) EnqueueLineageEvent(
	parentLocalID, childLocalID, relationshipType, resolver string, confidence Confidence,
) (string, error) {
	if err := e.requireOpen(); err != nil {
		return "", err
	}
	if err := validateLineage(parentLocalID, childLocalID, relationshipType, resolver, confidence); err != nil {
		return "", err
	}
	if confidence < ConfidenceExact {
		return "", newError(CodeReviewRequired,
			"confidence %v is below %.2f: the user must confirm the pair (ConfirmLineageEdge)",
			float64(confidence), float64(ConfidenceExact))
	}

	payload, err := engine.EncodeLineageIntent(engine.LineageIntent{
		ParentLocalID: parentLocalID,
		ChildLocalID:  childLocalID,
		Relationship:  relationshipType,
		Confidence:    float64(confidence),
		Resolver:      resolver,
	})
	if err != nil {
		return "", newError(CodeInvalidInput, "build lineage payload: %v", err)
	}

	eventUUID, err := e.queue.EnqueueEvent(engine.EventEdgeAttached, payload)
	if err != nil {
		if errors.Is(err, queue.ErrPayloadTooLarge) {
			return "", newError(CodeInvalidInput, "enqueue lineage event: %v", err)
		}
		return "", newError(CodeDBError, "enqueue lineage event: %v", err)
	}
	return eventUUID, nil
}

// ConfirmLineageEdge records that the user confirmed a proposed lineage
// pair in the review screen and queues its EVENT_EDGE_ATTACHED. Returns the
// event UUID.
//
// The edge keeps the confidence the resolver gave it: a confirmation is
// not new evidence about the files, so it is never raised to 1.00. The
// event tells the server's reviewer the pair was confirmed on the device
// (evidenceJson.reviewedOnDevice). Confirming a pair twice returns the
// first event's UUID without queueing another; confirming a pair the user
// rejected earlier overrides the rejection. The decision and the event are
// written in one transaction.
//
// A confidence below the server's 0.50 floor is INVALID_INPUT: the server
// would refuse the edge. Arguments are otherwise as for
// EnqueueLineageEvent.
func (e *Engine) ConfirmLineageEdge(
	parentLocalID, childLocalID, relationshipType, resolver string, confidence Confidence,
) (string, error) {
	if err := e.requireOpen(); err != nil {
		return "", err
	}
	if err := validateLineage(parentLocalID, childLocalID, relationshipType, resolver, confidence); err != nil {
		return "", err
	}
	if float64(confidence) < engine.MinEdgeConfidence {
		return "", newError(CodeInvalidInput, "confidence %v is below the server's minimum %.2f",
			float64(confidence), engine.MinEdgeConfidence)
	}

	payload, err := engine.EncodeLineageIntent(engine.LineageIntent{
		ParentLocalID: parentLocalID,
		ChildLocalID:  childLocalID,
		Relationship:  relationshipType,
		Confidence:    float64(confidence),
		Resolver:      resolver,
		Reviewed:      true,
	})
	if err != nil {
		return "", newError(CodeInvalidInput, "build lineage payload: %v", err)
	}
	eventUUID, _, err := e.queue.ConfirmLineagePair(queue.LineageReview{
		ParentLocalID: parentLocalID,
		ChildLocalID:  childLocalID,
		Relationship:  relationshipType,
		Resolver:      resolver,
		Confidence:    float64(confidence),
	}, engine.EventEdgeAttached, payload)
	if err != nil {
		if errors.Is(err, queue.ErrPayloadTooLarge) {
			return "", newError(CodeInvalidInput, "confirm lineage edge: %v", err)
		}
		return "", newError(CodeDBError, "confirm lineage edge: %v", err)
	}
	return eventUUID, nil
}

// RejectLineageEdge records that the user rejected a proposed lineage pair
// (parent first, as proposed). The decision is stored in the queue
// database, so the pair is not proposed again after a restart; nothing is
// sent. Rejecting a pair twice is a no-op. A pair the user already
// confirmed can't be rejected on the device while its edge is queued or
// sent: that is INVALID_INPUT, and the reviewer on the server has to
// reject the edge there. One whose edge ended FAILED or SKIPPED (it never
// reached the server) is offered again and can be rejected.
func (e *Engine) RejectLineageEdge(parentLocalID, childLocalID, relationshipType, resolver string, confidence Confidence) error {
	if err := e.requireOpen(); err != nil {
		return err
	}
	// The same checks as ConfirmLineageEdge: the decision is stored and
	// echoed back by LineageReviewsJSON, so it must be well formed.
	if err := validateLineage(parentLocalID, childLocalID, relationshipType, resolver, confidence); err != nil {
		return err
	}
	err := e.queue.RejectLineagePair(queue.LineageReview{
		ParentLocalID: parentLocalID,
		ChildLocalID:  childLocalID,
		Relationship:  relationshipType,
		Resolver:      resolver,
		Confidence:    float64(confidence),
	})
	if err != nil {
		if errors.Is(err, queue.ErrLineageAlreadyConfirmed) {
			return newError(CodeInvalidInput, "reject lineage edge: %v", err)
		}
		return newError(CodeDBError, "reject lineage edge: %v", err)
	}
	return nil
}

// LineageReviewsJSON returns every review decision the user has made, most
// recent first, as a JSON array of queue.LineageReview objects
// (parentLocalId, childLocalId, decision CONFIRMED or REJECTED,
// relationship, resolver, confidence, eventUuid, decidedAtUnix). The review
// screen leaves out every pair listed here.
func (e *Engine) LineageReviewsJSON() (string, error) {
	if err := e.requireOpen(); err != nil {
		return "[]", err
	}
	reviews, err := e.queue.ListLineageReviews()
	if err != nil {
		return "[]", newError(CodeDBError, "list lineage reviews: %v", err)
	}
	b, err := json.Marshal(reviews)
	if err != nil {
		return "[]", newError("INTERNAL", "encode lineage reviews: %v", err)
	}
	return string(b), nil
}

// EnqueueDeleteEvent enqueues an EVENT_NODE_DELETED for the given LocalID,
// the same local ID the media was enqueued for upload under. At sync time
// it is sent as the server node UUID the item was uploaded as, which the
// server moves to trash. It is never sent for media that was never
// uploaded, or for media that was reclaimed (offloaded): after a reclaim
// the server master is the only copy.
//
// The serialised payload is capped at queue.MaxEventPayloadBytes
// (64 KiB); an oversized localID is rejected with INVALID_INPUT
// rather than DB_ERROR. See the package doc on Engine for the
// rationale.
func (e *Engine) EnqueueDeleteEvent(localID string) (string, error) {
	if err := e.requireOpen(); err != nil {
		return "", err
	}
	if localID == "" {
		return "", newError(CodeInvalidInput, "LocalID is required")
	}
	payload, err := engine.EncodeDeleteIntent(engine.DeleteIntent{LocalID: localID})
	if err != nil {
		return "", newError(CodeInvalidInput, "build delete payload: %v", err)
	}
	eventUUID, err := e.queue.EnqueueEvent(engine.EventNodeDeleted, payload)
	if err != nil {
		if errors.Is(err, queue.ErrPayloadTooLarge) {
			return "", newError(CodeInvalidInput, "enqueue delete event: %v", err)
		}
		return "", newError(CodeDBError, "enqueue delete event: %v", err)
	}
	return eventUUID, nil
}

// ---------------------------------------------------------------------------
// SyncBatch
// ---------------------------------------------------------------------------

// SyncOptions controls a SyncBatch call.
type SyncOptions struct {
	// TimeoutSecs bounds how long the batch keeps starting new work
	// (default 60). An upload already streaming when it passes is not cut
	// off: it runs under its own size-based budget (engine.UploadBudget).
	TimeoutSecs int
	BatchSize   int
	// RetryBackoffSecs is the base of the exponential retry backoff:
	// a row waits RetryBackoffSecs << retryCount seconds after a failed
	// attempt, capped at one hour (default 10).
	RetryBackoffSecs int64
	// MaxRetries is how many failed attempts move a row to FAILED
	// (default 5). Cancelled or interrupted attempts don't count.
	MaxRetries     int
	IncludeEvents  bool
	IncludeUploads bool
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
// The in-process cancel flag (B.2.2, SetCancelFlag) stops the batch and
// aborts an upload in flight. Whatever stops a batch early -- the cancel
// flag, the timeout, or the server rejecting the device's credentials
// (AUTH_FAILED) -- every queued item it had claimed but not finished goes
// back to the queue without using a retry.
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
		retryBackoff = engine.DefaultRetryBackoffSecs
	}
	maxRetries := opts.MaxRetries
	if maxRetries <= 0 {
		maxRetries = engine.DefaultMaxRetries
	}

	policy := engine.SyncPolicy{RetryBackoffSecs: retryBackoff, MaxRetries: maxRetries}

	stdCtx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSecs)*time.Second)
	defer cancel()
	e.checkKeyRotation(stdCtx)

	var result SyncResult
	if opts.IncludeUploads {
		n, err := e.engine.SyncUploadsWithPolicy(stdCtx, batchSize, policy)
		result.Uploaded = int64(n)
		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return result, newError(CodeContextCanceled, "sync uploads cancelled: %v", err)
			}
			return result, errToError(err)
		}
	}
	if opts.IncludeEvents {
		n, err := e.engine.SyncEventsWithPolicy(stdCtx, batchSize, policy)
		result.EventsSent = int64(n)
		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return result, newError(CodeContextCanceled, "sync events cancelled: %v", err)
			}
			return result, errToError(err)
		}
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

// ResetFailedUploads resets all FAILED upload items back to PENDING.
func (e *Engine) ResetFailedUploads() (int64, error) {
	if err := e.requireOpen(); err != nil {
		return 0, err
	}
	return e.engine.ResetFailedUploads()
}

// GetActiveUploadProgress returns current active upload metrics, or nil if no upload active.
func (e *Engine) GetActiveUploadProgress() (*engine.ActiveUploadProgress, error) {
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	return e.engine.GetActiveUploadProgress()
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
	LocalID  string `json:"localId"`
	Eligible bool   `json:"eligible"`
	Reason   string `json:"reason,omitempty"`
}

// CheckSafeSpaceCandidates queries the server for the current verified, tier
// and lifecycle status of each candidate's node and returns a verdict per
// candidate. A candidate is eligible only when its node is hash-verified, on
// the Tier-3 master archive and live (ACTIVE or HIDDEN). Ineligible verdicts
// carry "VERIFIED_REQUIRED: <failing gate>" as their Reason; candidates
// without a nodeUUID are reported as ineligible with a not-found reason.
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
	ctx, cancel := context.WithTimeout(context.Background(), reclaimTimeout)
	defer cancel()
	raw, err := e.engine.CheckSafeSpaceCandidates(ctx, localIDs)
	if err != nil {
		return nil, errToError(err)
	}
	// raw is []engine.SafeSpaceCandidate indexed by input order; map it
	// back to the caller's input localIDs.
	out := make([]SafeSpaceVerdict, 0, len(raw))
	for _, c := range raw {
		reason := ""
		if !c.IsEligible {
			// Audit: VERIFIED_REQUIRED is the canonical reason code a
			// candidate is ineligible; the suffix names the gate that
			// failed (not verified, wrong tier, not live, ...).
			reason = CodeVerifiedRequired
			if c.Reason != "" {
				reason += ": " + c.Reason
			}
		}
		out = append(out, SafeSpaceVerdict{LocalID: c.LocalID, Eligible: c.IsEligible, Reason: reason})
	}
	return out, nil
}

// reclaimTimeout bounds a reclaim-gate call.
//
// The gate is a server round trip (node-status) plus local checks, so it
// needs a deadline. The shells run these on a two-thread query pool: an
// unreachable server would otherwise pin a thread for the client's whole
// timeout, and with two concurrent reclaims every other engine query queues
// behind them.
const reclaimTimeout = 30 * time.Second

// ReclaimSafeSpace marks LocalID as offloaded after re-checking the server
// and only if the node is currently verified, on the Tier-3 master archive,
// live (ACTIVE or HIDDEN) and holding the recorded BLAKE3, and only if
// sizeBytes and modifiedUnix -- the item's media-store size and
// modification time, read by the shell immediately before this call --
// match the baseline recorded at enqueue (#229). The local file
// deletion is the shell's responsibility and should only happen after
// this returns Eligible=true.
func (e *Engine) ReclaimSafeSpace(localID string, sizeBytes, modifiedUnix int64) (SafeSpaceVerdict, error) {
	if err := e.requireOpen(); err != nil {
		return SafeSpaceVerdict{}, err
	}
	if localID == "" {
		return SafeSpaceVerdict{}, newError(CodeInvalidInput, "LocalID is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), reclaimTimeout)
	defer cancel()
	verdict, err := e.engine.SafeSpaceReclaim(ctx, localID,
		engine.LocalFileState{SizeBytes: sizeBytes, ModifiedUnix: modifiedUnix})
	if err != nil {
		// Distinguish transient infrastructure failures (network, DB)
		// from genuine ineligible verdicts. The shell should retry on
		// transient errors and only surface "verify your node" to the
		// user when the engine confirms the node is ineligible.
		//
		// - ClientError with NETWORK_ERROR / IO_ERROR: transient
		// - Errors wrapping a queue DB error ("set offloaded: ..."): transient
		// - Everything else: ineligible (not verified, wrong tier, no record)
		return SafeSpaceVerdict{LocalID: localID, Reason: err.Error()},
			reclaimErrorToBranchdamError(err)
	}
	if !verdict.Eligible {
		return SafeSpaceVerdict{LocalID: localID, Reason: verdict.Reason},
			newError(CodeVerifiedRequired, "reclaim ineligible: %s", verdict.Reason)
	}
	return SafeSpaceVerdict{LocalID: localID, Eligible: true}, nil
}

// GetMediaStatus returns the status of a media asset identified by localID.
// Returns one of: "OFFLOADED", "COMPLETED", "IN_PROGRESS", "PENDING", "FAILED", or "NOT_ENQUEUED".
func (e *Engine) GetMediaStatus(localID string) (string, error) {
	if err := e.requireOpen(); err != nil {
		return "NOT_ENQUEUED", err
	}
	return e.engine.GetMediaStatus(localID)
}

// GetAllMediaStatuses returns a map of localID/localPath/srcPathHash -> status for all tracked items.
func (e *Engine) GetAllMediaStatuses() (map[string]string, error) {
	if err := e.requireOpen(); err != nil {
		return map[string]string{}, err
	}
	return e.engine.GetAllMediaStatuses()
}

// CountPendingUploads returns the total number of non-completed upload tasks.
func (e *Engine) CountPendingUploads() (int64, error) {
	if err := e.requireOpen(); err != nil {
		return 0, err
	}
	return e.engine.CountPendingUploads()
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
// Pending imports
// ---------------------------------------------------------------------------

// StagePendingImport persists a camera-roll item that is waiting for the
// user to confirm its import, so the intent survives the app process dying
// before the user answers. Re-staging refreshes the metadata. An item the
// user already skipped (SuppressPendingImport) stays skipped and staged is
// false; staged is true when the item is now pending.
func (e *Engine) StagePendingImport(localID, localPath, filename, mimeType string, sizeBytes, capturedAtUnix int64) (bool, error) {
	if err := e.requireOpen(); err != nil {
		return false, err
	}
	if localID == "" {
		return false, newError(CodeInvalidInput, "LocalID is required")
	}
	staged, err := e.queue.StagePendingImport(queue.PendingImport{
		LocalID:        localID,
		LocalPath:      localPath,
		Filename:       filename,
		MimeType:       mimeType,
		SizeBytes:      sizeBytes,
		CapturedAtUnix: capturedAtUnix,
	})
	if err != nil {
		return false, newError(CodeDBError, "stage pending import: %v", err)
	}
	return staged, nil
}

// ListPendingImportsJSON returns the items still awaiting an import
// decision, oldest capture first, as a JSON array of queue.PendingImport
// objects (localId, localPath, filename, mimeType, sizeBytes,
// capturedAtUnix, state, createdAtUnix, updatedAtUnix). limit <= 0 returns
// all of them.
func (e *Engine) ListPendingImportsJSON(limit int) (string, error) {
	if err := e.requireOpen(); err != nil {
		return "[]", err
	}
	items, err := e.queue.ListPendingImports(limit)
	if err != nil {
		return "[]", newError(CodeDBError, "list pending imports: %v", err)
	}
	b, err := json.Marshal(items)
	if err != nil {
		return "[]", newError("INTERNAL", "encode pending imports: %v", err)
	}
	return string(b), nil
}

// DeletePendingImport forgets localID: after it was imported, or to lift
// a skip. deleted is false when nothing was recorded for it.
func (e *Engine) DeletePendingImport(localID string) (bool, error) {
	if err := e.requireOpen(); err != nil {
		return false, err
	}
	if localID == "" {
		return false, newError(CodeInvalidInput, "LocalID is required")
	}
	deleted, err := e.queue.DeletePendingImport(localID)
	if err != nil {
		return false, newError(CodeDBError, "delete pending import: %v", err)
	}
	return deleted, nil
}

// SuppressPendingImport records that the user skipped localID, so later
// scans don't offer it again. It need not have been staged first.
func (e *Engine) SuppressPendingImport(localID string) error {
	if err := e.requireOpen(); err != nil {
		return err
	}
	if localID == "" {
		return newError(CodeInvalidInput, "LocalID is required")
	}
	if err := e.queue.SuppressPendingImport(localID); err != nil {
		return newError(CodeDBError, "suppress pending import: %v", err)
	}
	return nil
}

// IsImportSuppressed reports whether the user skipped localID.
func (e *Engine) IsImportSuppressed(localID string) (bool, error) {
	if err := e.requireOpen(); err != nil {
		return false, err
	}
	if localID == "" {
		return false, newError(CodeInvalidInput, "LocalID is required")
	}
	suppressed, err := e.queue.IsImportSuppressed(localID)
	if err != nil {
		return false, newError(CodeDBError, "is import suppressed: %v", err)
	}
	return suppressed, nil
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
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := e.client.Handshake(ctx, "")
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
//
// Event payload size cap: events enqueued through this Engine
// (EnqueueLineageEvent, EnqueueDeleteEvent) are bounded at
// queue.MaxEventPayloadBytes (64 KiB). The cap prevents a single
// misbehaving caller from writing a multi-MB payload to the event
// queue, which would otherwise lock the queue's single SQLite
// connection and stall every other writer. Oversized input is
// rejected with INVALID_INPUT before any row is inserted; do NOT
// raise the cap or add a code path that bypasses it.
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

	// Rotated API keys go to the shell's secure storage (keyrotation.go).
	httpClient.SetKeyStore(storeRotatedKey)

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
