package branchdam

import (
	"path/filepath"
	"testing"
)

func TestVersionNonEmpty(t *testing.T) {
	if v := Version(); v == "" {
		t.Fatalf("Version() returned empty string")
	}
}

func TestNewEngineDefaultsClientVersion(t *testing.T) {
	dir := t.TempDir()
	e, err := NewEngine(EngineOptions{
		DBPath:            filepath.Join(dir, "engine.db"),
		BaseURL:           "http://localhost:8080",
		AgentID:           "test-agent",
		DevCleartextHosts: []string{"localhost"},
	})
	if err != nil {
		t.Fatalf("NewEngine: %v", err)
	}
	defer e.Close()
	if got := e.Options().ClientVersion; got == "" {
		t.Fatalf("NewEngine did not default ClientVersion")
	}
}

func TestNewEnginePreservesExplicitClientVersion(t *testing.T) {
	dir := t.TempDir()
	e, err := NewEngine(EngineOptions{
		DBPath:            filepath.Join(dir, "engine.db"),
		BaseURL:           "http://localhost:8080",
		AgentID:           "test-agent",
		ClientVersion:     "1.2.3-custom",
		DevCleartextHosts: []string{"localhost"},
	})
	if err != nil {
		t.Fatalf("NewEngine: %v", err)
	}
	defer e.Close()
	if got := e.Options().ClientVersion; got != "1.2.3-custom" {
		t.Fatalf("ClientVersion = %q, want %q", got, "1.2.3-custom")
	}
}

func TestEngineCloseIdempotent(t *testing.T) {
	dir := t.TempDir()
	e, err := NewEngine(EngineOptions{
		DBPath:            filepath.Join(dir, "engine.db"),
		BaseURL:           "http://localhost:8080",
		AgentID:           "test-agent",
		DevCleartextHosts: []string{"localhost"},
	})
	if err != nil {
		t.Fatalf("NewEngine: %v", err)
	}
	if err := e.Close(); err != nil {
		t.Fatalf("first Close: %v", err)
	}
	if err := e.Close(); err != nil {
		t.Fatalf("second Close: %v", err)
	}
}

func TestNewEngineValidation(t *testing.T) {
	tests := []struct {
		name string
		opts EngineOptions
	}{
		{"empty DBPath", EngineOptions{BaseURL: "https://x", AgentID: "a"}},
		{"empty BaseURL", EngineOptions{DBPath: "/tmp/x.db", AgentID: "a"}},
		{"bad BaseURL", EngineOptions{DBPath: "/tmp/x.db", BaseURL: "://bad", AgentID: "a"}},
		{"non-http scheme", EngineOptions{DBPath: "/tmp/x.db", BaseURL: "ftp://x", AgentID: "a"}},
		{"http without cleartext host", EngineOptions{DBPath: "/tmp/x.db", BaseURL: "http://attacker.example.com", AgentID: "a"}},
		{"empty AgentID", EngineOptions{DBPath: "/tmp/x.db", BaseURL: "https://x"}},
		{"negative timeout", EngineOptions{DBPath: "/tmp/x.db", BaseURL: "https://x", AgentID: "a", HTTPTimeoutSec: -1}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := NewEngine(tt.opts)
			if err == nil {
				t.Fatalf("NewEngine: expected INVALID_INPUT error, got nil")
			}
			be, ok := err.(*Error)
			if !ok {
				t.Fatalf("NewEngine: error is not *Error: %T %v", err, err)
			}
			if be.Code != "INVALID_INPUT" {
				t.Fatalf("NewEngine: Code = %q, want %q", be.Code, "INVALID_INPUT")
			}
		})
	}
}

func TestNewEngineValidatesSchemeHTTPSOrHTTP(t *testing.T) {
	dir := t.TempDir()
	for _, scheme := range []string{"https", "http"} {
		t.Run(scheme, func(t *testing.T) {
			_, err := NewEngine(EngineOptions{
				DBPath:            filepath.Join(dir, "engine-"+scheme+".db"),
				BaseURL:           scheme + "://localhost:8080",
				AgentID:           "a",
				DevCleartextHosts: []string{"localhost"},
			})
			if err != nil {
				t.Fatalf("NewEngine with %s scheme: %v", scheme, err)
			}
		})
	}
}

func TestNewEngineRejectsHTTPWithoutCleartextHost(t *testing.T) {
	dir := t.TempDir()
	_, err := NewEngine(EngineOptions{
		DBPath:  filepath.Join(dir, "engine.db"),
		BaseURL: "http://attacker.example.com",
		AgentID: "a",
	})
	if err == nil {
		t.Fatalf("NewEngine: expected INVALID_INPUT error for HTTP without cleartext host, got nil")
	}
	be, ok := err.(*Error)
	if !ok {
		t.Fatalf("NewEngine: error is not *Error: %T %v", err, err)
	}
	if be.Code != "INVALID_INPUT" {
		t.Fatalf("NewEngine: Code = %q, want %q", be.Code, "INVALID_INPUT")
	}
}

func TestNewEngineAllowsHTTPWithCleartextHost(t *testing.T) {
	dir := t.TempDir()
	_, err := NewEngine(EngineOptions{
		DBPath:            filepath.Join(dir, "engine.db"),
		BaseURL:           "http://10.0.2.2:8080",
		AgentID:           "a",
		DevCleartextHosts: []string{"10.0.2.2", "localhost"},
	})
	if err != nil {
		t.Fatalf("NewEngine with HTTP and cleartext host: %v", err)
	}
}

func TestNewEngineRejectsHTTPWithWrongCleartextHost(t *testing.T) {
	dir := t.TempDir()
	_, err := NewEngine(EngineOptions{
		DBPath:            filepath.Join(dir, "engine.db"),
		BaseURL:           "http://10.0.2.2:8080",
		AgentID:           "a",
		DevCleartextHosts: []string{"localhost"},
	})
	if err == nil {
		t.Fatalf("NewEngine: expected INVALID_INPUT error for HTTP with non-matching cleartext host, got nil")
	}
	be, ok := err.(*Error)
	if !ok {
		t.Fatalf("NewEngine: error is not *Error: %T %v", err, err)
	}
	if be.Code != "INVALID_INPUT" {
		t.Fatalf("NewEngine: Code = %q, want %q", be.Code, "INVALID_INPUT")
	}
}

func TestIsHostInCleartextAllowlist(t *testing.T) {
	tests := []struct {
		host     string
		list     []string
		expected bool
	}{
		{"localhost", []string{"localhost", "10.0.2.2"}, true},
		{"10.0.2.2", []string{"localhost", "10.0.2.2"}, true},
		{"attacker.example.com", []string{"localhost", "10.0.2.2"}, false},
		{"localhost", nil, false},
		{"localhost", []string{}, false},
		{"LOCALHOST", []string{"localhost"}, false},
	}
	for _, tt := range tests {
		t.Run(tt.host, func(t *testing.T) {
			got := isHostInCleartextAllowlist(tt.host, tt.list)
			if got != tt.expected {
				t.Fatalf("isHostInCleartextAllowlist(%q, %v) = %v, want %v", tt.host, tt.list, got, tt.expected)
			}
		})
	}
}

// TestIsMediaOffloaded_FailClosedOnDBError: per B.2.3, the engine must
// surface DB errors to the shell so it can refuse to delete. The
// current branchdam wrapper maps queue errors to DB_ERROR.
func TestIsMediaOffloaded_FailClosedOnDBError(t *testing.T) {
	dir := t.TempDir()
	e, err := NewEngine(EngineOptions{
		DBPath:            filepath.Join(dir, "engine.db"),
		BaseURL:           "http://localhost:8080",
		AgentID:           "test-agent",
		DevCleartextHosts: []string{"localhost"},
	})
	if err != nil {
		t.Fatalf("NewEngine: %v", err)
	}
	// Close the queue underneath the engine to force a DB error.
	if err := e.queue.Close(); err != nil {
		t.Fatalf("close queue: %v", err)
	}
	_, err = e.IsMediaOffloaded("any-id")
	if err == nil {
		t.Fatalf("IsMediaOffloaded: expected DB_ERROR after closing queue, got nil")
	}
	be, ok := err.(*Error)
	if !ok {
		t.Fatalf("error type = %T, want *Error", err)
	}
	if be.Code != "DB_ERROR" {
		t.Fatalf("Code = %q, want %q", be.Code, "DB_ERROR")
	}
}
