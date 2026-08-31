package branchdam

import "testing"

func TestVersionNonEmpty(t *testing.T) {
	if v := Version(); v == "" {
		t.Fatalf("Version() returned empty string")
	}
}

func TestNewEngineDefaultsClientVersion(t *testing.T) {
	e, err := NewEngine(EngineOptions{})
	if err != nil {
		t.Fatalf("NewEngine: %v", err)
	}
	defer e.Close()
	if got := e.Options().ClientVersion; got == "" {
		t.Fatalf("NewEngine did not default ClientVersion")
	}
}

func TestNewEnginePreservesExplicitClientVersion(t *testing.T) {
	e, err := NewEngine(EngineOptions{ClientVersion: "1.2.3-custom"})
	if err != nil {
		t.Fatalf("NewEngine: %v", err)
	}
	defer e.Close()
	if got := e.Options().ClientVersion; got != "1.2.3-custom" {
		t.Fatalf("ClientVersion = %q, want %q", got, "1.2.3-custom")
	}
}

func TestEngineCloseIdempotent(t *testing.T) {
	e, err := NewEngine(EngineOptions{})
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
