package client

type HandshakeRequest struct {
	AgentID                string `json:"agentId"`
	ClientVersion          string `json:"clientVersion"`
	LastProcessedEventUUID string `json:"lastProcessedEventUuid,omitempty"`
}

type HandshakeResponse struct {
	OK                    bool   `json:"ok"`
	ServerVersion         string `json:"serverVersion"`
	ServerTimeUnix        int64  `json:"serverTimeUnix"`
	AcknowledgedEventUUID string `json:"acknowledgedEventUuid,omitempty"`
	PendingEventsCount    int64  `json:"pendingEventsCount"`
	NamingTemplate        string `json:"namingTemplate,omitempty"`
	// CurrentKeyID is the id of the paired-device key the request
	// authenticated with (0 = omitted, not a paired-device key).
	CurrentKeyID int64 `json:"currentKeyId,omitempty"`
	// PendingRotation is set when the pairing has a newer active key than
	// the one the request used; Client.Handshake acts on it
	// (keyrotation.go).
	PendingRotation *PendingRotation `json:"pendingRotation,omitempty"`
}

// PendingRotation mirrors branchDAM's pendingRotationDTO
// (internal/httpapi/routes.go in the server repo).
type PendingRotation struct {
	// KeyID is the new key's id, for logging only.
	KeyID int64 `json:"keyId"`
	// APIKey is always "": the new key is never sent in the clear, and a
	// non-empty value is ignored.
	APIKey string `json:"apiKey"`
	// SealedAPIKey is the new key sealed for this device (OpenRotatedKey).
	// Empty when the server cannot deliver it (no BRANCHDAM_SECRET_KEY,
	// or a rotation from before delivery existed): the device then has to
	// be paired again before PreviousKeyExpiresAtUnix.
	SealedAPIKey string `json:"sealedApiKey,omitempty"`
	// PreviousKeyExpiresAtUnix is when the request's key stops working
	// (0 = no expiry set).
	PreviousKeyExpiresAtUnix int64 `json:"previousKeyExpiresAtUnix"`
}

type AgentEventRequest struct {
	EventUUID string `json:"eventUuid,omitempty"`
	AgentID   string `json:"agentId"`
	EventType string `json:"eventType"`
	Payload   string `json:"payload"`
}

type AgentEventResponse struct {
	EventID string `json:"eventId"`
}

type NodeStatusRequest struct {
	NodeUUIDs []string `json:"nodeUuids"`
}

type NodeStatusItem struct {
	NodeUUID       string `json:"nodeUuid"`
	Found          bool   `json:"found"`
	LifecycleState string `json:"lifecycleState,omitempty"`
	Tier           string `json:"tier,omitempty"`
	Verified       bool   `json:"verified"`
	// FullHash is the server's stored BLAKE3-256 for the node. Reclaim
	// requires it to equal (case-insensitively) the hash recorded in
	// queue.db at ingest; an absent value keeps the local file (#217).
	FullHash string `json:"fullHash,omitempty"`
}

type NodeStatusResponse struct {
	Statuses []NodeStatusItem `json:"statuses"`
}

type MobileTelemetry struct {
	DeviceID          string `json:"deviceId"`
	DeviceName        string `json:"deviceName"`
	Platform          string `json:"platform"`
	MountPath         string `json:"mountPath,omitempty"`
	ClientVersion     string `json:"clientVersion"`
	TotalBytes        int64  `json:"totalBytes"`
	FreeBytes         int64  `json:"freeBytes"`
	UsedBytes         int64  `json:"usedBytes"`
	SafeToFreeBytes   int64  `json:"safeToFreeBytes"`
	PendingQueueCount int64  `json:"pendingQueueCount"`
	BatteryLevel      int    `json:"batteryLevel"`
	IsCharging        bool   `json:"isCharging"`
	NetworkType       string `json:"networkType"`
	TimestampUnix     int64  `json:"timestampUnix"`
}

// DefaultAndroidMountPath is the primary external storage root on Android.
const DefaultAndroidMountPath = "/storage/emulated/0"

// DefaultIOSMountPath is the standard application container sandbox root on iOS.
const DefaultIOSMountPath = "/var/mobile"

// DefaultMobileMountPath retains backwards-compatibility for callers referencing the legacy symbol.
const DefaultMobileMountPath = DefaultAndroidMountPath

// ScratchStorageDTO mirrors the scratchStorage sub-object of POST /api/v1/agent/telemetry.
type ScratchStorageDTO struct {
	MountPath            string `json:"mountPath"`
	TotalBytes           int64  `json:"totalBytes"`
	FreeBytes            int64  `json:"freeBytes"`
	UsedBytes            int64  `json:"usedBytes"`
	MirrorsSizeBytes     int64  `json:"mirrorsSizeBytes"`
	RenderCacheSizeBytes int64  `json:"renderCacheSizeBytes"`
	ProxiesSizeBytes     int64  `json:"proxiesSizeBytes"`
	PrunableBytes        int64  `json:"prunableBytes"`
}

// TelemetryInput is the request body sent to POST /api/v1/agent/telemetry.
type TelemetryInput struct {
	AgentID        string            `json:"agentId"`
	ClientVersion  string            `json:"clientVersion,omitempty"`
	TimestampUnix  int64             `json:"timestampUnix"`
	ScratchStorage ScratchStorageDTO `json:"scratchStorage"`
}

type UploadResponse struct {
	OK           bool   `json:"ok,omitempty"`
	NodeUUID     string `json:"nodeUuid"`
	FilePath     string `json:"filePath,omitempty"`
	Status       string `json:"status"`
	SizeBytes    int64  `json:"sizeBytes,omitempty"`
	BytesWritten int64  `json:"bytesWritten,omitempty"`
	Blake3Hash   string `json:"blake3Hash"`
	RelativePath string `json:"relativePath,omitempty"`
	IsDedup      bool   `json:"isDedup,omitempty"`
}

// DedupResponse is returned when the server indicates the uploaded content
// already exists in the library (D1: X-Dedup: true response header).
type DedupResponse struct {
	NodeUUID string
	FilePath string
}

type DedupError struct {
	DedupResponse
}

func (e *DedupError) Error() string {
	return "server dedup: content already exists in library"
}

// AsDedupResponse checks whether err wraps a server dedup response and
// extracts the existing node identity. Returns (zero, false) for non-dedup errors.
func AsDedupResponse(err error) (DedupResponse, bool) {
	if err == nil {
		return DedupResponse{}, false
	}
	if deErr, ok := err.(*DedupError); ok {
		return deErr.DedupResponse, true
	}
	return DedupResponse{}, false
}

// ContentCheckResult is the response body for GET /api/v1/agent/check-content.
type ContentCheckResult struct {
	Found          bool   `json:"found"`
	NodeUUID       string `json:"nodeUuid,omitempty"`
	FilePath       string `json:"filePath,omitempty"`
	LifecycleState string `json:"lifecycleState,omitempty"`
}
