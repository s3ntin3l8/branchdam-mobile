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
	OK         bool   `json:"ok"`
	NodeUUID   string `json:"nodeUuid"`
	FilePath   string `json:"filePath"`
	Status     string `json:"status"`
	SizeBytes  int64  `json:"sizeBytes"`
	Blake3Hash string `json:"blake3Hash"`
}
