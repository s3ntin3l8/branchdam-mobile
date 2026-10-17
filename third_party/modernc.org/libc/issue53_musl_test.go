// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build linux && (amd64 || arm64 || loong64 || ppc64le || s390x || riscv64 || 386 || arm)

package libc // import "modernc.org/libc"

import (
	"os"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
)

// https://gitlab.com/cznic/libc/-/issues/53

func issue53FileLock(f uintptr) int32 {
	return AtomicLoadPInt32(f + unsafe.Offsetof(TFILE{}.Flock))
}

func TestIssue53Flockfile(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	f := Xtmpfile(tls)
	if f == 0 {
		t.Fatal("tmpfile failed")
	}

	// Not deferred: a failed assertion would leave f locked and fclose would
	// then block on it.

	// Files start out unlocked and without locking.
	if g := issue53FileLock(f); g >= 0 {
		t.Fatalf("lock word %v, expected < 0", g)
	}

	// The owner can lock recursively.
	Xflockfile(tls, f)
	if g, e := issue53FileLock(f), tls.ID; g != e {
		t.Fatalf("lock word %v, expected owner %v", g, e)
	}
	if g := Xftrylockfile(tls, f); g != 0 {
		t.Fatalf("recursive ftrylockfile: got %v, expected 0", g)
	}
	Xfunlockfile(tls, f)
	if g, e := issue53FileLock(f), tls.ID; g != e {
		t.Fatalf("lock word %v after one of two unlocks, expected owner %v", g, e)
	}

	// Another thread cannot take it and blocks in flockfile until the owner
	// releases it.
	tls2 := NewTLS()
	defer tls2.Close()

	if g := Xftrylockfile(tls2, f); g != -1 {
		t.Fatalf("ftrylockfile on a locked file: got %v, expected -1", g)
	}

	acquired := make(chan struct{})
	go func() {
		Xflockfile(tls2, f)
		close(acquired)
	}()
	select {
	case <-acquired:
		t.Fatal("flockfile did not block on a locked file")
	case <-time.After(100 * time.Millisecond):
	}

	Xfunlockfile(tls, f)
	select {
	case <-acquired:
	case <-time.After(10 * time.Second):
		t.Fatal("flockfile was not woken by funlockfile")
	}

	// The contended acquire path records the owner with MAYBE_WAITERS set.
	if g, e := issue53FileLock(f)&^int32(MAYBE_WAITERS), tls2.ID; g != e {
		t.Fatalf("lock word owner %v, expected %v", g, e)
	}
	Xfunlockfile(tls2, f)
	if g := issue53FileLock(f); g != 0 {
		t.Fatalf("lock word %v after unlock, expected 0", g)
	}

	// From now on every stdio call on f takes the internal lock, which is the
	// path through __lockfile and the locking getc/putc helpers.
	s, err := CString("hello\n")
	if err != nil {
		t.Fatal(err)
	}

	defer Xfree(tls, s)

	if g := Xfputs(tls, s, f); g < 0 {
		t.Fatalf("fputs: %v", g)
	}
	if g := Xputc(tls, 'w', f); g != 'w' {
		t.Fatalf("putc: %v", g)
	}
	if g := Xfflush(tls, f); g != 0 {
		t.Fatalf("fflush: %v", g)
	}
	Xrewind(tls, f)
	for i, e := range []int32{'h', 'e', 'l', 'l', 'o', '\n', 'w', -1} {
		if g := Xgetc(tls, f); g != e {
			t.Fatalf("getc #%d: got %v, expected %v", i, g, e)
		}
	}
	if g := issue53FileLock(f); g != 0 {
		t.Fatalf("lock word %v after unlocked stdio calls, expected 0", g)
	}
	if g := Xfclose(tls, f); g != 0 {
		t.Fatalf("fclose: %v", g)
	}
}

// ffsll and the musl fma use a_ctz_64 and a_clz_64, which used to be
// untranslatable inline assembly on the 64 bit ports.
func TestIssue53Bits(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	if g, e := Xffsll(tls, 0x10), int32(5); g != e {
		t.Errorf("ffsll: got %v, expected %v", g, e)
	}
	if g, e := Xffsll(tls, 0), int32(0); g != e {
		t.Errorf("ffsll: got %v, expected %v", g, e)
	}
	if g, e := Xfma(tls, 2, 3, 4), 10.0; g != e {
		t.Errorf("fma: got %v, expected %v", g, e)
	}
	// 1+2^-52 squared needs the fused rounding: the naive product loses 2^-104.
	x := 1 + 0x1p-52
	if g, e := Xfma(tls, x, x, -1), 0x1p-51+0x1p-104; g != e {
		t.Errorf("fma: got %x, expected %x", g, e)
	}

	p := Xmalloc(tls, 8)
	defer Xfree(tls, p)

	*(*int32)(unsafe.Pointer(p)) = 5
	if g := _a_cas(tls, p, 5, 7); g != 5 {
		t.Errorf("a_cas: got %v, expected 5", g)
	}
	if g := _a_cas(tls, p, 5, 9); g != 7 {
		t.Errorf("a_cas: got %v, expected 7", g)
	}
	if g := *(*int32)(unsafe.Pointer(p)); g != 7 {
		t.Errorf("a_cas: value %v, expected 7", g)
	}
}

// A C function pointer must refer to a named function, as in ccgo output: a
// closure that does not escape lives on the goroutine stack and moves.
var issue53Got struct {
	calls   int
	info    uintptr
	sig     int32
	siSigno int32
}

func issue53Handler(tls *TLS, sig int32, info, ctx uintptr) {
	issue53Got.calls++
	issue53Got.sig = sig
	issue53Got.info = info
	issue53Got.siSigno = (*Tsiginfo_t)(unsafe.Pointer(info)).Fsi_signo
}

func TestIssue53Sigaction(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	n := Tsize_t(unsafe.Sizeof(Tsigaction{}))
	sa := Xmalloc(tls, 2*n)
	defer Xfree(tls, sa)

	old := sa + uintptr(n)
	issue53Got.calls = 0
	handler := __ccgo_fp(issue53Handler)
	var resetHand uint32 = SA_RESETHAND
	*(*Tsigaction)(unsafe.Pointer(sa)) = Tsigaction{}
	*(*uintptr)(unsafe.Pointer(sa)) = handler
	(*Tsigaction)(unsafe.Pointer(sa)).Fsa_flags = int32(SA_SIGINFO) | int32(resetHand)
	if g := Xsigaction(tls, int32(unix.SIGUSR1), sa, old); g != 0 {
		t.Fatalf("sigaction: %v", g)
	}
	if g, e := *(*uintptr)(unsafe.Pointer(old)), uintptr(SIG_DFL); g != e {
		t.Fatalf("previous handler %#x, expected %#x", g, e)
	}

	// Query only.
	*(*Tsigaction)(unsafe.Pointer(old)) = Tsigaction{}
	if g := Xsigaction(tls, int32(unix.SIGUSR1), 0, old); g != 0 {
		t.Fatalf("sigaction: %v", g)
	}
	if g, e := *(*uintptr)(unsafe.Pointer(old)), handler; g != e {
		t.Fatalf("handler %#x, expected %#x", g, e)
	}
	if g, e := (*Tsigaction)(unsafe.Pointer(old)).Fsa_flags, int32(SA_SIGINFO)|int32(resetHand); g != e {
		t.Fatalf("flags %#x, expected %#x", g, e)
	}

	if err := unix.Kill(os.Getpid(), unix.SIGUSR1); err != nil {
		t.Fatal(err)
	}

	// Signals are delivered at the libc's check points, TLS.Free is one.
	for deadline := time.Now().Add(10 * time.Second); issue53Got.calls == 0; {
		if time.Now().After(deadline) {
			t.Fatal("handler not called")
		}

		tls.Alloc(8)
		tls.Free(8)
		time.Sleep(time.Millisecond)
	}
	if g := issue53Got; g.calls != 1 || g.sig != int32(unix.SIGUSR1) || g.info == 0 || g.siSigno != int32(unix.SIGUSR1) {
		t.Fatalf("got %+v", g)
	}

	// SA_RESETHAND restored the default disposition.
	if g := Xsigaction(tls, int32(unix.SIGUSR1), 0, old); g != 0 {
		t.Fatalf("sigaction: %v", g)
	}
	if g, e := *(*uintptr)(unsafe.Pointer(old)), uintptr(SIG_DFL); g != e {
		t.Fatalf("handler %#x after SA_RESETHAND, expected %#x", g, e)
	}

	// raise delivers to the calling thread before returning.
	issue53Got.calls = 0
	(*Tsigaction)(unsafe.Pointer(sa)).Fsa_flags = int32(SA_SIGINFO)
	if g := Xsigaction(tls, int32(unix.SIGUSR1), sa, 0); g != 0 {
		t.Fatalf("sigaction: %v", g)
	}
	if g := Xraise(tls, int32(unix.SIGUSR1)); g != 0 {
		t.Fatalf("raise: %v", g)
	}
	if g := issue53Got; g.calls != 1 || g.sig != int32(unix.SIGUSR1) {
		t.Fatalf("after raise: got %+v", g)
	}
	Xsignal(tls, int32(unix.SIGUSR1), SIG_DFL)

	// Invalid signal numbers are rejected as before.
	if g := Xsigaction(tls, 0, sa, 0); g != -1 {
		t.Fatalf("sigaction(0): got %v, expected -1", g)
	}
}
