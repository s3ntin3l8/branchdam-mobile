// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package libc // import "modernc.org/libc"

import (
	"runtime"
	"sync/atomic"
	"testing"
)

// TestIssue52 guards against __atomic_compare_exchange* reporting a bogus old
// value on failure (https://gitlab.com/cznic/libc/-/work_items/52). The previous
// implementation re-read *ptr in a separate step after a failed CAS instead of
// reporting the value observed during the comparison; if *ptr transiently
// reverted to the expected value inside that window, the helper returned failure
// while writing the expected value back into *expected, so the ubiquitous C spin
// idiom
//
//	while (atomic_compare_exchange_strong(&lock, &zero, 1) != 0) ;
//
// left the loop without having acquired anything, wedging whatever waited on the
// lock afterwards.
//
// C11 7.17.7.4 puts the update of *expected inside the atomic read-modify-write
// ("Atomically, compares ... and if false, updates the value in expected with the
// value pointed to by object"), which is what cmpxchg does in one instruction.
// The invariant callers rely on is therefore
//
//	return 0 (failure)  =>  *expected != old
//
// Only the contended case can observe this, which is why it went unnoticed: the
// uncontended subtest below passes even on the broken code.
func TestIssue52(t *testing.T) {
	t.Run("failure_witness", testIssue52FailureWitness)
	t.Run("uncontended_semantics", testIssue52UncontendedSemantics)
}

// testIssue52FailureWitness runs a compare-exchange against a cell that another
// goroutine repeatedly takes and releases, mimicking the lock hand-off that makes
// the cell revert to the expected value inside the failure window. Every failed
// exchange must report a witness different from the expected value; reporting the
// expected value tells the caller it won an exchange that never happened.
func testIssue52FailureWitness(t *testing.T) {
	tls := NewTLS()

	defer tls.Close()

	raw, cp, ep, dp := alignedCells(t)
	defer runtime.KeepAlive(raw)

	cell, exp, des := (*uint64)(cp), (*uint64)(ep), (*uint64)(dp)
	*cell = 0
	*des = 1

	stop := make(chan struct{})
	done := make(chan struct{})

	go func() {
		defer close(done)
		for {
			select {
			case <-stop:
				return
			default:
			}
			atomic.StoreUint64(cell, 1)
			atomic.StoreUint64(cell, 0)
		}
	}()

	const n = 2_000_000
	violations := 0
	for i := 0; i < n; i++ {
		*exp = 0
		r := X__atomic_compare_exchangeUint64(tls, uintptr(cp), uintptr(ep), uintptr(dp), 0, atomicSeqCst, atomicSeqCst)
		if r == 0 && *exp == 0 {
			violations++
		}
	}

	close(stop)
	<-done

	if violations != 0 {
		t.Fatalf("%d of %d failed compare-exchanges reported the expected value as the witness; "+
			"a caller spinning on `while (cas(p, 0, 1) != 0);` exits without having acquired (issue #52)",
			violations, n)
	}
}

// testIssue52UncontendedSemantics pins the plain, single-threaded contract of the
// lock-free compare-exchange helpers: a success swaps and leaves *expected alone,
// a failure leaves the cell alone and reports the actual value. This holds on the
// broken code too and only guards against regressing the easy half.
func testIssue52UncontendedSemantics(t *testing.T) {
	tls := NewTLS()

	defer tls.Close()

	t.Run("Uint32", func(t *testing.T) {
		raw, cp, ep, dp := alignedCells(t)
		defer runtime.KeepAlive(raw)
		cell, exp, des := (*uint32)(cp), (*uint32)(ep), (*uint32)(dp)

		*cell, *exp, *des = 7, 7, 9
		if r := X__atomic_compare_exchangeUint32(tls, uintptr(cp), uintptr(ep), uintptr(dp), 0, atomicSeqCst, atomicSeqCst); r != 1 {
			t.Fatalf("matching compare-exchange returned %v, want 1", r)
		}
		if *cell != 9 {
			t.Fatalf("after success cell = %v, want 9", *cell)
		}
		if *exp != 7 {
			t.Fatalf("after success *expected = %v, want it left at 7", *exp)
		}

		*cell, *exp, *des = 7, 5, 9
		if r := X__atomic_compare_exchangeUint32(tls, uintptr(cp), uintptr(ep), uintptr(dp), 0, atomicSeqCst, atomicSeqCst); r != 0 {
			t.Fatalf("mismatching compare-exchange returned %v, want 0", r)
		}
		if *cell != 7 {
			t.Fatalf("after failure cell = %v, want it left at 7", *cell)
		}
		if *exp != 7 {
			t.Fatalf("after failure *expected = %v, want the actual value 7", *exp)
		}
	})

	t.Run("Uint64", func(t *testing.T) {
		raw, cp, ep, dp := alignedCells(t)
		defer runtime.KeepAlive(raw)
		cell, exp, des := (*uint64)(cp), (*uint64)(ep), (*uint64)(dp)

		*cell, *exp, *des = 7, 7, 9
		if r := X__atomic_compare_exchangeUint64(tls, uintptr(cp), uintptr(ep), uintptr(dp), 0, atomicSeqCst, atomicSeqCst); r != 1 {
			t.Fatalf("matching compare-exchange returned %v, want 1", r)
		}
		if *cell != 9 {
			t.Fatalf("after success cell = %v, want 9", *cell)
		}
		if *exp != 7 {
			t.Fatalf("after success *expected = %v, want it left at 7", *exp)
		}

		*cell, *exp, *des = 7, 5, 9
		if r := X__atomic_compare_exchangeUint64(tls, uintptr(cp), uintptr(ep), uintptr(dp), 0, atomicSeqCst, atomicSeqCst); r != 0 {
			t.Fatalf("mismatching compare-exchange returned %v, want 0", r)
		}
		if *cell != 7 {
			t.Fatalf("after failure cell = %v, want it left at 7", *cell)
		}
		if *exp != 7 {
			t.Fatalf("after failure *expected = %v, want the actual value 7", *exp)
		}
	})
}
