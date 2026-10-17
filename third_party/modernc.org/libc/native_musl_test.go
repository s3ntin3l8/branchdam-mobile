// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build linux && (amd64 || arm64 || loong64 || ppc64le || s390x || riscv64 || 386 || arm)

package libc // import "modernc.org/libc"

import (
	"bytes"
	"math"
	"math/rand"
	"os"
	"strconv"
	"testing"
	"unsafe"

	"golang.org/x/sys/unix"
)

func TestNativeMuslEquivalence(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	const bufSize = 4096
	p := Xmalloc(tls, bufSize)
	q := Xmalloc(tls, bufSize)
	scratch := Xmalloc(tls, bufSize)
	defer Xfree(tls, p)
	defer Xfree(tls, q)
	defer Xfree(tls, scratch)
	pb := unsafe.Slice((*byte)(unsafe.Pointer(p)), bufSize)
	qb := unsafe.Slice((*byte)(unsafe.Pointer(q)), bufSize)
	sb := unsafe.Slice((*byte)(unsafe.Pointer(scratch)), bufSize)

	sign := func(x int32) int32 {
		switch {
		case x < 0:
			return -1
		case x > 0:
			return 1
		}
		return 0
	}

	rng := rand.New(rand.NewSource(1))
	for i := 0; i < 10000; i++ {
		rng.Read(pb)
		n := Tsize_t(rng.Intn(200))
		off := uintptr(rng.Intn(16))
		off2 := uintptr(rng.Intn(16))

		// memcpy
		copy(qb, pb)
		copy(sb, pb)
		Xmemcpy(tls, q+off, q+256+off2, n)
		___musl_memcpy(tls, scratch+off, scratch+256+off2, n)
		if !bytes.Equal(qb, sb) {
			t.Fatalf("memcpy mismatch: n=%d off=%d off2=%d", n, off, off2)
		}

		// memmove, including overlap
		delta := uintptr(rng.Intn(64))
		copy(qb, pb)
		copy(sb, pb)
		Xmemmove(tls, q+128+off, q+128+off+delta, n)
		___musl_memmove(tls, scratch+128+off, scratch+128+off+delta, n)
		if !bytes.Equal(qb, sb) {
			t.Fatalf("memmove fwd mismatch: n=%d off=%d delta=%d", n, off, delta)
		}
		copy(qb, pb)
		copy(sb, pb)
		Xmemmove(tls, q+128+off+delta, q+128+off, n)
		___musl_memmove(tls, scratch+128+off+delta, scratch+128+off, n)
		if !bytes.Equal(qb, sb) {
			t.Fatalf("memmove bwd mismatch: n=%d off=%d delta=%d", n, off, delta)
		}

		// memset, with sizes large enough to reach the doubling path
		nbig := Tsize_t(rng.Intn(bufSize - 16))
		c := int32(rng.Intn(256))
		copy(qb, pb)
		copy(sb, pb)
		Xmemset(tls, q+off, c, nbig)
		___musl_memset(tls, scratch+off, c, nbig)
		if !bytes.Equal(qb, sb) {
			t.Fatalf("memset mismatch: n=%d off=%d c=%d", nbig, off, c)
		}

		// memcmp: mostly-equal buffers with an occasional planted difference
		copy(qb, pb)
		if rng.Intn(2) == 0 && nbig > 0 {
			qb[off+uintptr(rng.Intn(int(nbig)))] ^= byte(1 + rng.Intn(255))
		}
		want := ___musl_memcmp(tls, p+off, q+off, nbig)
		if got := Xmemcmp(tls, p+off, q+off, nbig); sign(got) != sign(want) {
			t.Fatalf("memcmp sign mismatch: n=%d off=%d got=%d want=%d", nbig, off, got, want)
		}

		// strcspn
		sLen := rng.Intn(100)
		for j := 0; j < sLen; j++ {
			qb[uintptr(j)+off] = byte(1 + rng.Intn(255))
		}
		qb[uintptr(sLen)+off] = 0
		cLen := rng.Intn(5)
		for j := 0; j < cLen; j++ {
			qb[256+uintptr(j)] = byte(1 + rng.Intn(255))
		}
		qb[256+uintptr(cLen)] = 0
		if got, want := Xstrcspn(tls, q+off, q+256), ___musl_strcspn(tls, q+off, q+256); got != want {
			t.Fatalf("strcspn mismatch: got=%d want=%d", got, want)
		}
	}

	for _, x := range []float64{0, math.Copysign(0, -1), 1.5, -1.5, math.Inf(1), math.Inf(-1), math.MaxFloat64, -math.SmallestNonzeroFloat64} {
		if got, want := Xfabs(tls, x), ___musl_fabs(tls, x); got != want {
			t.Fatalf("fabs(%g): got=%g want=%g", x, got, want)
		}
	}
	if got := Xfabs(tls, math.NaN()); !math.IsNaN(got) {
		t.Fatalf("fabs(NaN) = %g", got)
	}
}

func TestNativeMuslSignalChecks(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	tls.pendingSignals = make(chan os.Signal, 1)
	tls.checkSignals = true
	tls.sigHandlers[int32(unix.SIGUSR1)] = SIG_IGN
	check := func(name string, f func()) {
		t.Helper()
		tls.pendingSignals <- unix.SIGUSR1
		f()
		if got := len(tls.pendingSignals); got != 0 {
			t.Errorf("%s left %d pending signal(s)", name, got)
		}
	}

	check("fabs", func() { Xfabs(tls, -1) })

	s := Xmalloc(tls, 4)
	c := Xmalloc(tls, 2)
	defer Xfree(tls, s)
	defer Xfree(tls, c)
	copy(unsafe.Slice((*byte)(unsafe.Pointer(s)), 4), "abc\x00")
	copy(unsafe.Slice((*byte)(unsafe.Pointer(c)), 2), "x\x00")
	check("strcspn", func() { Xstrcspn(tls, s, c) })
}

var benchSizes = []int{8, 32, 512, 4096, 65536}

func benchBufs(b *testing.B, tls *TLS, size int) (p, q uintptr) {
	p = Xmalloc(tls, Tsize_t(size))
	q = Xmalloc(tls, Tsize_t(size))
	rng := rand.New(rand.NewSource(1))
	rng.Read(unsafe.Slice((*byte)(unsafe.Pointer(p)), size))
	b.Cleanup(func() {
		Xfree(tls, p)
		Xfree(tls, q)
	})
	return p, q
}

func BenchmarkXmemcpy(b *testing.B) {
	tls := NewTLS()
	defer tls.Close()
	for _, impl := range []struct {
		name string
		f    func(*TLS, uintptr, uintptr, Tsize_t) uintptr
	}{
		{"native", Xmemcpy},
		{"musl", ___musl_memcpy},
	} {
		b.Run(impl.name, func(b *testing.B) {
			for _, size := range benchSizes {
				b.Run(fmtSize(size), func(b *testing.B) {
					p, q := benchBufs(b, tls, size)
					b.SetBytes(int64(size))
					for i := 0; i < b.N; i++ {
						impl.f(tls, q, p, Tsize_t(size))
					}
				})
			}
		})
	}
}

func BenchmarkXmemset(b *testing.B) {
	tls := NewTLS()
	defer tls.Close()
	for _, impl := range []struct {
		name string
		f    func(*TLS, uintptr, int32, Tsize_t) uintptr
	}{
		{"native", Xmemset},
		{"musl", ___musl_memset},
	} {
		b.Run(impl.name, func(b *testing.B) {
			for _, c := range []int32{0, 'x'} {
				name := "zero"
				if c != 0 {
					name = "nonzero"
				}
				b.Run(name, func(b *testing.B) {
					for _, size := range benchSizes {
						b.Run(fmtSize(size), func(b *testing.B) {
							p, _ := benchBufs(b, tls, size)
							b.SetBytes(int64(size))
							for i := 0; i < b.N; i++ {
								impl.f(tls, p, c, Tsize_t(size))
							}
						})
					}
				})
			}
		})
	}
}

func BenchmarkXmemcmp(b *testing.B) {
	tls := NewTLS()
	defer tls.Close()
	for _, impl := range []struct {
		name string
		f    func(*TLS, uintptr, uintptr, Tsize_t) int32
	}{
		{"native", Xmemcmp},
		{"musl", ___musl_memcmp},
	} {
		b.Run(impl.name, func(b *testing.B) {
			for _, scenario := range []string{"eq", "diffmid"} {
				b.Run(scenario, func(b *testing.B) {
					for _, size := range benchSizes {
						b.Run(fmtSize(size), func(b *testing.B) {
							p, q := benchBufs(b, tls, size)
							Xmemcpy(tls, q, p, Tsize_t(size))
							if scenario == "diffmid" {
								qb := unsafe.Slice((*byte)(unsafe.Pointer(q)), size)
								qb[size/2] ^= 0xff
							}
							b.SetBytes(int64(size))
							var sink int32
							for i := 0; i < b.N; i++ {
								sink += impl.f(tls, p, q, Tsize_t(size))
							}
							_ = sink
						})
					}
				})
			}
		})
	}
}

func BenchmarkXfabs(b *testing.B) {
	tls := NewTLS()
	defer tls.Close()
	for _, impl := range []struct {
		name string
		f    func(*TLS, float64) float64
	}{
		{"native", Xfabs},
		{"musl", ___musl_fabs},
	} {
		b.Run(impl.name, func(b *testing.B) {
			var sink float64
			for i := 0; i < b.N; i++ {
				sink += impl.f(tls, -1.5)
			}
			_ = sink
		})
	}
}

func BenchmarkXstrcspn(b *testing.B) {
	tls := NewTLS()
	defer tls.Close()
	// JSON-ish workload: a run of ordinary characters terminated by a
	// quote, scanned for the usual string-terminator set.
	s := Xmalloc(tls, 256)
	c := Xmalloc(tls, 8)
	defer Xfree(tls, s)
	defer Xfree(tls, c)
	sb := unsafe.Slice((*byte)(unsafe.Pointer(s)), 256)
	for i := range sb {
		sb[i] = 'a'
	}
	copy(unsafe.Slice((*byte)(unsafe.Pointer(c)), 3), "\"\\\x00")
	for _, impl := range []struct {
		name string
		f    func(*TLS, uintptr, uintptr) Tsize_t
	}{
		{"native", Xstrcspn},
		{"musl", ___musl_strcspn},
	} {
		b.Run(impl.name, func(b *testing.B) {
			for _, size := range []int{7, 40, 200} {
				b.Run(fmtSize(size), func(b *testing.B) {
					sb[size] = '"'
					b.SetBytes(int64(size))
					var sink Tsize_t
					for i := 0; i < b.N; i++ {
						sink += impl.f(tls, s, c)
					}
					_ = sink
					sb[size] = 'a'
				})
			}
		})
	}
}

func fmtSize(n int) string {
	switch {
	case n >= 1<<20 && n%(1<<20) == 0:
		return strconv.Itoa(n>>20) + "M"
	case n >= 1<<10 && n%(1<<10) == 0:
		return strconv.Itoa(n>>10) + "K"
	}
	return strconv.Itoa(n)
}
