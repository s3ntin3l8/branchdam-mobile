package hasher

import (
	"bytes"
	"testing"
)

func TestFastHash(t *testing.T) {
	h := FastHash([]byte("hello branchdam"))
	if h == "" {
		t.Fatal("expected non-empty fast hash")
	}
}

func TestFullHash(t *testing.T) {
	data := []byte("hello branchdam blake3")
	h, err := FullHash(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(h) != 64 {
		t.Fatalf("expected 64 hex char BLAKE3 hash, got %d chars: %s", len(h), h)
	}
}
