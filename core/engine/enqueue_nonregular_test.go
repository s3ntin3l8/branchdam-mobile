package engine

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

// TestEnqueueLocalCapture_RejectsNonRegularPath pins that a path that is
// not a regular file is refused BEFORE it is opened, on every platform.
//
// The path crosses the FFI boundary from the native shell, so it is not
// necessarily a file: a directory is the case that exists everywhere, and
// a FIFO is the dangerous one on POSIX -- opening a FIFO read-only blocks
// until some process opens the write end, which would pin one of the
// shell's two query threads for good. The check therefore runs before the
// open, and the open itself is non-blocking where the platform supports it.
//
// The FIFO variant is pinned by the POSIX-only test next to this one; a
// directory is used here so the guard is covered on every platform.
func TestEnqueueLocalCapture_RejectsNonRegularPath(t *testing.T) {
	q, err := queue.Open(filepath.Join(t.TempDir(), "engine.db"))
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()
	e := New(q, nil)

	dir := t.TempDir()
	done := make(chan error, 1)
	go func() {
		_, err := e.EnqueueLocalCapture(dir, "dir.mov", 0, "local-1", "cam", "")
		done <- err
	}()

	select {
	case err := <-done:
		if err == nil {
			t.Fatal("EnqueueLocalCapture accepted a directory")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("EnqueueLocalCapture did not return")
	}
}
