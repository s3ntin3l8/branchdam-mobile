package hasher

import (
	"bytes"
	"crypto/rand"
	"io"
	"testing"
)

func TestFastHash(t *testing.T) {
	h1 := FastHash([]byte("hello branchdam"))
	h2 := FastHash([]byte("hello branchdam"))
	h3 := FastHash([]byte("hello branchdam!"))

	if h1 == "" || len(h1) != 16 {
		t.Fatalf("expected 16 hex char fast hash, got %q", h1)
	}
	if h1 != h2 {
		t.Fatalf("expected deterministic hash: %q != %q", h1, h2)
	}
	if h1 == h3 {
		t.Fatalf("expected distinct hash for different input: %q == %q", h1, h3)
	}
}

func TestFullHash(t *testing.T) {
	data := []byte("hello branchdam blake3 full hash")
	h, err := FullHash(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(h) != 64 {
		t.Fatalf("expected 64 hex char BLAKE3 hash, got %d chars: %s", len(h), h)
	}
}

func TestStreamingHasher(t *testing.T) {
	payload := make([]byte, 256*1024)
	if _, err := rand.Read(payload); err != nil {
		t.Fatalf("failed to generate random test data: %v", err)
	}

	fastExpected := FastHash(payload)
	fullExpected, err := FullHash(bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("FullHash failed: %v", err)
	}

	fastGot, fullGot, size, err := HashReader(bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("HashReader failed: %v", err)
	}

	if size != int64(len(payload)) {
		t.Fatalf("expected size %d, got %d", len(payload), size)
	}
	if fastGot != fastExpected {
		t.Fatalf("fast hash mismatch: got %q, expected %q", fastGot, fastExpected)
	}
	if fullGot != fullExpected {
		t.Fatalf("full hash mismatch: got %q, expected %q", fullGot, fullExpected)
	}
}

func TestStreamingHasherZeroBytes(t *testing.T) {
	fastGot, fullGot, size, err := HashReader(bytes.NewReader([]byte{}))
	if err != nil {
		t.Fatalf("unexpected error on empty stream: %v", err)
	}
	if size != 0 {
		t.Fatalf("expected size 0, got %d", size)
	}
	if len(fastGot) != 16 {
		t.Fatalf("expected 16 hex char fast hash, got %q", fastGot)
	}
	if len(fullGot) != 64 {
		t.Fatalf("expected 64 hex char full hash, got %q", fullGot)
	}
}

func TestStreamingHasherPipe(t *testing.T) {
	data := []byte("stream chunk test")
	sh := NewStreamingHasher()
	tee := io.TeeReader(bytes.NewReader(data), sh)

	out, err := io.ReadAll(tee)
	if err != nil {
		t.Fatalf("io.ReadAll failed: %v", err)
	}
	if !bytes.Equal(out, data) {
		t.Fatalf("output bytes do not match input")
	}
	if sh.BytesRead() != int64(len(data)) {
		t.Fatalf("expected %d bytes read, got %d", len(data), sh.BytesRead())
	}
}
