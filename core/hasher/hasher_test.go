package hasher

import (
	"bytes"
	"crypto/rand"
	"errors"
	"io"
	"testing"
)

func patternBytes(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(i % 251)
	}
	return b
}

// fastHashGoldenVectors are copied verbatim from the server's
// internal/hashing TestFastHashGoldenVectors (the workstation agent pins the
// same values). They cover every boundary of the first/middle/last-2MiB
// sampling: empty, well under 2 MiB (all three regions coincide), exactly
// 2 MiB, and both sides of the 6 MiB point where the regions stop
// overlapping. If one of these fails, mobile fast hashes no longer match
// the server's and must not be "fixed" by updating the expected value.
var fastHashGoldenVectors = []struct {
	name string
	data []byte
	want string
}{
	{"empty", nil, "34c96acdcadb1bbb"},                                            // pragma: allowlist secret
	{"tiny_13b", []byte("hello, world!"), "a17463135ce85764"},                     // pragma: allowlist secret
	{"exactly_2mib", bytes.Repeat([]byte{0xAB}, 2*1024*1024), "c519d4df8b74ba0d"}, // pragma: allowlist secret
	{"3mib_pattern", patternBytes(3 * 1024 * 1024), "2c49f596a9f6d43e"},           // pragma: allowlist secret
	{"7mib_pattern", patternBytes(7 * 1024 * 1024), "18da4c4b0be74ade"},           // pragma: allowlist secret
}

// TestFastHashGoldenVectors pins the ReaderAt-based FastHash to the
// server's golden vectors.
func TestFastHashGoldenVectors(t *testing.T) {
	for _, c := range fastHashGoldenVectors {
		t.Run(c.name, func(t *testing.T) {
			got, err := FastHash(bytes.NewReader(c.data), int64(len(c.data)))
			if err != nil {
				t.Fatalf("FastHash: %v", err)
			}
			if got != c.want {
				t.Errorf("FastHash(%s) = %q, want %q", c.name, got, c.want)
			}
			if got := FastHashBytes(c.data); got != c.want {
				t.Errorf("FastHashBytes(%s) = %q, want %q", c.name, got, c.want)
			}
		})
	}
}

// TestHashReaderGoldenVectors pins the single-pass path the engine actually
// uses at enqueue time (HashReader -> StreamingHasher) to the same server
// golden vectors.
func TestHashReaderGoldenVectors(t *testing.T) {
	for _, c := range fastHashGoldenVectors {
		t.Run(c.name, func(t *testing.T) {
			fast, full, size, err := HashReader(bytes.NewReader(c.data), int64(len(c.data)))
			if err != nil {
				t.Fatalf("HashReader: %v", err)
			}
			if fast != c.want {
				t.Errorf("HashReader fast(%s) = %q, want %q", c.name, fast, c.want)
			}
			if size != int64(len(c.data)) {
				t.Errorf("size = %d, want %d", size, len(c.data))
			}
			wantFull, _ := FullHash(bytes.NewReader(c.data))
			if full != wantFull {
				t.Errorf("full = %q, want %q", full, wantFull)
			}
		})
	}
}

// TestStreamingHasherMatchesFastHash feeds the streaming hasher in
// irregular, non-window-aligned chunks across every sampleRegions boundary
// and checks it agrees with the ReaderAt-based FastHash. Catches a capturer
// that assigns an overlapping byte to only one window.
func TestStreamingHasherMatchesFastHash(t *testing.T) {
	sizes := []int{
		0,
		1,
		2*1024*1024 - 1,
		2 * 1024 * 1024,
		2*1024*1024 + 1,
		3 * 1024 * 1024,
		6*1024*1024 - 1,
		6 * 1024 * 1024,
		10 * 1024 * 1024,
	}
	for _, size := range sizes {
		data := patternBytes(size)
		want, err := FastHash(bytes.NewReader(data), int64(size))
		if err != nil {
			t.Fatalf("size %d: FastHash: %v", size, err)
		}
		s := NewStreamingHasher(int64(size))
		const chunk = 99991 // prime, deliberately not a power of two
		for off := 0; off < len(data); {
			end := min(off+chunk, len(data))
			if _, err := s.Write(data[off:end]); err != nil {
				t.Fatalf("size %d: Write: %v", size, err)
			}
			off = end
		}
		if got := s.FastHash(); got != want {
			t.Errorf("size %d: StreamingHasher.FastHash() = %q, want %q", size, got, want)
		}
	}
}

// TestFastHashSamplesMiddleAndTail checks that the sampled regions are
// actually the ones hashed: a change in the middle or last 2 MiB of a large
// file changes the hash, and a change outside all three regions does not.
func TestFastHashSamplesMiddleAndTail(t *testing.T) {
	const size = 10 * 1024 * 1024
	base := patternBytes(size)
	h := func(b []byte) string { return FastHashBytes(b) }
	orig := h(base)

	for _, off := range []int{size / 2, size - 1} {
		mod := bytes.Clone(base)
		mod[off] ^= 0xFF
		if h(mod) == orig {
			t.Errorf("flipping byte %d (inside a sampled region) did not change FastHash", off)
		}
	}
	// 3 MiB is past the first region [0,2MiB) and before the middle region
	// [4MiB,6MiB): it is deliberately not sampled.
	mod := bytes.Clone(base)
	mod[3*1024*1024] ^= 0xFF
	if h(mod) != orig {
		t.Errorf("flipping an unsampled byte changed FastHash: sampling regions drifted from the server's")
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

	fastExpected, err := FastHash(bytes.NewReader(payload), int64(len(payload)))
	if err != nil {
		t.Fatalf("FastHash failed: %v", err)
	}
	fullExpected, err := FullHash(bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("FullHash failed: %v", err)
	}

	fastGot, fullGot, size, err := HashReader(bytes.NewReader(payload), int64(len(payload)))
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
	fastGot, fullGot, size, err := HashReader(bytes.NewReader([]byte{}), 0)
	if err != nil {
		t.Fatalf("unexpected error on empty stream: %v", err)
	}
	if size != 0 {
		t.Fatalf("expected size 0, got %d", size)
	}
	if fastGot != "34c96acdcadb1bbb" {
		t.Fatalf("empty-stream fast hash = %q, want server golden 34c96acdcadb1bbb", fastGot)
	}
	if len(fullGot) != 64 {
		t.Fatalf("expected 64 hex char full hash, got %q", fullGot)
	}
}

// TestHashReaderSizeMismatch: a stream shorter or longer than the declared
// size (a file modified while it is being hashed) is an error, never a
// silently wrong fast hash.
func TestHashReaderSizeMismatch(t *testing.T) {
	data := patternBytes(1024)
	for _, declared := range []int64{1023, 1025} {
		_, _, _, err := HashReader(bytes.NewReader(data), declared)
		if !errors.Is(err, ErrSizeMismatch) {
			t.Errorf("declared %d for 1024-byte stream: err = %v, want ErrSizeMismatch", declared, err)
		}
	}
}

func TestStreamingHasherPipe(t *testing.T) {
	data := []byte("stream chunk test")
	sh := NewStreamingHasher(int64(len(data)))
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
	if want := FastHashBytes(data); sh.FastHash() != want {
		t.Fatalf("tee'd FastHash = %q, want %q", sh.FastHash(), want)
	}
}
