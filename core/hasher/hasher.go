package hasher

import (
	"encoding/hex"
	"io"

	"github.com/cespare/xxhash/v2"
	"github.com/zeebo/blake3"
)

// FastHash computes a 64-bit fast hash as a hex string.
func FastHash(data []byte) string {
	h := xxhash.Sum64(data)
	var buf [8]byte
	for i := uint(0); i < 8; i++ {
		buf[7-i] = byte(h >> (i * 8))
	}
	return hex.EncodeToString(buf[:])
}

// FullHash computes the BLAKE3-256 hash of a reader.
func FullHash(r io.Reader) (string, error) {
	hasher := blake3.New()
	if _, err := io.Copy(hasher, r); err != nil {
		return "", err
	}
	return hex.EncodeToString(hasher.Sum(nil)), nil
}
