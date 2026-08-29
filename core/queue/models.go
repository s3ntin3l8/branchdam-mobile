package queue

type UploadStatus string

const (
	UploadPending    UploadStatus = "PENDING"
	UploadInProgress UploadStatus = "IN_PROGRESS"
	UploadCompleted  UploadStatus = "COMPLETED"
	UploadFailed     UploadStatus = "FAILED"
)

type EventStatus string

const (
	EventPending EventStatus = "PENDING"
	EventSent    EventStatus = "SENT"
	EventFailed  EventStatus = "FAILED"
)

type UploadItem struct {
	ID              int64        `json:"id"`
	LocalPath       string       `json:"localPath"`
	TargetFilename  string       `json:"targetFilename"`
	TargetDir       string       `json:"targetDir"`
	FastHash        string       `json:"fastHash"`
	Blake3Hash      string       `json:"blake3Hash"`
	CameraModel     string       `json:"cameraModel,omitempty"`
	SizeBytes       int64        `json:"sizeBytes"`
	CapturedAtUnix  int64        `json:"capturedAtUnix"`
	Status          UploadStatus `json:"status"`
	RetryCount      int          `json:"retryCount"`
	LastAttemptUnix int64        `json:"lastAttemptUnix"`
	ErrorMsg        string       `json:"errorMsg,omitempty"`
	NodeUUID        string       `json:"nodeUuid,omitempty"`
	CreatedAtUnix   int64        `json:"createdAtUnix"`
	UpdatedAtUnix   int64        `json:"updatedAtUnix"`
}

type EventItem struct {
	ID              int64       `json:"id"`
	EventUUID       string      `json:"eventUuid"`
	EventType       string      `json:"eventType"`
	PayloadJSON     string      `json:"payloadJson"`
	Status          EventStatus `json:"status"`
	RetryCount      int         `json:"retryCount"`
	LastAttemptUnix int64       `json:"lastAttemptUnix"`
	ErrorMsg        string      `json:"errorMsg,omitempty"`
	CreatedAtUnix   int64       `json:"createdAtUnix"`
	UpdatedAtUnix   int64       `json:"updatedAtUnix"`
}

type LocalMediaState struct {
	LocalID        string `json:"localId"`
	NodeUUID       string `json:"nodeUuid"`
	Blake3Hash     string `json:"blake3Hash"`
	LifecycleState string `json:"lifecycleState"`
	IsOffloaded    bool   `json:"isOffloaded"`
	CreatedAtUnix  int64  `json:"createdAtUnix"`
	UpdatedAtUnix  int64  `json:"updatedAtUnix"`
}
