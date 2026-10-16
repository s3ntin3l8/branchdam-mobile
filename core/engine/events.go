package engine

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

// Event types the engine translates at dispatch time. Both are queued in a
// device-local form keyed by local media IDs (LineageIntent, DeleteIntent)
// because the server node UUIDs are usually not known yet when the shell
// enqueues them; SyncEvents resolves the local IDs to node UUIDs through
// local_media_state and sends the server's own payload schema
// (EdgeAttachedPayload / NodeDeletedPayload in branchDAM's
// internal/agent/types.go). Any other event type is sent verbatim.
const (
	EventEdgeAttached = "EVENT_EDGE_ATTACHED"
	EventNodeDeleted  = "EVENT_NODE_DELETED"
)

// LineageIntent is the queued, device-local form of an EVENT_EDGE_ATTACHED.
// The JSON keys are the queue's storage format; rows written by earlier
// releases use the same keys and stay dispatchable.
type LineageIntent struct {
	ParentLocalID string  `json:"parentLocalId"`
	ChildLocalID  string  `json:"childLocalId"`
	Relationship  string  `json:"relationship"`
	Confidence    float64 `json:"confidence"`
	Resolver      string  `json:"resolver"`
	// Reviewed is true when a person confirmed the pair on the device.
	// An edge below AutoEdgeConfidence is only ever sent when it is set;
	// rows written before it existed read as false.
	Reviewed bool `json:"reviewed,omitempty"`
}

// DeleteIntent is the queued, device-local form of an EVENT_NODE_DELETED.
type DeleteIntent struct {
	LocalID string `json:"localId"`
}

// EncodeLineageIntent serialises a LineageIntent for the event queue.
func EncodeLineageIntent(in LineageIntent) (string, error) {
	b, err := json.Marshal(in)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// EncodeDeleteIntent serialises a DeleteIntent for the event queue.
func EncodeDeleteIntent(in DeleteIntent) (string, error) {
	b, err := json.Marshal(in)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// edgeAttachedPayload is the server's EdgeAttachedPayload as this client
// sends it: endpoints by UUID only (never the server-internal node IDs) and
// never a reviewState, which only a human reviewer may set.
type edgeAttachedPayload struct {
	SourceNodeUUID   string          `json:"sourceNodeUuid"`
	TargetNodeUUID   string          `json:"targetNodeUuid"`
	RelationshipType string          `json:"relationshipType"`
	Confidence       float64         `json:"confidence"`
	Tier             int64           `json:"tier"`
	Resolver         string          `json:"resolver,omitempty"`
	EvidenceJSON     json.RawMessage `json:"evidenceJson,omitempty"`
}

// nodeDeletedPayload is the server's NodeDeletedPayload.
type nodeDeletedPayload struct {
	NodeUUID string `json:"nodeUuid"`
}

// MinEdgeConfidence is the server's needsReviewFloor: an edge below it is
// refused (fatally, after the event was accepted), so it is never sent.
const MinEdgeConfidence = 0.50

// AutoEdgeConfidence is the confidence at which the device may send a
// lineage edge on its own: deterministic capture-time pairs (DNG RAW +
// Ultra HDR JPEG sharing a stem, Motion Photos, Live Photos) are exact.
// Anything weaker (a timestamp-proximity pair, an in-phone edit matched by
// filename) is a guess that waits for a person to confirm it on the device.
const AutoEdgeConfidence = 1.0

// mobileEdgeTier is the resolver tier mobile lineage edges are reported as:
// they come from direct capture-time evidence (sensor pairs, embedded
// motion-photo video, Live Photo resources, editor exports).
const mobileEdgeTier = 1

// EventDeferralMaxAge bounds how long an event may wait for its media to
// reach the server. A lineage edge whose endpoints still have no node UUID
// after this long (never uploaded, or the upload failed for good) is marked
// FAILED instead of being re-evaluated forever.
const EventDeferralMaxAge = 30 * 24 * time.Hour

// serverRelationshipTypes are the values of the server's
// media_edges.relationship_type CHECK constraint.
var serverRelationshipTypes = map[string]bool{
	"DERIVED_FROM":    true,
	"FINAL_EXPORT":    true,
	"PROXY_OF":        true,
	"PROJECT_SIDECAR": true,
	"DUPLICATE_OF":    true,
}

// mobileRelationshipAliases maps relationship names the mobile shells use
// that the server does not know onto the server's enum. A Motion Photo's
// extracted micro-video is derived from the still that contains it.
var mobileRelationshipAliases = map[string]string{
	"MOTION_PHOTO_CONTAINS": "DERIVED_FROM",
}

// ErrUnknownRelationship is returned by ServerRelationshipType for a
// relationship the server cannot store.
var ErrUnknownRelationship = errors.New("unknown relationship type")

// ServerRelationshipType maps a mobile relationship name to the server's
// relationship_type enum. Server values pass through unchanged; known
// mobile aliases are translated; anything else is an error, because the
// server would reject it with a fatal CHECK-constraint failure after
// accepting the event.
func ServerRelationshipType(mobile string) (string, error) {
	if serverRelationshipTypes[mobile] {
		return mobile, nil
	}
	if mapped, ok := mobileRelationshipAliases[mobile]; ok {
		return mapped, nil
	}
	return "", fmt.Errorf("%w: %q", ErrUnknownRelationship, mobile)
}

// dispatchAction is what SyncEvents does with one claimed event.
type dispatchAction int

const (
	// actionSend submits payload to the server.
	actionSend dispatchAction = iota
	// actionDefer leaves the event PENDING without using a retry.
	actionDefer
	// actionSkip ends the event as SKIPPED: nothing to do on the server.
	actionSkip
	// actionFail ends the event as FAILED: it can never be sent.
	actionFail
	// actionRetry counts a failed attempt (transient local error).
	actionRetry
)

type dispatchPlan struct {
	action  dispatchAction
	payload string
	reason  string
}

func sendPlan(payload string) dispatchPlan { return dispatchPlan{action: actionSend, payload: payload} }

func planf(a dispatchAction, format string, args ...any) dispatchPlan {
	return dispatchPlan{action: a, reason: fmt.Sprintf(format, args...)}
}

// planEvent decides how to dispatch one queued event, translating the
// device-local lineage and delete forms into the server's schema.
func (e *Engine) planEvent(evt *queue.EventItem) dispatchPlan {
	switch evt.EventType {
	case EventEdgeAttached:
		return e.planEdgeAttached(evt)
	case EventNodeDeleted:
		return e.planNodeDeleted(evt)
	default:
		return sendPlan(evt.PayloadJSON)
	}
}

func (e *Engine) planEdgeAttached(evt *queue.EventItem) dispatchPlan {
	var in LineageIntent
	if err := json.Unmarshal([]byte(evt.PayloadJSON), &in); err != nil {
		return planf(actionFail, "malformed queued lineage event: %v", err)
	}
	if in.ParentLocalID == "" || in.ChildLocalID == "" {
		return planf(actionFail, "malformed queued lineage event: parentLocalId and childLocalId are required")
	}
	rel, err := ServerRelationshipType(in.Relationship)
	if err != nil {
		return planf(actionFail, "lineage event not sent: %v", err)
	}
	conf := in.Confidence
	if math.IsNaN(conf) || conf < MinEdgeConfidence {
		return planf(actionFail, "lineage event not sent: confidence %v is below the server's minimum %.2f", conf, MinEdgeConfidence)
	}
	if conf < AutoEdgeConfidence && !in.Reviewed {
		// Queued by an earlier release that sent its guesses on its own.
		// Dropping it is safe: no decision was recorded, so the shell
		// offers the pair for review again.
		return planf(actionSkip, "lineage event not sent: confidence %v is below %.2f and nobody confirmed the pair on the device", conf, AutoEdgeConfidence)
	}
	conf = math.Min(conf, 1.0)

	parent, err := e.resolveLocalMedia(in.ParentLocalID)
	if err != nil {
		return planf(actionRetry, "resolve parent %q: %v", in.ParentLocalID, err)
	}
	child, err := e.resolveLocalMedia(in.ChildLocalID)
	if err != nil {
		return planf(actionRetry, "resolve child %q: %v", in.ChildLocalID, err)
	}

	var waiting []string
	if parent.nodeUUID == "" {
		waiting = append(waiting, "parent")
	}
	if child.nodeUUID == "" {
		waiting = append(waiting, "child")
	}
	if len(waiting) > 0 {
		what := strings.Join(waiting, " and ")
		if e.deferralExpired(evt) {
			return planf(actionFail, "lineage event not sent: %s media never reached the server within %s", what, EventDeferralMaxAge)
		}
		return planf(actionDefer, "waiting for %s media to be uploaded", what)
	}
	if parent.nodeUUID == child.nodeUUID {
		return planf(actionFail, "lineage event not sent: parent and child resolve to the same node %s", parent.nodeUUID)
	}

	out := edgeAttachedPayload{
		// The server reads source as the parent (origin) and target as
		// the child (derivative): applyEdgeAttached's cycle check passes
		// ParentNodeID: sourceID, ChildNodeID: targetID.
		SourceNodeUUID:   parent.nodeUUID,
		TargetNodeUUID:   child.nodeUUID,
		RelationshipType: rel,
		Confidence:       conf,
		Tier:             mobileEdgeTier,
		Resolver:         in.Resolver,
	}
	evidence := map[string]any{}
	if rel != in.Relationship {
		// Keep the mobile-side name the alias was mapped from, for the
		// human reviewing the edge.
		evidence["mobileRelationship"] = in.Relationship
	}
	if in.Reviewed {
		// Tell the server's reviewer a person already looked at the
		// pair on the phone. The confidence stays the resolver's own:
		// a confirmation is not new evidence about the files.
		evidence["reviewedOnDevice"] = true
	}
	if len(evidence) > 0 {
		ev, err := json.Marshal(evidence)
		if err == nil {
			out.EvidenceJSON = ev
		}
	}
	b, err := json.Marshal(out)
	if err != nil {
		return planf(actionFail, "encode lineage event: %v", err)
	}
	return sendPlan(string(b))
}

func (e *Engine) planNodeDeleted(evt *queue.EventItem) dispatchPlan {
	var in DeleteIntent
	if err := json.Unmarshal([]byte(evt.PayloadJSON), &in); err != nil {
		return planf(actionFail, "malformed queued delete event: %v", err)
	}
	if in.LocalID == "" {
		return planf(actionFail, "malformed queued delete event: localId is required")
	}
	m, err := e.resolveLocalMedia(in.LocalID)
	if err != nil {
		return planf(actionRetry, "resolve %q: %v", in.LocalID, err)
	}
	if !m.known {
		return planf(actionSkip, "delete not sent: %q was never enqueued for upload", in.LocalID)
	}
	if m.offloaded {
		// Reclaim deletes only the phone copy; the server master is now
		// the only copy and must never be trashed by this device.
		return planf(actionSkip, "delete not sent: %q was reclaimed (offloaded), the server master is kept", in.LocalID)
	}
	if m.nodeUUID == "" {
		if m.uploadPending {
			if !e.deferralExpired(evt) {
				return planf(actionDefer, "waiting for the upload of %q to finish", in.LocalID)
			}
			return planf(actionSkip, "delete not sent: the upload of %q did not complete within %s", in.LocalID, EventDeferralMaxAge)
		}
		return planf(actionSkip, "delete not sent: %q was never uploaded", in.LocalID)
	}
	b, err := json.Marshal(nodeDeletedPayload{NodeUUID: m.nodeUUID})
	if err != nil {
		return planf(actionFail, "encode delete event: %v", err)
	}
	return sendPlan(string(b))
}

func (e *Engine) deferralExpired(evt *queue.EventItem) bool {
	return time.Since(time.Unix(evt.CreatedAtUnix, 0)) > EventDeferralMaxAge
}

// resolvedMedia is what this device knows about one local media item.
type resolvedMedia struct {
	// known is false when the device has no local_media_state for it.
	known bool
	// nodeUUID is the server node the item was uploaded as, or "".
	nodeUUID string
	// offloaded is true when the item, or another local item with the
	// same server node, was reclaimed.
	offloaded bool
	// uploadPending is true while an upload of the item's content is
	// still queued or in flight.
	uploadPending bool
}

// legacyLocalIDPrefix is the "ph://" prefix older iOS builds put on PhotoKit
// local identifiers in lineage events (and on the Live Photo video's
// synthetic ID) while uploads were recorded under the bare identifier.
// Resolution tries both spellings so events queued by those builds still
// find their media.
const legacyLocalIDPrefix = "ph://"

func localIDCandidates(localID string) []string {
	if rest, ok := strings.CutPrefix(localID, legacyLocalIDPrefix); ok {
		return []string{localID, rest}
	}
	return []string{localID, legacyLocalIDPrefix + localID}
}

// resolveLocalMedia looks a queued local ID up in local_media_state. It
// also accepts a raw server node UUID in place of a local ID (older shells
// enqueued deletes that way) as long as this device recorded that node. A
// node UUID missing from local_media_state is recovered from the upload
// queue when the item's upload has completed.
func (e *Engine) resolveLocalMedia(localID string) (resolvedMedia, error) {
	var state *queue.LocalMediaState
	for _, id := range localIDCandidates(localID) {
		s, err := e.q.GetMediaByLocalID(id)
		if err == nil {
			state = s
			break
		}
		if !isNotFoundErr(err) {
			return resolvedMedia{}, err
		}
	}
	if state == nil {
		if _, perr := uuid.Parse(localID); perr == nil {
			s, err := e.q.GetMediaByNodeUUID(localID)
			if err != nil && !isNotFoundErr(err) {
				return resolvedMedia{}, err
			}
			state = s
		}
	}
	if state == nil {
		return resolvedMedia{}, nil
	}

	out := resolvedMedia{known: true, nodeUUID: state.NodeUUID, offloaded: state.IsOffloaded}
	if out.nodeUUID == "" && state.Blake3Hash != "" {
		item, err := e.q.GetUploadItemByBlake3Hash(state.Blake3Hash)
		if err != nil {
			return resolvedMedia{}, err
		}
		if item != nil {
			switch item.Status {
			case queue.UploadCompleted:
				out.nodeUUID = item.NodeUUID
			case queue.UploadPending, queue.UploadInProgress:
				out.uploadPending = true
			}
		}
	}
	if out.nodeUUID != "" && !out.offloaded {
		off, err := e.q.IsNodeOffloaded(out.nodeUUID)
		if err != nil {
			return resolvedMedia{}, err
		}
		out.offloaded = off
	}
	return out, nil
}
