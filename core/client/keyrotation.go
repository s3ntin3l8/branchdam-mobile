package client

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Rotated-key delivery (branchDAM S13, docs/agent-api.md "Key rotation
// delivery" in the server repo). When an operator rotates this device's
// key, the server keeps the old key working for a grace window and hands
// the new key out on the next handshake, sealed so that only the holder
// of the key that request authenticated with can open it. The first
// request made with the new key adopts it server-side and revokes every
// older key of the pairing at once.
//
// The client side, in order:
//
//  1. Handshake answers with pendingRotation.sealedApiKey.
//  2. OpenRotatedKey opens it with the key the handshake authenticated with.
//  3. The KeyStore (SetKeyStore) stores it durably in the platform's
//     secure storage. A failure, no store, or a value that does not open
//     leaves the client on its current key.
//  4. Only then does the client switch its in-memory key, and it adopts
//     the new key at once with a confirming handshake.
//  5. If the new key is refused (401) on that first use, the client goes
//     back to the old key, retries that one request with it, stores the
//     old key again once the server accepts it, and never tries the
//     refused key id again in this process.

// rotationSealInfo is the HMAC-SHA256 message that derives the rotation
// transport key from the current API key. Must match branchDAM's
// pairing.RotationSealInfo byte for byte.
const rotationSealInfo = "branchdam-key-rotation-v1"

// sealedKeyPrefix is the version tag on pendingRotation.sealedApiKey.
const sealedKeyPrefix = "v1:"

const handshakePath = "/api/v1/agent/handshake"

// ErrSealedKeyInvalid is returned by OpenRotatedKey for any value that
// does not open: wrong version prefix, bad base64, too short, a GCM tag
// mismatch (wrong current key, tampered value), or a plaintext that cannot
// be an API key. The causes are deliberately not distinguished. Per the
// contract a value that does not open is ignored; the client never falls
// back to anything else.
var ErrSealedKeyInvalid = errors.New("sealed rotated key does not open with the current API key")

// OpenRotatedKey opens a pendingRotation.sealedApiKey value with
// currentKey, the API key the handshake that carried it authenticated
// with: "v1:" + standard base64 (with padding) of nonce (12 bytes) ||
// ciphertext || GCM tag (16 bytes), AES-256-GCM with no associated data,
// keyed by HMAC-SHA256(key = currentKey, message =
// "branchdam-key-rotation-v1"). This is the standard-library equivalent of
// branchDAM's pairing.OpenRotatedKey.
func OpenRotatedKey(currentKey, sealed string) (string, error) {
	if currentKey == "" || !strings.HasPrefix(sealed, sealedKeyPrefix) {
		return "", ErrSealedKeyInvalid
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(sealed, sealedKeyPrefix))
	if err != nil {
		return "", ErrSealedKeyInvalid
	}
	mac := hmac.New(sha256.New, []byte(currentKey))
	mac.Write([]byte(rotationSealInfo))
	block, err := aes.NewCipher(mac.Sum(nil))
	if err != nil {
		return "", fmt.Errorf("rotation cipher: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", fmt.Errorf("rotation GCM: %w", err)
	}
	if len(raw) < gcm.NonceSize()+gcm.Overhead() {
		return "", ErrSealedKeyInvalid
	}
	plain, err := gcm.Open(nil, raw[:gcm.NonceSize()], raw[gcm.NonceSize():], nil)
	if err != nil {
		return "", ErrSealedKeyInvalid
	}
	if !validAPIKey(plain) {
		return "", ErrSealedKeyInvalid
	}
	return string(plain), nil
}

// validAPIKey reports whether b can be sent as an X-API-Key header value:
// non-empty, at most 1 KiB, printable ASCII without spaces.
func validAPIKey(b []byte) bool {
	if len(b) == 0 || len(b) > 1024 {
		return false
	}
	for _, c := range b {
		if c < 0x21 || c > 0x7e {
			return false
		}
	}
	return true
}

// KeyStore durably stores the device's API key in the platform's secure
// storage (Android Keystore-backed EncryptedSharedPreferences, iOS
// Keychain), replacing the stored one, so the next launch opens the engine
// with it. The client calls it before switching to a rotated key, and with
// the old key again if the server refuses the new one. A non-nil error
// means nothing usable was stored: the client then stays on its current
// key.
type KeyStore func(ctx context.Context, apiKey string) error

// keyAdoption is the client's state between switching to a rotated key
// and the server's first answer to a request made with it.
type keyAdoption struct {
	newKey string
	oldKey string
	keyID  int64
}

// keyState is the Client's mutable key state; mu also guards Client.apiKey.
type keyState struct {
	mu sync.Mutex
	// store is the platform key store; nil means rotations are only logged.
	store KeyStore
	// adopting is non-nil from the switch to a rotated key until the
	// server first answers a request made with it.
	adopting *keyAdoption
	// restoreKey is the previous key after the server refused the
	// rotated one; it is stored again once the server accepts it.
	restoreKey string
	// refused are rotated key ids the server refused on first use; they
	// are not adopted again in this process.
	refused map[int64]struct{}

	// lastHandshake is when a handshake last succeeded (HandshakeDue).
	lastHandshake time.Time

	// adoptMu serializes adoptRotation.
	adoptMu sync.Mutex
}

// HandshakeDue reports whether no handshake has succeeded within interval,
// so a caller that wants rotated keys picked up promptly (the bound
// Engine's SyncBatch) knows to make one.
func (c *Client) HandshakeDue(interval time.Duration) bool {
	c.keys.mu.Lock()
	defer c.keys.mu.Unlock()
	return c.keys.lastHandshake.IsZero() || time.Since(c.keys.lastHandshake) >= interval
}

func (c *Client) markHandshake() {
	c.keys.mu.Lock()
	c.keys.lastHandshake = time.Now()
	c.keys.mu.Unlock()
}

// SetKeyStore installs the platform key store that lets this client adopt
// rotated keys from the handshake. Without one (nil) it only logs that a
// rotation is pending: a key that lives only in memory would be lost on
// the next launch after the server has already revoked the old one.
func (c *Client) SetKeyStore(store KeyStore) {
	c.keys.mu.Lock()
	defer c.keys.mu.Unlock()
	c.keys.store = store
}

// currentKey returns the API key the next request should use.
func (c *Client) currentKey() string {
	c.keys.mu.Lock()
	defer c.keys.mu.Unlock()
	return c.apiKey
}

// observeAuth runs after every response that reached the server, with the
// key the request used and the response status. It completes or rolls
// back a pending key adoption and returns a key to retry the same request
// with, or "" for no retry. Callers retry at most once; a request whose
// body cannot be replayed (UploadStream) ignores the result, and the next
// request uses the reverted key.
func (c *Client) observeAuth(ctx context.Context, usedKey string, status int) string {
	ok := status >= 200 && status < 300
	unauthorized := status == http.StatusUnauthorized
	if !ok && !unauthorized {
		return ""
	}

	var restore, retry string
	var store KeyStore
	c.keys.mu.Lock()
	switch {
	case c.keys.adopting != nil && usedKey == c.keys.adopting.newKey:
		a := c.keys.adopting
		c.keys.adopting = nil
		if ok {
			slog.Info("branchdam: the server accepted the rotated API key; the previous key is now revoked", "keyId", a.keyID)
			break
		}
		if c.keys.refused == nil {
			c.keys.refused = map[int64]struct{}{}
		}
		c.keys.refused[a.keyID] = struct{}{}
		if c.apiKey == a.newKey { // pragma: allowlist secret
			c.apiKey = a.oldKey // pragma: allowlist secret
		}
		c.keys.restoreKey = a.oldKey
		retry = a.oldKey
		slog.Warn("branchdam: the server refused the rotated API key on its first use; falling back to the previous key", "keyId", a.keyID)
	case c.keys.restoreKey != "" && usedKey == c.keys.restoreKey:
		if ok {
			restore = c.keys.restoreKey
			store = c.keys.store
		} else {
			slog.Error("branchdam: the server refused both the rotated API key and the previous one; pair this device again")
		}
		c.keys.restoreKey = ""
	case unauthorized && usedKey != c.apiKey:
		// This request started before the client switched keys and was
		// refused with the stale key: retry it once with the current one.
		retry = c.apiKey
	}
	c.keys.mu.Unlock()

	if restore != "" && store != nil {
		if err := store(ctx, restore); err != nil {
			slog.Error("branchdam: could not store the previous API key again after the rotated key was refused; pair this device again before the app restarts", "err", err)
			c.keys.mu.Lock()
			if c.keys.restoreKey == "" && c.apiKey == restore { // pragma: allowlist secret
				c.keys.restoreKey = restore // try again after the next accepted request
			}
			c.keys.mu.Unlock()
		} else {
			slog.Info("branchdam: stored the previous API key again after the rotated key was refused")
		}
	}
	return retry
}

// adoptRotation acts on a handshake response's pendingRotation: open the
// sealed key with usedKey (the key that handshake authenticated with),
// store it, switch to it, and adopt it at once with a second handshake,
// whose response it returns. It returns nil whenever it did not get that
// far; the caller then keeps the first response.
func (c *Client) adoptRotation(ctx context.Context, req HandshakeRequest, usedKey string, rot *PendingRotation) *HandshakeResponse {
	logAttrs := []any{"keyId", rot.KeyID, "previousKeyExpiresAtUnix", rot.PreviousKeyExpiresAtUnix}
	if rot.SealedAPIKey == "" {
		slog.Warn("branchdam: the server rotated this device's API key but cannot deliver it; pair this device again before the current key expires", logAttrs...)
		return nil
	}
	if !c.keys.adoptMu.TryLock() {
		return nil // another handshake is adopting it
	}
	defer c.keys.adoptMu.Unlock()

	c.keys.mu.Lock()
	store := c.keys.store
	_, refused := c.keys.refused[rot.KeyID]
	stale := c.apiKey != usedKey || c.keys.adopting != nil // pragma: allowlist secret
	c.keys.mu.Unlock()
	switch {
	case store == nil:
		slog.Warn("branchdam: the server has a rotated API key for this device but no key store is installed; staying on the current key", logAttrs...)
		return nil
	case refused:
		slog.Warn("branchdam: the server offered a rotated API key it already refused once; staying on the current key", logAttrs...)
		return nil
	case stale:
		return nil
	}

	newKey, err := OpenRotatedKey(usedKey, rot.SealedAPIKey)
	if err != nil {
		slog.Warn("branchdam: ignoring a rotated API key that does not open with the current key", append(logAttrs, "err", err)...)
		return nil
	}
	if newKey == usedKey {
		return nil
	}
	if err := store(ctx, newKey); err != nil {
		slog.Warn("branchdam: could not store the rotated API key; staying on the current key", append(logAttrs, "err", err)...)
		return nil
	}

	c.keys.mu.Lock()
	if c.apiKey != usedKey { // pragma: allowlist secret
		c.keys.mu.Unlock()
		return nil
	}
	c.apiKey = newKey // pragma: allowlist secret
	c.keys.adopting = &keyAdoption{newKey: newKey, oldKey: usedKey, keyID: rot.KeyID}
	c.keys.mu.Unlock()
	slog.Info("branchdam: stored the rotated API key; switching to it", logAttrs...)

	// Adopt it now: a handshake is replayable (unlike an upload), so a
	// refusal can fall back and retry, and its response confirms
	// currentKeyId.
	var confirm HandshakeResponse
	confirmKey, err := c.postJSONWithKey(ctx, handshakePath, req, &confirm)
	if err != nil {
		slog.Warn("branchdam: could not confirm the rotated API key yet; the next request adopts it", append(logAttrs, "err", err)...)
		return nil
	}
	if confirmKey == newKey && confirm.CurrentKeyID != 0 && confirm.CurrentKeyID != rot.KeyID {
		slog.Warn("branchdam: the server reports a different current key id after adopting the rotated key", "keyId", rot.KeyID, "currentKeyId", confirm.CurrentKeyID)
	}
	return &confirm
}
