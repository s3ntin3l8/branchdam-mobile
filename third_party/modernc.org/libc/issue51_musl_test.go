// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build linux && (amd64 || arm64 || loong64 || ppc64le || s390x || riscv64 || 386 || arm)

package libc

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"
	"unsafe"
)

// TestIssue51 guards against the lost-wakeup deadlock in ___lock/___unlock
// (https://gitlab.com/cznic/libc/-/work_items/51). The previous implementation
// kept an atomic fast path on the C lock word plus a throwaway hand-off object;
// when an unlocker reached locksMu before a contending locker had registered in
// the locks map, the hand-off was created and immediately discarded, leaving the
// waiter blocked on a fresh, never-unlocked mutex forever (process wedged at zero
// CPU). The trigger is reachable through the exported API alone, e.g. concurrent
// localtime_r contending on the single process-global timezone lock.
//
// These are stress checks: they drive the real ___lock/___unlock and the exported
// localtime_r path under heavy contention, asserting mutual exclusion holds and
// nothing deadlocks. Run under -race to additionally verify the hand-off is a
// properly synchronized, data-race-free critical section.
func TestIssue51(t *testing.T) {
	t.Run("lock_mutual_exclusion", testIssue51LockMutualExclusion)
	t.Run("localtime_r_concurrent", testIssue51LocaltimeConcurrent)
}

// issue51LockWord is the C lock word driven by testIssue51LockMutualExclusion;
// its address is the lock identity. It is a package-level variable on purpose.
// ___lock keeps the lock state in the word itself, so the address must be stable,
// and it must sit in the Go data segment for -race to be meaningful: the race
// detector ignores atomics outside the Go arena and data segments, so a lock word
// in libc-allocated memory would carry no happens-before edge to the Go variable
// the critical section guards. That matches the real static musl lock words
// (_lock1..4, __locale_lock, ...), which are package-level [1]int32 in the
// generated code.
var issue51LockWord int32

// testIssue51LockMutualExclusion hammers a single lock address from many
// goroutines. Each critical section increments a non-atomic counter; if mutual
// exclusion holds the final value is exact, and a lost update (or a -race report)
// signals a broken lock. A deadlock is caught by the timeout.
func testIssue51LockMutualExclusion(t *testing.T) {
	const goroutines = 20
	const iters = 10000

	p := uintptr(unsafe.Pointer(&issue51LockWord))
	var guarded int64 // deliberately non-atomic; protected by ___lock(p)

	var wg sync.WaitGroup
	wg.Add(goroutines)
	for g := 0; g < goroutines; g++ {
		go func() {
			defer wg.Done()
			tls := NewTLS()
			defer tls.Close()
			for i := 0; i < iters; i++ {
				___lock(tls, p)
				guarded++
				___unlock(tls, p)
			}
		}()
	}

	if !issue51WaitTimeout(&wg, time.Minute) {
		t.Fatal("deadlock: ___lock/___unlock contention did not complete (issue #51)")
	}
	if got, want := guarded, int64(goroutines)*iters; got != want {
		t.Fatalf("mutual exclusion violated: guarded=%d want=%d", got, want)
	}
}

// testIssue51LocaltimeConcurrent exercises the exact exported path from the bug
// report: many goroutines, each with its own TLS, contend on the process-global
// timezone lock via localtime_r. It must not deadlock.
func testIssue51LocaltimeConcurrent(t *testing.T) {
	const goroutines = 16
	const iters = 50000

	var wg sync.WaitGroup
	wg.Add(goroutines)
	var calls int64
	for g := 0; g < goroutines; g++ {
		go func() {
			defer wg.Done()
			tls := NewTLS()
			defer tls.Close()
			tp := Xmalloc(tls, 8)   // time_t
			tm := Xmalloc(tls, 128) // struct tm (over-allocated)
			if tp == 0 || tm == 0 {
				return
			}
			defer Xfree(tls, tp)
			defer Xfree(tls, tm)
			*(*int64)(unsafe.Pointer(tp)) = 1600000000
			for i := 0; i < iters; i++ {
				Xlocaltime_r(tls, tp, tm)
				atomic.AddInt64(&calls, 1)
			}
		}()
	}

	if !issue51WaitTimeout(&wg, time.Minute) {
		t.Fatal("deadlock: concurrent Xlocaltime_r did not complete (issue #51)")
	}
	t.Logf("completed %d concurrent Xlocaltime_r calls without deadlock", atomic.LoadInt64(&calls))
}

func issue51WaitTimeout(wg *sync.WaitGroup, d time.Duration) bool {
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
		return true
	case <-time.After(d):
		return false
	}
}
