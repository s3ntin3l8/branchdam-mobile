// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build linux && (amd64 || arm64 || loong64 || ppc64le || s390x || riscv64 || 386 || arm)

package libc // import "modernc.org/libc"

import (
	"os"
	"path/filepath"
	"reflect"
	"sync/atomic"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
)

// https://gitlab.com/cznic/libc/-/issues/55

// issue55SetTimespec stores sec and nsec into the struct timespec at ts,
// whatever the width of its fields on this target.
func issue55SetTimespec(ts uintptr, sec, nsec int64) {
	if nsec >= 1e9 {
		nsec -= 1e9
		sec++
	}
	v := reflect.ValueOf((*Ttimespec)(unsafe.Pointer(ts))).Elem()
	v.FieldByName("Ftv_sec").SetInt(sec)
	v.FieldByName("Ftv_nsec").SetInt(nsec)
}

// issue55Deadline returns a timespec at ts that is d past now on clock clk.
func issue55Deadline(ts uintptr, clk int32, d time.Duration) {
	var now unix.Timespec
	if err := unix.ClockGettime(clk, &now); err != nil {
		panic(err)
	}

	issue55SetTimespec(ts, int64(now.Sec)+int64(d/time.Second), int64(now.Nsec)+int64(d%time.Second))
}

func TestIssue55Condattr(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	p := Xmalloc(tls, 256)
	defer Xfree(tls, p)

	a, c, m, ts, clk, pshared := p, p+16, p+64, p+128, p+160, p+168
	if g := Xpthread_condattr_init(tls, a); g != 0 {
		t.Fatalf("condattr_init: %v", g)
	}
	for _, v := range []Tclockid_t{CLOCK_PROCESS_CPUTIME_ID, CLOCK_THREAD_CPUTIME_ID, -1} {
		if g := Xpthread_condattr_setclock(tls, a, v); g != EINVAL {
			t.Errorf("setclock(%v): got %v, expected EINVAL", v, g)
		}
	}
	if g := Xpthread_condattr_getclock(tls, a, clk); g != 0 || *(*Tclockid_t)(unsafe.Pointer(clk)) != CLOCK_REALTIME {
		t.Fatalf("getclock: %v, %v", g, *(*Tclockid_t)(unsafe.Pointer(clk)))
	}
	if g := Xpthread_condattr_setclock(tls, a, CLOCK_MONOTONIC); g != 0 {
		t.Fatalf("setclock: %v", g)
	}
	if g := Xpthread_condattr_setpshared(tls, a, 2); g != EINVAL {
		t.Errorf("setpshared(2): got %v, expected EINVAL", g)
	}
	if g := Xpthread_condattr_setpshared(tls, a, 1); g != 0 {
		t.Fatalf("setpshared: %v", g)
	}
	if g := Xpthread_condattr_getpshared(tls, a, pshared); g != 0 || *(*int32)(unsafe.Pointer(pshared)) != 1 {
		t.Fatalf("getpshared: %v, %v", g, *(*int32)(unsafe.Pointer(pshared)))
	}
	if g := Xpthread_condattr_getclock(tls, a, clk); g != 0 || *(*Tclockid_t)(unsafe.Pointer(clk)) != CLOCK_MONOTONIC {
		t.Fatalf("getclock: %v, %v", g, *(*Tclockid_t)(unsafe.Pointer(clk)))
	}

	if g := Xpthread_cond_init(tls, c, a); g != 0 {
		t.Fatalf("cond_init: %v", g)
	}
	if g := Xpthread_mutex_init(tls, m, 0); g != 0 {
		t.Fatalf("mutex_init: %v", g)
	}
	Xpthread_mutex_lock(tls, m)

	// A deadline 30ms ahead on the monotonic clock times out, and not early.
	issue55Deadline(ts, unix.CLOCK_MONOTONIC, 30*time.Millisecond)
	start := time.Now()
	if g := Xpthread_cond_timedwait(tls, c, m, ts); g != ETIMEDOUT {
		t.Fatalf("cond_timedwait: got %v, expected ETIMEDOUT", g)
	}
	if el := time.Since(start); el < 25*time.Millisecond {
		t.Fatalf("cond_timedwait returned after %v", el)
	}

	// A malformed deadline is rejected.
	issue55SetTimespec(ts, 0, 0)
	(*Ttimespec)(unsafe.Pointer(ts)).Ftv_nsec = -1
	if g := Xpthread_cond_timedwait(tls, c, m, ts); g != EINVAL {
		t.Fatalf("cond_timedwait: got %v, expected EINVAL", g)
	}

	// A signal before a far deadline ends the wait.
	issue55Deadline(ts, unix.CLOCK_MONOTONIC, 10*time.Second)
	go func() {
		time.Sleep(20 * time.Millisecond)
		tls2 := NewTLS()
		Xpthread_mutex_lock(tls2, m)
		Xpthread_cond_signal(tls2, c)
		Xpthread_mutex_unlock(tls2, m)
		tls2.Close()
	}()
	if g := Xpthread_cond_timedwait(tls, c, m, ts); g != 0 {
		t.Fatalf("cond_timedwait: got %v, expected 0", g)
	}
	Xpthread_mutex_unlock(tls, m)
	Xpthread_cond_destroy(tls, c)
	Xpthread_mutex_destroy(tls, m)
	if g := Xpthread_condattr_destroy(tls, a); g != 0 {
		t.Fatalf("condattr_destroy: %v", g)
	}
}

func TestIssue55Timedwait(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	p := Xmalloc(tls, 64)
	defer Xfree(tls, p)

	w, ts := p, p+16
	*(*int32)(unsafe.Pointer(w)) = 1

	// The word already differs: no wait at all.
	if g := ___timedwait_cp(tls, w, 0, CLOCK_REALTIME, 0, 1); g != 0 {
		t.Fatalf("got %v, expected 0", g)
	}

	// A deadline in the past times out at once.
	issue55SetTimespec(ts, 1, 0)
	if g := ___timedwait_cp(tls, w, 1, CLOCK_REALTIME, ts, 1); g != ETIMEDOUT {
		t.Fatalf("got %v, expected ETIMEDOUT", g)
	}

	// 30ms ahead on the realtime clock.
	issue55Deadline(ts, unix.CLOCK_REALTIME, 30*time.Millisecond)
	start := time.Now()
	if g := ___timedwait_cp(tls, w, 1, CLOCK_REALTIME, ts, 1); g != ETIMEDOUT {
		t.Fatalf("got %v, expected ETIMEDOUT", g)
	}
	if el := time.Since(start); el < 25*time.Millisecond {
		t.Fatalf("returned after %v", el)
	}
	if n, ok := parkedOn(w); ok || n != 0 {
		t.Fatalf("park entry left behind after a timeout: %v %v", n, ok)
	}

	// Woken before a far deadline.
	issue55Deadline(ts, unix.CLOCK_MONOTONIC, 10*time.Second)
	go func() {
		time.Sleep(20 * time.Millisecond)
		tls2 := NewTLS()
		atomic.StoreInt32((*int32)(unsafe.Pointer(w)), 0)
		___wake(tls2, w, 1, 1)
		tls2.Close()
	}()
	if g := ___timedwait_cp(tls, w, 1, CLOCK_MONOTONIC, ts, 1); g != 0 {
		t.Fatalf("got %v, expected 0", g)
	}
	if n, ok := parkedOn(w); ok || n != 0 {
		t.Fatalf("park entry left behind after a wake: %v %v", n, ok)
	}
}

var issue55Got int32

func issue55Handler(tls *TLS, sig int32) {
	issue55Got = sig
}

func TestIssue55Tkill(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	// A signal aimed at another emulated thread reaches that thread's handler
	// at its next check point.
	tls2 := NewTLS()
	handler := __ccgo_fp(issue55Handler)
	ready := make(chan struct{})
	done := make(chan int32)
	go func() {
		issue55Got = 0
		Xsignal(tls2, int32(unix.SIGUSR1), handler)
		close(ready)
		for deadline := time.Now().Add(10 * time.Second); issue55Got == 0 && time.Now().Before(deadline); {
			tls2.Alloc(8)
			tls2.Free(8)
			time.Sleep(time.Millisecond)
		}
		v := issue55Got
		Xsignal(tls2, int32(unix.SIGUSR1), SIG_DFL)
		tls2.Close()
		done <- v
	}()
	<-ready
	if g := X__syscall2(tls, SYS_tkill, long(tls2.ID), long(unix.SIGUSR1)); g != 0 {
		t.Fatalf("tkill: %v", g)
	}
	if g := <-done; g != int32(unix.SIGUSR1) {
		t.Fatalf("handler got %v", g)
	}

	// A tid that is no emulated thread goes to the kernel and fails there.
	if g := X__syscall2(tls, SYS_tkill, 1<<30, long(unix.SIGUSR1)); g >= 0 {
		t.Fatalf("tkill of an unknown tid: %v", g)
	}
}

func TestIssue55ThreadNames(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	buf := Xmalloc(tls, 64)
	defer Xfree(tls, buf)

	if g := Xpthread_getname_np(tls, tls.pthread, buf, 8); g != ERANGE {
		t.Fatalf("getname_np with a short buffer: got %v, expected ERANGE", g)
	}
	if g := Xpthread_getname_np(tls, tls.pthread, buf, 16); g != 0 {
		t.Fatalf("getname_np: %v", g)
	}
	// Unnamed: the process name, as the kernel reports it.
	want := filepath.Base(os.Args[0])
	if len(want) > 15 {
		want = want[:15]
	}
	if g := GoString(buf); g != want {
		t.Fatalf("unnamed thread reports %q, expected %q", g, want)
	}

	name := issue56CString(tls, "worker")
	defer Xfree(tls, name)

	if g := Xpthread_setname_np(tls, tls.pthread, name); g != 0 {
		t.Fatalf("setname_np: %v", g)
	}
	if g := Xpthread_getname_np(tls, tls.pthread, buf, 64); g != 0 || GoString(buf) != "worker" {
		t.Fatalf("getname_np: %v %q", g, GoString(buf))
	}

	long := issue56CString(tls, "0123456789abcdef")
	defer Xfree(tls, long)

	if g := Xpthread_setname_np(tls, tls.pthread, long); g != ERANGE {
		t.Fatalf("setname_np with 16 bytes: got %v, expected ERANGE", g)
	}

	// The calling thread has a CPU clock, other emulated threads do not.
	clk, ts := buf, buf+16
	if g := Xpthread_getcpuclockid(tls, tls.pthread, clk); g != 0 || *(*Tclockid_t)(unsafe.Pointer(clk)) != CLOCK_THREAD_CPUTIME_ID {
		t.Fatalf("getcpuclockid: %v %v", g, *(*Tclockid_t)(unsafe.Pointer(clk)))
	}
	if g := Xclock_gettime(tls, *(*Tclockid_t)(unsafe.Pointer(clk)), ts); g != 0 {
		t.Fatalf("clock_gettime: %v", g)
	}

	tls2 := NewTLS()
	defer tls2.Close()

	if g := Xpthread_getcpuclockid(tls, tls2.pthread, clk); g != ESRCH {
		t.Fatalf("getcpuclockid of another thread: got %v, expected ESRCH", g)
	}
}
