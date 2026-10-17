package branchdam

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"
)

// KeyStore is implemented by the platform shell to store the device's API
// key durably in its secure storage: EncryptedSharedPreferences backed by
// the Android Keystore, or the iOS Keychain through PairingCredentialStore.
// The engine calls StoreAPIKey when the server delivers a rotated key on a
// handshake (branchDAM S13), before it starts using that key, and again
// with the previous key if the server refuses the new one on first use.
// StoreAPIKey must return only once the key is written (Android: commit(),
// not apply()), and return an error when it is not: the engine then stays
// on the key it has. It is called on a Go thread and must not wait on the
// shell's own engine queue.
type KeyStore interface {
	StoreAPIKey(apiKey string) error
}

var (
	keyStoreMu sync.Mutex
	keyStore   KeyStore
)

// errNoKeyStore is returned to the core client when no shell key store is
// installed, so a rotated key is never used without being stored.
var errNoKeyStore = errors.New("no platform key store installed (BindingSetKeyStore); the rotated API key was not stored")

// BindingSetKeyStore installs the shell's key store for every engine,
// including ones opened before or after this call. Pass nil to remove it;
// rotated keys are then only logged.
func BindingSetKeyStore(store KeyStore) {
	keyStoreMu.Lock()
	defer keyStoreMu.Unlock()
	keyStore = store
}

// storeRotatedKey is the core client's KeyStore for every Engine. It reads
// the installed shell store at call time.
func storeRotatedKey(_ context.Context, apiKey string) error {
	keyStoreMu.Lock()
	store := keyStore
	keyStoreMu.Unlock()
	if store == nil {
		return errNoKeyStore
	}
	return store.StoreAPIKey(apiKey)
}

// rotationCheckInterval is how long SyncBatch lets pass without a
// handshake before it makes one just to pick up a rotated key. The
// server's grace window defaults to 24 h, so a device that only syncs in
// the background still sees a rotation in time.
const rotationCheckInterval = 6 * time.Hour

// rotationCheckTimeout bounds that handshake, within the batch's own
// deadline.
const rotationCheckTimeout = 10 * time.Second

// checkKeyRotation handshakes when the last successful handshake is older
// than rotationCheckInterval, which adopts a pending rotated key (see
// core/client/keyrotation.go). It runs on the sync batch's context, so it
// spends the batch's TimeoutSecs, never more. Best effort: a failure is
// logged and the sync batch runs anyway, where an auth failure surfaces
// as usual.
func (e *Engine) checkKeyRotation(batchCtx context.Context) {
	if !e.client.HandshakeDue(rotationCheckInterval) {
		return
	}
	ctx, cancel := context.WithTimeout(batchCtx, rotationCheckTimeout)
	defer cancel()
	if _, err := e.client.Handshake(ctx, ""); err != nil {
		slog.Debug("branchdam: pre-sync handshake failed", "err", err)
	}
}

// RepairDeadlineUnix returns when this device's current API key stops
// working because the server rotated it but could not deliver the new key
// (pendingRotation with an empty sealedApiKey: no BRANCHDAM_SECRET_KEY on
// the server), as Unix seconds, or 0 when no such rotation is pending.
// The shells' Settings screens ask the user to pair the device again
// before that time. See client.Client.RepairDeadlineUnix.
func (e *Engine) RepairDeadlineUnix() (int64, error) {
	if err := e.requireOpen(); err != nil {
		return 0, err
	}
	return e.client.RepairDeadlineUnix(), nil
}
