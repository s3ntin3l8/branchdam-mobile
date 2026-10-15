// Package hasher implements the mobile side of branchDAM's dual
// fingerprinting model: a cheap sampled FastHash and a full-stream BLAKE3-256
// FullHash. FastHash is a byte-for-byte port of the server's
// internal/hashing.FastHash (and the workstation agent's port of it), so a
// fast hash computed on the phone is comparable with one the server or the
// agent computed for the same bytes.
package hasher

import (
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"

	"github.com/cespare/xxhash/v2"
	"github.com/zeebo/blake3"
)

// fastHashSampleSize is 2 MiB per sampled region, matching the server.
const fastHashSampleSize = 2 * 1024 * 1024

// ErrSizeMismatch is returned by HashReader when the stream did not contain
// exactly the number of bytes the caller declared. The sampled FastHash
// depends on the size up front, so a file that grew or shrank while it was
// being read cannot produce a trustworthy fingerprint.
var ErrSizeMismatch = errors.New("hasher: stream length does not match declared size")

// FastHash computes the server's sampled xxHash64 fingerprint: xxHash64 over
// the first, middle, and last 2 MiB of the content (in that order) followed
// by the content size as 8 big-endian bytes. For content under 6 MiB the
// three regions overlap (and coincide entirely under 2 MiB); the overlapping
// bytes are hashed once per region, exactly as the server does. Returns 16
// lowercase hex characters.
func FastHash(r io.ReaderAt, size int64) (string, error) {
	h := xxhash.New()
	buf := make([]byte, fastHashSampleSize)

	for _, reg := range sampleRegions(size) {
		n := reg.end - reg.start
		if n == 0 {
			continue
		}
		read, err := r.ReadAt(buf[:n], reg.start)
		if err != nil && (!errors.Is(err, io.EOF) || int64(read) != n) {
			return "", fmt.Errorf("fast hash: read region [%d,%d): %w", reg.start, reg.end, err)
		}
		if _, err := h.Write(buf[:read]); err != nil {
			return "", fmt.Errorf("fast hash: %w", err)
		}
	}
	return sumWithSize(h, size), nil
}

// FastHashBytes is FastHash over an in-memory buffer.
func FastHashBytes(data []byte) string {
	s := NewStreamingHasher(int64(len(data)))
	_, _ = s.Write(data)
	return s.FastHash()
}

func sumWithSize(h *xxhash.Digest, size int64) string {
	var sizeBuf [8]byte
	binary.BigEndian.PutUint64(sizeBuf[:], uint64(size))
	_, _ = h.Write(sizeBuf[:])
	return fmt.Sprintf("%016x", h.Sum64())
}

type region struct{ start, end int64 }

// sampleRegions returns the (first, middle, last) 2 MiB windows for size,
// clamped to [0, size] so they overlap rather than run out of bounds for
// small content. Identical to the server's sampleRegions.
func sampleRegions(size int64) [3]region {
	if size <= 0 {
		return [3]region{{0, 0}, {0, 0}, {0, 0}}
	}
	clamp := func(v int64) int64 {
		if v < 0 {
			return 0
		}
		if v > size {
			return size
		}
		return v
	}

	first := region{0, clamp(fastHashSampleSize)}
	middleStart := clamp(size/2 - fastHashSampleSize/2)
	middle := region{middleStart, clamp(middleStart + fastHashSampleSize)}
	lastStart := clamp(size - fastHashSampleSize)
	last := region{lastStart, size}
	return [3]region{first, middle, last}
}

// FullHash computes the BLAKE3-256 (64 hex characters) hash of a reader.
func FullHash(r io.Reader) (string, error) {
	hasher := blake3.New()
	if _, err := io.Copy(hasher, r); err != nil {
		return "", err
	}
	return hex.EncodeToString(hasher.Sum(nil)), nil
}

// StreamingHasher computes the sampled FastHash and the BLAKE3-256 FullHash
// in a single forward pass over content whose size is known up front (a
// file, after Stat). Write must be fed the content sequentially from offset
// 0; it captures the bytes that fall inside the three FastHash sample
// windows (at most 6 MiB of buffer regardless of content size) and streams
// every byte into BLAKE3. FastHash is only meaningful once exactly size
// bytes have been written; HashReader enforces that.
type StreamingHasher struct {
	size       int64
	regions    [3]region
	bufs       [3][]byte
	fullHasher hash.Hash
	bytesRead  int64
}

// NewStreamingHasher returns a StreamingHasher for content of the given size.
func NewStreamingHasher(size int64) *StreamingHasher {
	s := &StreamingHasher{
		size:       size,
		regions:    sampleRegions(size),
		fullHasher: blake3.New(),
	}
	for i, r := range s.regions {
		s.bufs[i] = make([]byte, r.end-r.start)
	}
	return s
}

// Write copies p's bytes into every sample window they fall in (more than
// one for overlapping windows on content under 6 MiB) and updates the
// BLAKE3 digest. Bytes past the declared size are counted (so HashReader
// can detect the mismatch) but never captured.
func (s *StreamingHasher) Write(p []byte) (n int, err error) {
	chunkStart := s.bytesRead
	chunkEnd := chunkStart + int64(len(p))
	for i, r := range s.regions {
		if r.end <= r.start {
			continue
		}
		lo := max(chunkStart, r.start)
		hi := min(chunkEnd, r.end)
		if lo < hi {
			copy(s.bufs[i][lo-r.start:hi-r.start], p[lo-chunkStart:hi-chunkStart])
		}
	}
	s.bytesRead = chunkEnd
	return s.fullHasher.Write(p)
}

// BytesRead returns total bytes streamed through the hasher.
func (s *StreamingHasher) BytesRead() int64 {
	return s.bytesRead
}

// FastHash returns the sampled 64-bit fast hash string (16 hex characters),
// identical to FastHash(content, size) when exactly size bytes were written.
func (s *StreamingHasher) FastHash() string {
	h := xxhash.New()
	for _, buf := range s.bufs {
		_, _ = h.Write(buf)
	}
	return sumWithSize(h, s.size)
}

// FullHash returns the 64-hex character BLAKE3-256 digest string.
func (s *StreamingHasher) FullHash() string {
	return hex.EncodeToString(s.fullHasher.Sum(nil))
}

// HashReader consumes r, which must hold exactly size bytes (e.g. an opened
// file and the size from its Stat), in one pass and returns its FastHash,
// FullHash, and size. It returns ErrSizeMismatch if the stream was shorter
// or longer than size, which happens when the file changes while it is
// being hashed.
func HashReader(r io.Reader, size int64) (fastHash string, fullHash string, sizeBytes int64, err error) {
	if size < 0 {
		return "", "", 0, fmt.Errorf("hasher: negative size %d", size)
	}
	s := NewStreamingHasher(size)
	buf := make([]byte, 64*1024)
	for {
		n, rErr := r.Read(buf)
		if n > 0 {
			if _, wErr := s.Write(buf[:n]); wErr != nil {
				return "", "", 0, wErr
			}
		}
		if rErr != nil {
			if rErr == io.EOF {
				break
			}
			return "", "", 0, rErr
		}
	}
	if s.BytesRead() != size {
		return "", "", 0, fmt.Errorf("%w: read %d bytes, expected %d", ErrSizeMismatch, s.BytesRead(), size)
	}
	return s.FastHash(), s.FullHash(), s.BytesRead(), nil
}
