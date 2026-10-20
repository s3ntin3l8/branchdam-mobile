package engine

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

// TestEnqueueLocalCapture_RejectsOversizedFile pins that the enqueue path
// refuses a file that is too large to hash, so the hash stays bounded work.
//
// The path crosses the FFI boundary from the native shell, and the hash
// streams the whole file: without a cap a huge (or sparse) file turns the
// enqueue into an unbounded read on a phone's query thread. The limit is
// maxEnqueueFileBytes, the same 64 GiB bound the FFI surface's
// ComputeHashes uses.
//
// The limit is lowered for the test (the real one cannot be reached
// without writing 64 GiB), so this checks the wiring of the bound, not the
// value of the constant.
func TestEnqueueLocalCapture_RejectsOversizedFile(t *testing.T) {
	q, err := queue.Open(filepath.Join(t.TempDir(), "engine.db"))
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()
	e := New(q, nil)
	e.hashSizeLimit = 4

	big := filepath.Join(t.TempDir(), "big.dng")
	if err := os.WriteFile(big, []byte("more than four bytes"), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	_, err = e.EnqueueLocalCapture(big, "big.dng", 0, "local-big", "cam", "")
	if err == nil {
		t.Fatal("EnqueueLocalCapture accepted a file over the size limit")
	}
	ce, ok := err.(*client.ClientError)
	if !ok {
		t.Fatalf("error type = %T, want *client.ClientError", err)
	}
	if ce.Code != client.CodeInvalidInput {
		t.Fatalf("Code = %q, want %q", ce.Code, client.CodeInvalidInput)
	}
	if !strings.Contains(ce.Message, "too large to hash") {
		t.Fatalf("Message = %q, want the size-limit refusal", ce.Message)
	}

	// A file within the limit is still enqueued, so the bound rejects
	// only what it should.
	small := filepath.Join(t.TempDir(), "small.dng")
	if err := os.WriteFile(small, []byte("four"), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	if _, err := e.EnqueueLocalCapture(small, "small.dng", 0, "local-small", "cam", ""); err != nil {
		t.Fatalf("EnqueueLocalCapture: %v", err)
	}
}
