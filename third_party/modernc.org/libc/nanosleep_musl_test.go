// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build linux && (amd64 || arm64 || loong64 || ppc64le || s390x || riscv64 || 386 || arm)

package libc // import "modernc.org/libc"

import (
	"runtime"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
)

// preemptSignals delivers the signal the Go runtime uses for asynchronous
// preemption to the calling OS thread until the returned function is called.
// The caller must be locked to its OS thread.
func preemptSignals(t *testing.T) func() {
	t.Helper()
	pid, tid := unix.Getpid(), unix.Gettid()
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		tick := time.NewTicker(10 * time.Millisecond)
		defer tick.Stop()
		for {
			select {
			case <-stop:
				return
			case <-tick.C:
				if err := unix.Tgkill(pid, tid, unix.SIGURG); err != nil {
					return
				}
			}
		}
	}()
	return func() {
		close(stop)
		<-done
	}
}

func setTimespec(p uintptr, sec, nsec int64) {
	ts := (*Ttimespec)(unsafe.Pointer(p))
	// tv_nsec is long, which is 32 bits wide on the 32-bit targets.
	ts.Ftv_sec = Ttime_t(sec)
	ts.Ftv_nsec = long(nsec)
}

// TestSleepEINTR verifies that a signal the translated C program never
// installed a handler for does not shorten a sleep. The Go runtime sends SIGURG
// to preempt a thread asynchronously; a C program running on this libc cannot
// be expected to notice, restart the sleep and still get the timing it asked
// for - SQLite's unixSleep(), for one, discards the return value.
func TestSleepEINTR(t *testing.T) {
	const (
		sleep = 300 * time.Millisecond
		slack = 20 * time.Millisecond
	)

	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	tls := NewTLS()
	defer tls.Close()

	sz := int(unsafe.Sizeof(Ttimespec{}))
	req := tls.Alloc(sz)
	defer tls.Free(sz)

	t.Run("nanosleep", func(t *testing.T) {
		setTimespec(req, 0, int64(sleep))
		defer preemptSignals(t)()

		t0 := time.Now()
		rc := Xnanosleep(tls, req, 0)
		d := time.Since(t0)

		if rc != 0 {
			errno := *(*int32)(unsafe.Pointer(X__errno_location(tls)))
			t.Errorf("nanosleep = %v, errno %v (%v), want 0", rc, errno, unix.Errno(errno))
		}
		if d < sleep-slack {
			t.Errorf("nanosleep(%v) returned after %v, want at least %v", sleep, d, sleep-slack)
		}
	})

	t.Run("clock_nanosleep", func(t *testing.T) {
		setTimespec(req, 0, int64(sleep))
		defer preemptSignals(t)()

		t0 := time.Now()
		rc := Xclock_nanosleep(tls, CLOCK_MONOTONIC, 0, req, 0)
		d := time.Since(t0)

		if rc != 0 {
			t.Errorf("clock_nanosleep = %v (%v), want 0", rc, unix.Errno(rc))
		}
		if d < sleep-slack {
			t.Errorf("clock_nanosleep(%v) returned after %v, want at least %v", sleep, d, sleep-slack)
		}
	})

	t.Run("clock_nanosleep TIMER_ABSTIME", func(t *testing.T) {
		if rc := Xclock_gettime(tls, CLOCK_MONOTONIC, req); rc != 0 {
			t.Fatalf("clock_gettime = %v", rc)
		}
		deadline := int64((*Ttimespec)(unsafe.Pointer(req)).Ftv_nsec) + int64(sleep)
		setTimespec(req, int64((*Ttimespec)(unsafe.Pointer(req)).Ftv_sec)+deadline/1e9, deadline%1e9)
		defer preemptSignals(t)()

		t0 := time.Now()
		rc := Xclock_nanosleep(tls, CLOCK_MONOTONIC, TIMER_ABSTIME, req, 0)
		d := time.Since(t0)

		if rc != 0 {
			t.Errorf("clock_nanosleep = %v (%v), want 0", rc, unix.Errno(rc))
		}
		if d < sleep-slack {
			t.Errorf("clock_nanosleep(TIMER_ABSTIME, +%v) returned after %v, want at least %v", sleep, d, sleep-slack)
		}
	})

	// The remaining-time report the caller asked for must still work.
	t.Run("rem reported", func(t *testing.T) {
		rem := tls.Alloc(sz)
		defer tls.Free(sz)
		setTimespec(rem, -1, -1)
		setTimespec(req, 0, int64(10*time.Millisecond))

		if rc := Xnanosleep(tls, req, rem); rc != 0 {
			t.Fatalf("nanosleep = %v", rc)
		}
		// An uninterrupted sleep leaves rem alone, so this only checks that
		// passing a non-nil rem still works and nothing scribbled on it.
		if got := (*Ttimespec)(unsafe.Pointer(rem)).Ftv_sec; got != -1 {
			t.Errorf("rem.tv_sec = %v, want it untouched (-1)", got)
		}
	})
}
