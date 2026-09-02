package branchdam

import (
	"fmt"
	"strings"
	"sync"
)

// gomobile-compatible binding layer. gomobile's gobind skips methods that
// accept or return struct types; these wrappers use only primitives so
// they survive into the Java/Swift bindings. A package-level mutex
// serializes access — the mobile shell is single-threaded by design
// (see EngineHolder.kt's executor).

var (
	bindingMu     sync.Mutex
	bindingEngine *Engine
)

// BindingOpen initialises the engine from primitive parameters.
// devCleartextHosts is a comma-separated list of hosts allowed over
// HTTP in debug builds; pass "" in production to require HTTPS.
func BindingOpen(dbPath, baseURL, apiKey, agentID, clientVersion, devCleartextHosts string) error {
	bindingMu.Lock()
	defer bindingMu.Unlock()

	if bindingEngine != nil {
		_ = bindingEngine.Close()
		bindingEngine = nil
	}

	var hosts []string
	if devCleartextHosts != "" {
		for _, h := range strings.Split(devCleartextHosts, ",") {
			h = strings.TrimSpace(h)
			if h != "" {
				hosts = append(hosts, h)
			}
		}
	}

	e, err := NewEngine(EngineOptions{
		DBPath:            dbPath,
		BaseURL:           baseURL,
		APIKey:            apiKey,
		AgentID:           agentID,
		ClientVersion:     clientVersion,
		DevCleartextHosts: hosts,
	})
	if err != nil {
		return err
	}
	bindingEngine = e
	return nil
}

// BindingClose closes the engine and releases resources.
func BindingClose() error {
	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return nil
	}
	err := bindingEngine.Close()
	bindingEngine = nil
	return err
}

// BindingEnqueueMedia enqueues a local media file for upload. Returns the
// upload ID (>= 1) on success or 0 on error.
func BindingEnqueueMedia(localPath, filename, localID, cameraModel string,
	capturedAtUnix, sizeBytes int64) (int64, error) {

	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return 0, fmt.Errorf("engine not open")
	}
	return bindingEngine.EnqueueMedia(EnqueueMediaOptions{
		LocalPath:      localPath,
		Filename:       filename,
		LocalID:        localID,
		CameraModel:    cameraModel,
		CapturedAtUnix: capturedAtUnix,
		SizeBytes:      sizeBytes,
	})
}

// BindingEnqueueLineageEvent enqueues a lineage relationship event.
// confidence is a 0.0–1.0 score.
func BindingEnqueueLineageEvent(parentLocalID, childLocalID,
	relationshipType, resolver string, confidence float64) (string, error) {

	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return "", fmt.Errorf("engine not open")
	}
	return bindingEngine.EnqueueLineageEvent(
		parentLocalID, childLocalID, relationshipType, resolver,
		Confidence(confidence))
}

// BindingEnqueueDeleteEvent enqueues a local-delete lifecycle event.
func BindingEnqueueDeleteEvent(localID string) (string, error) {
	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return "", fmt.Errorf("engine not open")
	}
	return bindingEngine.EnqueueDeleteEvent(localID)
}

// BindingSyncBatch runs a sync cycle.
func BindingSyncBatch(timeoutSecs, batchSize int64) error {
	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return fmt.Errorf("engine not open")
	}
	_, err := bindingEngine.SyncBatch(SyncOptions{
		TimeoutSecs:    int(timeoutSecs),
		BatchSize:      int(batchSize),
		IncludeEvents:  true,
		IncludeUploads: true,
	})
	return err
}

// BindingIsMediaOffloaded returns the offload flag. On error returns false
// (fail-closed: shell refuses to delete).
func BindingIsMediaOffloaded(localID string) (bool, error) {
	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return false, fmt.Errorf("engine not open")
	}
	return bindingEngine.IsMediaOffloaded(localID)
}

// BindingSetMediaOffloaded sets the offload flag directly.
func BindingSetMediaOffloaded(localID string, isOffloaded bool) error {
	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return fmt.Errorf("engine not open")
	}
	return bindingEngine.SetMediaOffloaded(localID, isOffloaded)
}

// BindingSetCancelFlag requests cancellation of the current sync batch.
func BindingSetCancelFlag() error {
	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return fmt.Errorf("engine not open")
	}
	bindingEngine.SetCancelFlag()
	return nil
}

// BindingFetchNamingTemplate fetches the naming template from the server.
func BindingFetchNamingTemplate() (string, error) {
	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return "", fmt.Errorf("engine not open")
	}
	return bindingEngine.FetchNamingTemplate()
}

// BindingReclaimSafeSpace runs the atomic reclaim for a single local ID.
func BindingReclaimSafeSpace(localID string) error {
	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return fmt.Errorf("engine not open")
	}
	_, err := bindingEngine.ReclaimSafeSpace(localID)
	return err
}

// BindingCheckSafeSpaceCandidates checks a batch of local IDs for
// eligibility. localIDs is a comma-separated list. Returns a
// comma-separated "localID:eligible:reason" string.
func BindingCheckSafeSpaceCandidates(localIDs string) (string, error) {
	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return "", fmt.Errorf("engine not open")
	}
	if localIDs == "" {
		return "", nil
	}
	ids := splitIDs(localIDs)
	candidates := make([]SafeSpaceCandidate, len(ids))
	for i, id := range ids {
		candidates[i] = SafeSpaceCandidate{LocalID: id}
	}
	verdicts, err := bindingEngine.CheckSafeSpaceCandidates(candidates)
	if err != nil {
		return "", err
	}
	parts := make([]string, 0, len(verdicts))
	for _, v := range verdicts {
		parts = append(parts, v.LocalID+":"+fmt.Sprintf("%t", v.Eligible)+":"+v.Reason)
	}
	return strings.Join(parts, ","), nil
}

func splitIDs(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}
