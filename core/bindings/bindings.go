package bindings

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
	"github.com/s3ntin3l8/branchdam-mobile/core/engine"
	"github.com/s3ntin3l8/branchdam-mobile/core/hasher"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

var (
	globalEngine *engine.Engine
	globalQueue  *queue.Queue
	globalClient *client.Client
	engineMu     sync.RWMutex
)

// InitCore initializes the Go core engine with SQLite and client config.
func InitCore(dbPath, baseURL, apiKey, agentID, clientVersion string) error {
	engineMu.Lock()
	defer engineMu.Unlock()

	if globalQueue != nil {
		_ = globalQueue.Close()
	}

	q, err := queue.Open(dbPath)
	if err != nil {
		return fmt.Errorf("failed to open queue db: %w", err)
	}

	c := client.New(client.Config{
		BaseURL:       baseURL,
		APIKey:        apiKey,
		AgentID:       agentID,
		ClientVersion: clientVersion,
	})

	globalQueue = q
	globalClient = c
	globalEngine = engine.New(q, c)

	return nil
}

// FetchNamingTemplate performs a handshake with the server and returns the active naming template.
func FetchNamingTemplate() (string, error) {
	engineMu.RLock()
	c := globalClient
	engineMu.RUnlock()
	if c == nil {
		return "", fmt.Errorf("core engine not initialized")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	resp, err := c.Handshake(ctx, "")
	if err != nil {
		return "", err
	}
	return resp.NamingTemplate, nil
}

// FileHashes contains hashes and size of a file.
type FileHashes struct {
	FastHash  string
	FullHash  string
	SizeBytes int64
}

// ComputeFileHashes calculates FastHash (16 hex) and FullHash BLAKE3-256 (64 hex).
func ComputeFileHashes(filePath string) (*FileHashes, error) {
	file, err := openFile(filePath)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	fastHash, fullHash, sizeBytes, err := hasher.HashReader(file)
	if err != nil {
		return nil, err
	}
	return &FileHashes{
		FastHash:  fastHash,
		FullHash:  fullHash,
		SizeBytes: sizeBytes,
	}, nil
}

// EnqueueMedia enqueues a newly captured photo/video into the local queue.
func EnqueueMedia(localPath, filename string, capturedAtUnix int64, localID string, cameraModel string) (int64, error) {
	engineMu.RLock()
	defer engineMu.RUnlock()
	if globalEngine == nil {
		return 0, fmt.Errorf("core engine not initialized")
	}

	var cameraModels []string
	if cameraModel != "" {
		cameraModels = append(cameraModels, cameraModel)
	}

	item, err := globalEngine.EnqueueLocalCapture(localPath, filename, capturedAtUnix, localID, cameraModels...)
	if err != nil {
		return 0, err
	}
	return item.ID, nil
}

// EnqueueLineageEvent registers an edge attachment event (e.g. DNG+JPEG pairing, edit derivation).
func EnqueueLineageEvent(parentUUID, childUUID, relationshipType, resolver string, confidence float64) (string, error) {
	engineMu.RLock()
	defer engineMu.RUnlock()
	if globalQueue == nil {
		return "", fmt.Errorf("core engine not initialized")
	}

	payload := map[string]any{
		"parentUuid":       parentUUID,
		"childUuid":        childUUID,
		"relationshipType": relationshipType,
		"confidence":       confidence,
		"resolver":         resolver,
	}
	payloadBytes, _ := json.Marshal(payload)

	return globalQueue.EnqueueEvent("EVENT_EDGE_ATTACHED", string(payloadBytes))
}

// EnqueueDeleteEvent registers a node deletion event when photo is moved to trash.
func EnqueueDeleteEvent(nodeUUID string) (string, error) {
	engineMu.RLock()
	defer engineMu.RUnlock()
	if globalQueue == nil {
		return "", fmt.Errorf("core engine not initialized")
	}

	payload := map[string]any{
		"nodeUuid": nodeUUID,
	}
	payloadBytes, _ := json.Marshal(payload)

	return globalQueue.EnqueueEvent("EVENT_NODE_DELETED", string(payloadBytes))
}

// SyncResult contains counts for uploads and events processed in a batch.
type SyncResult struct {
	Uploaded   int
	EventsSent int
}

// SyncBatch triggers an upload and event dispatch cycle with a timeout.
func SyncBatch(timeoutSecs int, batchSize int) (*SyncResult, error) {
	engineMu.RLock()
	eng := globalEngine
	engineMu.RUnlock()

	if eng == nil {
		return nil, fmt.Errorf("core engine not initialized")
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSecs)*time.Second)
	defer cancel()

	uCount, uErr := eng.SyncUploads(ctx, batchSize)
	eCount, eErr := eng.SyncEvents(ctx, batchSize)

	res := &SyncResult{
		Uploaded:   uCount,
		EventsSent: eCount,
	}

	if uErr != nil {
		return res, uErr
	}
	if eErr != nil {
		return res, eErr
	}
	return res, nil
}

// IsMediaOffloaded checks whether local deletion of localID was an intentional offload.
func IsMediaOffloaded(localID string) (bool, error) {
	engineMu.RLock()
	defer engineMu.RUnlock()
	if globalQueue == nil {
		return false, fmt.Errorf("core engine not initialized")
	}
	return globalQueue.IsMediaOffloaded(localID)
}

// SetMediaOffloaded flags local asset as offloaded to suppress deletion events.
func SetMediaOffloaded(localID string, isOffloaded bool) error {
	engineMu.RLock()
	defer engineMu.RUnlock()
	if globalQueue == nil {
		return fmt.Errorf("core engine not initialized")
	}
	return globalQueue.SetMediaOffloaded(localID, isOffloaded)
}
