// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

// The functions tested here come from the transpiled musl and exist only in
// generated files produced since the generator keeps src/thread/sem_*.c,
// pthread_kill.c and the src/sched sources. Widen the constraint to all musl
// targets once every ccgo_linux_*.go has been regenerated.

//go:build linux && amd64

package libc // import "modernc.org/libc"

import (
	"fmt"
	"os"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
)

// https://gitlab.com/cznic/libc/-/issues/55

func issue55Errno(tls *TLS) int32 {
	return *(*int32)(unsafe.Pointer(X__errno_location(tls)))
}

func TestIssue55Semaphores(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	p := Xmalloc(tls, 128)
	defer Xfree(tls, p)

	s, ts, val := p, p+64, p+96
	if g := Xsem_init(tls, s, 0, 1); g != 0 {
		t.Fatalf("sem_init: %v", g)
	}
	if g := Xsem_wait(tls, s); g != 0 {
		t.Fatalf("sem_wait: %v", g)
	}
	if g := Xsem_getvalue(tls, s, val); g != 0 || *(*int32)(unsafe.Pointer(val)) != 0 {
		t.Fatalf("sem_getvalue: %v %v", g, *(*int32)(unsafe.Pointer(val)))
	}
	if g := Xsem_trywait(tls, s); g != -1 || issue55Errno(tls) != EAGAIN {
		t.Fatalf("sem_trywait: %v, errno %v", g, issue55Errno(tls))
	}
	issue55Deadline(ts, unix.CLOCK_REALTIME, 30*time.Millisecond)
	start := time.Now()
	if g := Xsem_timedwait(tls, s, ts); g != -1 || issue55Errno(tls) != ETIMEDOUT {
		t.Fatalf("sem_timedwait: %v, errno %v", g, issue55Errno(tls))
	}
	if el := time.Since(start); el < 25*time.Millisecond {
		t.Fatalf("sem_timedwait returned after %v", el)
	}
	if g := Xsem_post(tls, s); g != 0 {
		t.Fatalf("sem_post: %v", g)
	}
	if g := Xsem_getvalue(tls, s, val); g != 0 || *(*int32)(unsafe.Pointer(val)) != 1 {
		t.Fatalf("sem_getvalue: %v %v", g, *(*int32)(unsafe.Pointer(val)))
	}
	if g := Xsem_trywait(tls, s); g != 0 {
		t.Fatalf("sem_trywait: %v", g)
	}

	// Another thread blocks in sem_wait until this one posts.
	done := make(chan int32)
	go func() {
		tls2 := NewTLS()
		r := Xsem_wait(tls2, s)
		tls2.Close()
		done <- r
	}()
	select {
	case <-done:
		t.Fatal("sem_wait did not block")
	case <-time.After(50 * time.Millisecond):
	}
	if g := Xsem_post(tls, s); g != 0 {
		t.Fatalf("sem_post: %v", g)
	}
	select {
	case g := <-done:
		if g != 0 {
			t.Fatalf("sem_wait: %v", g)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("sem_wait was not woken by sem_post")
	}

	// A post before a far deadline ends a timed wait.
	issue55Deadline(ts, unix.CLOCK_REALTIME, 10*time.Second)
	go func() {
		time.Sleep(20 * time.Millisecond)
		tls2 := NewTLS()
		Xsem_post(tls2, s)
		tls2.Close()
	}()
	if g := Xsem_timedwait(tls, s, ts); g != 0 {
		t.Fatalf("sem_timedwait: %v, errno %v", g, issue55Errno(tls))
	}
	if g := Xsem_destroy(tls, s); g != 0 {
		t.Fatalf("sem_destroy: %v", g)
	}

	// Named semaphores live in /dev/shm.
	name := issue56CString(tls, fmt.Sprintf("/libc-issue55-%d-%d", os.Getpid(), time.Now().UnixNano()))
	defer Xfree(tls, name)

	va := Xmalloc(tls, 64)
	defer Xfree(tls, va)

	sem := Xsem_open(tls, name, O_CREAT|O_EXCL, VaList(va, int32(0600), int32(1)))
	if sem == 0 {
		t.Fatalf("sem_open: errno %v", issue55Errno(tls))
	}
	if g := Xsem_open(tls, name, O_CREAT|O_EXCL, VaList(va, int32(0600), int32(1))); g != 0 || issue55Errno(tls) != EEXIST {
		t.Fatalf("sem_open O_EXCL on an existing semaphore: %#x, errno %v", g, issue55Errno(tls))
	}
	if g := Xsem_open(tls, name, 0, 0); g != sem {
		t.Fatalf("reopening returned %#x, expected %#x", g, sem)
	}
	if g := Xsem_getvalue(tls, sem, val); g != 0 || *(*int32)(unsafe.Pointer(val)) != 1 {
		t.Fatalf("sem_getvalue: %v %v", g, *(*int32)(unsafe.Pointer(val)))
	}
	if g := Xsem_wait(tls, sem); g != 0 {
		t.Fatalf("sem_wait: %v", g)
	}
	if g := Xsem_trywait(tls, sem); g != -1 || issue55Errno(tls) != EAGAIN {
		t.Fatalf("sem_trywait: %v, errno %v", g, issue55Errno(tls))
	}
	if g := Xsem_post(tls, sem); g != 0 {
		t.Fatalf("sem_post: %v", g)
	}
	if g := Xsem_close(tls, sem); g != 0 {
		t.Fatalf("sem_close: %v", g)
	}
	if g := Xsem_close(tls, sem); g != 0 {
		t.Fatalf("sem_close: %v", g)
	}
	if g := Xsem_unlink(tls, name); g != 0 {
		t.Fatalf("sem_unlink: %v, errno %v", g, issue55Errno(tls))
	}
}

func TestIssue55Sched(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	p := Xmalloc(tls, 256)
	defer Xfree(tls, p)

	if g := Xsched_get_priority_max(tls, SCHED_OTHER); g != 0 {
		t.Errorf("sched_get_priority_max(SCHED_OTHER): %v", g)
	}
	if g := Xsched_get_priority_min(tls, SCHED_OTHER); g != 0 {
		t.Errorf("sched_get_priority_min(SCHED_OTHER): %v", g)
	}
	// musl does not support these.
	if g := Xsched_getparam(tls, 0, p); g != -1 || issue55Errno(tls) != ENOSYS {
		t.Errorf("sched_getparam: %v, errno %v", g, issue55Errno(tls))
	}
	if g := Xsched_setscheduler(tls, 0, SCHED_OTHER, p); g != -1 || issue55Errno(tls) != ENOSYS {
		t.Errorf("sched_setscheduler: %v, errno %v", g, issue55Errno(tls))
	}
	set := p
	if g := Xsched_getaffinity(tls, 0, 128, set); g != 0 {
		t.Fatalf("sched_getaffinity: %v, errno %v", g, issue55Errno(tls))
	}
	if g := X__sched_cpucount(tls, 128, set); g < 1 {
		t.Errorf("cpu count %v", g)
	}
	if g := Xsched_rr_get_interval(tls, 0, p+128); g != 0 {
		t.Errorf("sched_rr_get_interval: %v, errno %v", g, issue55Errno(tls))
	}
	if g := Xsched_yield(tls); g != 0 {
		t.Errorf("sched_yield: %v", g)
	}
}

func TestIssue55PthreadKill(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	// The calling thread: the handler runs before pthread_kill returns.
	issue55Got = 0
	handler := __ccgo_fp(issue55Handler)
	Xsignal(tls, int32(unix.SIGUSR1), handler)
	if g := Xpthread_kill(tls, tls.pthread, int32(unix.SIGUSR1)); g != 0 {
		t.Fatalf("pthread_kill: %v", g)
	}
	if issue55Got != int32(unix.SIGUSR1) {
		t.Fatalf("handler got %v", issue55Got)
	}
	Xsignal(tls, int32(unix.SIGUSR1), SIG_DFL)
	if g := Xpthread_kill(tls, tls.pthread, 999); g != EINVAL {
		t.Fatalf("pthread_kill with a bad signal: got %v, expected EINVAL", g)
	}

	// Another thread: delivered at its next check point.
	tls2 := NewTLS()
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
	if g := Xpthread_kill(tls, tls2.pthread, int32(unix.SIGUSR1)); g != 0 {
		t.Fatalf("pthread_kill: %v", g)
	}
	if g := <-done; g != int32(unix.SIGUSR1) {
		t.Fatalf("handler got %v", g)
	}

	Xpthread_testcancel(tls)
}
