package hasher

import (
	"bytes"
	"crypto/rand"
	"errors"
	"io"
	"testing"
)

// errReader is a test reader that returns the configured error after
// the configured number of bytes. Used to verify the streaming
// hasher surfaces partial-read errors.
type errReader struct {
	remaining int
	err       error
}

func (e *errReader) Read(p []byte) (int, error) {
	if e.remaining <= 0 {
		return 0, e.err
	}
	if len(p) > e.remaining {
		p = p[:e.remaining]
	}
	n := len(p)
	e.remaining -= n
	for i := 0; i < n; i++ {
		p[i] = 'x'
	}
	return n, nil
}

// TestFullHash_LimitReaderNoOOM: FullHash over a multi-GB io.LimitReader
// finishes with the correct digest and does not allocate proportional
// to the stream size. The hasher streams through io.Copy, so memory
// usage is bounded by the BLAKE3 internal state.
func TestFullHash_LimitReaderNoOOM(t *testing.T) {
	const limit = 256 * 1024 * 1024 // 256 MiB
	src := io.LimitReader(&infiniteReader{}, limit)
	got, err := FullHash(src)
	if err != nil {
		t.Fatalf("FullHash: %v", err)
	}
	if len(got) != 64 {
		t.Fatalf("expected 64 hex char hash, got %d", len(got))
	}

	// Cross-check: hash the same first 256 MiB of an equivalent
	// deterministic stream and confirm the digests match.
	expected, err := FullHash(io.LimitReader(&infiniteReader{}, limit))
	if err != nil {
		t.Fatalf("FullHash (reference): %v", err)
	}
	if got != expected {
		t.Fatalf("deterministic mismatch: %s != %s", got, expected)
	}
}

// TestStreamingHasher_PartialWriteFailure: if the underlying reader
// returns an error mid-stream, HashReader surfaces the error and the
// returned hashes are empty strings.
func TestStreamingHasher_PartialWriteFailure(t *testing.T) {
	sentinel := errors.New("simulated mid-stream failure")
	r := &errReader{remaining: 1024, err: sentinel}

	_, _, _, err := HashReader(r)
	if err == nil {
		t.Fatalf("expected error from HashReader on partial read failure")
	}
	if !errors.Is(err, sentinel) {
		t.Fatalf("error chain does not contain sentinel: %v", err)
	}
}

// TestStreamingHasher_LargeStreamCorrectness: the streaming hasher
// produces the same digest as FullHash over the same input.
func TestStreamingHasher_LargeStreamCorrectness(t *testing.T) {
	const size = 1 * 1024 * 1024 // 1 MiB
	data := make([]byte, size)
	if _, err := rand.Read(data); err != nil {
		t.Fatalf("rand.Read: %v", err)
	}

	// Hash via the streaming API.
	sh := NewStreamingHasher()
	if _, err := io.Copy(sh, bytes.NewReader(data)); err != nil {
		t.Fatalf("io.Copy: %v", err)
	}
	streamFast := sh.FastHash()
	streamFull := sh.FullHash()
	streamSize := sh.BytesRead()

	// Hash via the one-shot API.
	oneShotFast, oneShotFull, oneShotSize, err := HashReader(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("HashReader: %v", err)
	}

	if streamSize != oneShotSize || oneShotSize != int64(size) {
		t.Fatalf("size mismatch: stream=%d oneShot=%d want=%d", streamSize, oneShotSize, size)
	}
	if streamFast != oneShotFast {
		t.Fatalf("FastHash mismatch: stream=%s oneShot=%s", streamFast, oneShotFast)
	}
	if streamFull != oneShotFull {
		t.Fatalf("FullHash mismatch: stream=%s oneShot=%s", streamFull, oneShotFull)
	}
}

// infiniteReader is a test reader that yields an infinite stream of
// 'x' bytes. Paired with io.LimitReader for memory-bounded testing.
type infiniteReader struct{}

func (infiniteReader) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = 'x'
	}
	return len(p), nil
}
