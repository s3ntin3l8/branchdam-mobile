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
}

type AgentEventRequest struct {
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
}

type NodeStatusResponse struct {
	Statuses []NodeStatusItem `json:"statuses"`
}

type MobileTelemetry struct {
	DeviceID          string `json:"deviceId"`
	DeviceName        string `json:"deviceName"`
	Platform          string `json:"platform"`
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
