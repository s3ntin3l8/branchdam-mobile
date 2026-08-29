package hasher

import (
	"encoding/hex"
	"hash"
	"io"

	"github.com/cespare/xxhash/v2"
	"github.com/zeebo/blake3"
)

// FastHash computes a 64-bit fast hash as a hex string from bytes.
func FastHash(data []byte) string {
	h := xxhash.Sum64(data)
	var buf [8]byte
	for i := uint(0); i < 8; i++ {
		buf[7-i] = byte(h >> (i * 8))
	}
	return hex.EncodeToString(buf[:])
}

// FullHash computes the BLAKE3-256 (64 hex characters) hash of a reader.
func FullHash(r io.Reader) (string, error) {
	hasher := blake3.New()
	if _, err := io.Copy(hasher, r); err != nil {
		return "", err
	}
	return hex.EncodeToString(hasher.Sum(nil)), nil
}

// StreamingHasher calculates both xxHash64 FastHash and BLAKE3-256 FullHash
// in a single pass without buffering file contents in memory.
type StreamingHasher struct {
	fastHasher *xxhash.Digest
	fullHasher hash.Hash
	bytesRead  int64
}

// NewStreamingHasher returns a newly initialized StreamingHasher.
func NewStreamingHasher() *StreamingHasher {
	return &StreamingHasher{
		fastHasher: xxhash.New(),
		fullHasher: blake3.New(),
	}
}

// Write updates both hashing digests with the incoming slice.
func (s *StreamingHasher) Write(p []byte) (n int, err error) {
	s.bytesRead += int64(len(p))
	if _, err := s.fastHasher.Write(p); err != nil {
		return 0, err
	}
	return s.fullHasher.Write(p)
}

// BytesRead returns total bytes streamed through the hasher.
func (s *StreamingHasher) BytesRead() int64 {
	return s.bytesRead
}

// FastHash returns the 64-bit fast hash string.
func (s *StreamingHasher) FastHash() string {
	h := s.fastHasher.Sum64()
	var buf [8]byte
	for i := uint(0); i < 8; i++ {
		buf[7-i] = byte(h >> (i * 8))
	}
	return hex.EncodeToString(buf[:])
}

// FullHash returns the 64-hex character BLAKE3-256 digest string.
func (s *StreamingHasher) FullHash() string {
	return hex.EncodeToString(s.fullHasher.Sum(nil))
}

// HashReader consumes a reader and returns its FastHash, FullHash, and size.
func HashReader(r io.Reader) (fastHash string, fullHash string, sizeBytes int64, err error) {
	s := NewStreamingHasher()
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
	return s.FastHash(), s.FullHash(), s.BytesRead(), nil
}
