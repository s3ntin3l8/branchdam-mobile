//go:build linux || darwin

package branchdam

import (
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

// TestComputeHashes_RejectsFIFOWithoutBlocking pins that a path that is
// not a regular file is refused BEFORE it is opened.
//
// The path crosses the FFI boundary from the native shell, so it is not
// necessarily a file. Opening a FIFO read-only blocks until some process
// opens the write end -- which would pin one of the shell's two query
// threads for good: no error, no timeout, and every later query on that
// thread queued behind it.
//
// The test would HANG (rather than fail) without the regular-file check,
// so it runs the call in a goroutine and fails on a timeout.
func TestComputeHashes_RejectsFIFOWithoutBlocking(t *testing.T) {
	e, _ := newTestEngineWithServer(t)

	dir := t.TempDir()
	fifo := filepath.Join(dir, "pipe")
	if err := syscall.Mkfifo(fifo, 0o600); err != nil {
		t.Fatalf("Mkfifo: %v", err)
	}

	type result struct {
		hashes Hashes
		err    error
	}
	done := make(chan result, 1)
	go func() {
		hashes, err := e.ComputeHashes(fifo, nil)
		done <- result{hashes, err}
	}()

	select {
	case r := <-done:
		if r.err == nil {
			t.Fatalf("ComputeHashes on a FIFO returned %+v, want an error", r.hashes)
		}
		if r.hashes.Blake3 != "" {
			t.Errorf("ComputeHashes on a FIFO returned a digest %q, want none", r.hashes.Blake3)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("ComputeHashes on a FIFO blocked instead of refusing the path")
	}
}

// TestComputeHashes_RejectsDirectory: a directory is not a regular file
// either, and reading one is an error at best.
func TestComputeHashes_RejectsDirectory(t *testing.T) {
	e, _ := newTestEngineWithServer(t)

	dir := t.TempDir()
	if _, err := e.ComputeHashes(dir, nil); err == nil {
		t.Fatal("ComputeHashes on a directory returned no error")
	}
}
