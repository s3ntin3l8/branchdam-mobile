//go:build linux || darwin

package engine

import (
	"path/filepath"
	"syscall"
	"testing"
	"time"

	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

// TestEnqueueLocalCapture_RejectsFIFOWithoutBlocking pins that a path that
// is not a regular file is refused BEFORE it is opened.
//
// The path crosses the FFI boundary from the native shell, so it is not
// necessarily a file. Opening a FIFO read-only blocks until some process
// opens the write end -- which would pin one of the shell's two query
// threads for good: no error, no timeout, and every later query on that
// thread queued behind it. The enqueue path runs on the same two-thread
// queryExecutor as ComputeHashes, so the same hazard applies to it.
//
// The test would HANG (rather than fail) without the regular-file check,
// so it runs the call in a goroutine and fails on a timeout.
func TestEnqueueLocalCapture_RejectsFIFOWithoutBlocking(t *testing.T) {
	q, err := queue.Open(filepath.Join(t.TempDir(), "engine.db"))
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()
	e := New(q, nil)

	fifo := filepath.Join(t.TempDir(), "pipe")
	if err := syscall.Mkfifo(fifo, 0o600); err != nil {
		t.Fatalf("Mkfifo: %v", err)
	}

	done := make(chan error, 1)
	go func() {
		_, err := e.EnqueueLocalCapture(fifo, "pipe.mov", 0, "local-1", "cam", "")
		done <- err
	}()

	select {
	case err := <-done:
		if err == nil {
			t.Fatal("EnqueueLocalCapture accepted a FIFO")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("EnqueueLocalCapture did not return: the open blocked on the FIFO")
	}
}
