// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build linux && (amd64 || arm64 || loong64 || ppc64le || s390x || riscv64 || 386 || arm)

package libc

import (
	"fmt"
	"testing"
	"time"
	"unsafe"
)

// TestMuslLockLifetime guards the lifetime invariant of ___lock/___unlock:
//
//	lock state keyed on a C lock word must not outlive the memory that word
//	lives in.
//
// musl deliberately abandons a held lock when the memory containing its lock
// word is about to be freed. freeaddrinfo drops the last aibuf reference with
// LOCK(b->lock) held and calls free(b) instead of UNLOCK(b->lock):
//
//	LOCK(b->lock);
//	if (!(b->ref -= cnt)) free(b);
//	else UNLOCK(b->lock);
//
// That is correct in C, because the lock word is inside the block and dies with
// it; a recycled, calloc'd block starts out unlocked. An implementation holding
// the lock state anywhere but the word itself makes the abandoned lock
// permanent, and the next caller handed that address deadlocks at zero CPU.
// Exactly that regression shipped in v1.74.2 and v1.74.3 and wedged name
// resolution on linux/386 and linux/arm.
func TestMuslLockLifetime(t *testing.T) {
	t.Run("recycled_lock_word", testLockRecycledWord)
	t.Run("no_state_outlives_the_word", testLockNoResidualState)
	t.Run("unbalanced_unlock", testLockUnbalancedUnlock)
	t.Run("freeaddrinfo_repeated", testLockFreeaddrinfoRepeated)
}

// testLockRecycledWord acquires a lock, abandons it the way freeaddrinfo does,
// then models the allocator handing the same block back to a later calloc and
// requires the recycled lock word to be acquirable. It runs the second
// acquisition on another goroutine so a regression fails on the deadline
// instead of hanging the test binary.
func testLockRecycledWord(t *testing.T) {
	tls := NewTLS()

	defer tls.Close()

	// Stand in for an aibuf: C memory with a lock word inside it.
	const size = 64
	block := Xcalloc(tls, 1, size)
	if block == 0 {
		t.Fatal("calloc failed")
	}

	defer Xfree(tls, block)

	p := block + 16 // The lock word, zero-initialized as every musl lock word is.

	___lock(tls, p)

	// Abandon it: freeaddrinfo frees the block while still holding the lock and
	// never unlocks. Zeroing models the free plus a later calloc returning the
	// same address; in C the lock state died with the block.
	Xmemset(tls, block, 0, size)

	done := make(chan struct{})
	go func() {
		defer close(done)

		tls2 := NewTLS()

		defer tls2.Close()

		___lock(tls2, p)
		___unlock(tls2, p)
	}()

	select {
	case <-done:
	case <-time.After(30 * time.Second):
		t.Fatal("deadlock: ___lock blocked on a recycled lock word; lock state outlived the memory it was keyed on")
	}
}

// testLockNoResidualState asserts the structural half of the invariant: the
// parking lot, the only process-global state ___lock keeps, is keyed on lock
// word addresses and so must never retain an entry for an address no goroutine
// is parked on. It checks the uncontended path, the contended path where a
// waiter really does park, and - the one that matters - a lock abandoned while
// held, which is what freeaddrinfo produces on every call that frees the last
// aibuf reference. Retaining state past that point is what made the abandoned
// lock permanent.
func testLockNoResidualState(t *testing.T) {
	tls := NewTLS()

	defer tls.Close()

	p := Xcalloc(tls, 1, Tsize_t(unsafe.Sizeof(int32(0))))
	if p == 0 {
		t.Fatal("calloc failed")
	}

	defer Xfree(tls, p)

	if _, entry := parkedOn(p); entry {
		t.Fatalf("the parking lot already has an entry for %#x before the test", p)
	}

	___lock(tls, p)
	___unlock(tls, p)
	if _, entry := parkedOn(p); entry {
		t.Error("after a balanced lock/unlock: the parking lot still has an entry")
	}

	// Contended: hold p, let another goroutine park on it, then hand it over. The
	// entry must be reclaimed once the waiter is through.
	___lock(tls, p)
	done := make(chan struct{})
	go func() {
		defer close(done)

		tls2 := NewTLS()

		defer tls2.Close()

		___lock(tls2, p)
		___unlock(tls2, p)
	}()

	parked := waitParked(p, 30*time.Second)
	___unlock(tls, p)
	<-done
	if !parked {
		t.Fatal("the contending goroutine never parked on p")
	}

	if _, entry := parkedOn(p); entry {
		t.Error("after a contended lock/unlock: the parking lot still has an entry")
	}

	___lock(tls, p) // Abandoned, as freeaddrinfo abandons b->lock before free(b).
	if _, entry := parkedOn(p); entry {
		t.Error("after an abandoned lock: the parking lot still has an entry; state keyed on an address that is about to be freed cannot be reclaimed")
	}
}

// parkedOn reports how many goroutines the parking lot has registered on p and
// whether it holds an entry for p at all. An entry that outlives its waiters is
// exactly the state the lifetime invariant forbids.
func parkedOn(p uintptr) (waiters int, entry bool) {
	lockParkMu.Lock()

	defer lockParkMu.Unlock()

	q, ok := lockParked[p]
	if !ok {
		return 0, false
	}

	return len(q.waiters), true
}

// waitParked reports whether a goroutine shows up parked on p within d.
func waitParked(p uintptr, d time.Duration) bool {
	for deadline := time.Now().Add(d); time.Now().Before(deadline); time.Sleep(time.Millisecond) {
		if n, _ := parkedOn(p); n != 0 {
			return true
		}
	}
	return false
}

// testLockUnbalancedUnlock requires an unlock of a lock nobody holds to be inert
// rather than a panic. The previous implementation dereferenced a missing map
// entry, turning a caller bug into a nil-pointer crash.
func testLockUnbalancedUnlock(t *testing.T) {
	tls := NewTLS()

	defer tls.Close()

	p := Xcalloc(tls, 1, Tsize_t(unsafe.Sizeof(int32(0))))
	if p == 0 {
		t.Fatal("calloc failed")
	}

	defer Xfree(tls, p)

	___unlock(tls, p)

	// Still usable afterwards.
	___lock(tls, p)
	___unlock(tls, p)
}

// testLockFreeaddrinfoRepeated is the end-to-end path the regression was found
// on: repeated getaddrinfo/freeaddrinfo. It deadlocks deterministically on the
// targets whose allocator hands the same aibuf address back (linux/386,
// linux/arm) and is a smoke test elsewhere.
func testLockFreeaddrinfoRepeated(t *testing.T) {
	const iters = 5

	// Progress is reported through a buffered channel rather than t.Logf: on the
	// deadlock path the worker outlives the test, and logging from a finished test
	// panics.
	trace := make(chan string, iters)
	done := make(chan error, 1)
	go func() {
		done <- freeaddrinfoLoop(iters, trace)
	}()

	var err error
	var timedOut bool
	select {
	case err = <-done:
	case <-time.After(60 * time.Second):
		timedOut = true
	}

	for {
		select {
		case s := <-trace:
			t.Log(s)
			continue
		default:
		}
		break
	}

	switch {
	case timedOut:
		t.Fatal("deadlock: getaddrinfo/freeaddrinfo did not complete; freeaddrinfo abandons a held lock and poisons its address")
	case err != nil:
		t.Skipf("getaddrinfo unavailable: %v", err)
	}
}

func freeaddrinfoLoop(iters int, trace chan<- string) error {
	tls := NewTLS()

	defer tls.Close()

	host, err := CString("localhost")
	if err != nil {
		return err
	}

	defer Xfree(tls, host)

	res := Xmalloc(tls, Tsize_t(unsafe.Sizeof(uintptr(0))))
	if res == 0 {
		return fmt.Errorf("malloc failed")
	}

	defer Xfree(tls, res)

	for i := 0; i < iters; i++ {
		if rc := Xgetaddrinfo(tls, host, 0, 0, res); rc != 0 {
			return fmt.Errorf("getaddrinfo(localhost) = %d", rc)
		}

		ai := *(*uintptr)(unsafe.Pointer(res))
		trace <- fmt.Sprintf("iteration %d: addrinfo=%#x, freeaddrinfo...", i, ai)
		Xfreeaddrinfo(tls, ai)
	}
	return nil
}
