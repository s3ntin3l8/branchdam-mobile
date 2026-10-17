// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package libc // import "modernc.org/libc"

import (
	"math"
	"math/big"
	"math/rand"
	"reflect"
	"testing"
	"unsafe"
)

// https://gitlab.com/cznic/libc/-/issues/56

func issue56CString(tls *TLS, s string) uintptr {
	p := Xmalloc(tls, Tsize_t(len(s)+1))
	copy(unsafe.Slice((*byte)(unsafe.Pointer(p)), len(s)+1), s+"\x00")
	return p
}

func TestIssue56Libm(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	eq := func(name string, g, e float64) {
		t.Helper()
		if g != e && !(math.IsNaN(g) && math.IsNaN(e)) {
			t.Errorf("%s: got %v, expected %v", name, g, e)
		}
	}

	// Only results that are exactly representable are checked, the musl and
	// the Go math based ports differ in the last bits elsewhere.
	eq("acosh", X__builtin_acosh(tls, 1), 0)
	eq("asinh", X__builtin_asinh(tls, 0), 0)
	eq("asinhf", float64(X__builtin_asinhf(tls, 0)), 0)
	eq("atanh", X__builtin_atanh(tls, 0), 0)
	eq("cbrt", X__builtin_cbrt(tls, 27), 3)
	eq("erf", X__builtin_erf(tls, 0), 0)
	eq("erf", X__builtin_erf(tls, math.Inf(1)), 1)
	eq("erfc", X__builtin_erfc(tls, 0), 1)
	eq("exp2", X__builtin_exp2(tls, 3), 8)
	eq("expm1", X__builtin_expm1(tls, 0), 0)
	eq("fdim", X__builtin_fdim(tls, 3, 1), 2)
	eq("fdim", X__builtin_fdim(tls, 1, 3), 0)
	eq("fdim", X__builtin_fdim(tls, math.Inf(1), math.Inf(1)), 0)
	eq("fdim", X__builtin_fdim(tls, math.NaN(), 1), math.NaN())
	eq("fma", X__builtin_fma(tls, 2, 3, 4), 10)
	eq("fmaf", float64(X__builtin_fmaf(tls, 2, 3, 4)), 10)
	eq("lgamma", X__builtin_lgamma(tls, 1), 0)
	eq("lgamma", X__builtin_lgamma(tls, 2), 0)
	eq("log1p", X__builtin_log1p(tls, 0), 0)
	eq("log1pf", float64(X__builtin_log1pf(tls, 0)), 0)
	eq("logb", X__builtin_logb(tls, 8), 3)
	eq("logb", X__builtin_logb(tls, 0.1), -4)
	eq("logb", X__builtin_logb(tls, 0), math.Inf(-1))
	eq("nextafter", X__builtin_nextafter(tls, 1, 2), 1+0x1p-52)
	eq("nextafterf", float64(X__builtin_nextafterf(tls, 1, 2)), 1+0x1p-23)
	eq("nexttoward", X__builtin_nexttoward(tls, 1, 0), 1-0x1p-53)
	eq("remainder", X__builtin_remainder(tls, 5.5, 2), -0.5)
	eq("remainder", X__builtin_remainder(tls, 7, 2), -1)
	eq("rint", X__builtin_rint(tls, 2.5), 2)
	eq("rint", X__builtin_rint(tls, 3.5), 4)
	eq("scalbn", X__builtin_scalbn(tls, 1.5, 3), 12)
	eq("scalbn", X__builtin_scalbn(tls, 1, -1074), 0x1p-1074)
	eq("scalbn", X__builtin_scalbn(tls, 1, 1024), math.Inf(1))
	eq("tgamma", X__builtin_tgamma(tls, 5), 24)

	for _, v := range []struct {
		x float64
		e int32
	}{
		{8, 3},
		{0.1, -4},
		{0x1p-1074, -1074},
		{0, math.MinInt32},
		{math.NaN(), math.MinInt32},
		{math.Inf(1), math.MaxInt32},
		{math.Inf(-1), math.MaxInt32},
	} {
		if g, e := Xilogb(tls, v.x), v.e; g != e {
			t.Errorf("ilogb(%v): got %v, expected %v", v.x, g, e)
		}
	}

	// remquo returns the low 31 bits of the quotient, as musl does.
	quo := Xmalloc(tls, 4)
	defer Xfree(tls, quo)

	for _, v := range []struct {
		x, y, r float64
		q       int32
	}{
		{5.5, 2, -0.5, 3},
		{-5.5, 2, 0.5, -3},
		{7, 2, -1, 4},
		{-7, 2, 1, -4},
		{5, 2, 1, 2},
		{5, -2, 1, -2},
		{1e10, 7, -3, 1428571429},
		{0x1p-1070, 0x1p-1074, 0, 16},
		{1.5, 0.5, 0, 3},
	} {
		*(*int32)(unsafe.Pointer(quo)) = -1
		g := X__builtin_remquo(tls, v.x, v.y, quo)
		if q := *(*int32)(unsafe.Pointer(quo)); g != v.r || q != v.q {
			t.Errorf("remquo(%v, %v): got %v, %v, expected %v, %v", v.x, v.y, g, q, v.r, v.q)
		}
	}
	if g := X__builtin_remquo(tls, 1, 0, quo); !math.IsNaN(g) {
		t.Errorf("remquo(1, 0): got %v, expected NaN", g)
	}
	if g := X__builtin_remquo(tls, math.Inf(1), 1, quo); !math.IsNaN(g) {
		t.Errorf("remquo(inf, 1): got %v, expected NaN", g)
	}
}

func TestIssue56Fmaf(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	// x*y+z computed in double precision is exactly halfway between two
	// floats while the exact value is below the midpoint. Converting the
	// double to float rounds to even, which is the wrong neighbour.
	x, y, z := float32(0x1.000002p-24), float32(0x1.fffffcp-1), float32(0x1.000002p+0)
	if g, e := float32(float64(x)*float64(y)+float64(z)), float32(0x1.000004p+0); g != e {
		t.Fatalf("test vector is not a double rounding case: %x", g)
	}

	if g, e := Xfmaf(tls, x, y, z), float32(0x1.000002p+0); g != e {
		t.Errorf("got %x, expected %x", g, e)
	}

	ref := func(x, y, z float32) float32 {
		var bx, by, bz, r big.Float
		bx.SetPrec(2048).SetFloat64(float64(x))
		by.SetPrec(2048).SetFloat64(float64(y))
		bz.SetPrec(2048).SetFloat64(float64(z))
		r.SetPrec(2048).Mul(&bx, &by)
		r.Add(&r, &bz)
		f, _ := r.Float32()
		return f
	}
	rng := rand.New(rand.NewSource(42))
	rnd := func(minExp, maxExp int) float32 {
		bits := rng.Uint32()&0x7fffff | uint32(rng.Intn(maxExp-minExp+1)+minExp)<<23
		if rng.Intn(2) == 0 {
			bits |= 1 << 31
		}
		return math.Float32frombits(bits)
	}
	for i := 0; i < 100000; i++ {
		var x, y, z float32
		switch i % 3 {
		case 0: // whole range, including overflow and underflow
			x, y, z = rnd(1, 254), rnd(1, 254), rnd(1, 254)
		case 1: // comparable magnitudes, cancellation and ties
			x, y = rnd(120, 134), rnd(120, 134)
			z = rnd(120, 134)
		default: // z far below the product, sticky bits
			x, y = rnd(120, 134), rnd(120, 134)
			z = rnd(60, 100)
		}
		if g, e := Xfmaf(tls, x, y, z), ref(x, y, z); g != e {
			t.Fatalf("#%d: fmaf(%x, %x, %x): got %x, expected %x", i, x, y, z, g, e)
		}
	}
}

func TestIssue56Atomics(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	p := Xmalloc(tls, 16)
	defer Xfree(tls, p)

	for i := uintptr(0); i < 16; i++ {
		*(*byte)(unsafe.Pointer(p + i)) = 0xaa
	}
	AtomicStorePInt8(p, -7)
	AtomicStorePUint8(p+1, 200)
	AtomicStorePInt16(p+2, -300)
	AtomicStorePUint16(p+4, 40000)
	if g, e := AtomicLoadPInt8(p), int8(-7); g != e {
		t.Errorf("got %v, expected %v", g, e)
	}
	if g, e := AtomicLoadPUint8(p+1), uint8(200); g != e {
		t.Errorf("got %v, expected %v", g, e)
	}
	if g, e := AtomicLoadPInt16(p+2), int16(-300); g != e {
		t.Errorf("got %v, expected %v", g, e)
	}
	if g, e := AtomicLoadPUint16(p+4), uint16(40000); g != e {
		t.Errorf("got %v, expected %v", g, e)
	}
	for i := uintptr(6); i < 16; i++ {
		if g, e := *(*byte)(unsafe.Pointer(p + i)), byte(0xaa); g != e {
			t.Errorf("byte %d: got %#x, expected %#x", i, g, e)
		}
	}
}

func TestIssue56Builtins(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	if g, e := X__flt_rounds(tls), int32(1); g != e {
		t.Errorf("got %v, expected %v", g, e)
	}
	if g, e := X__builtin_flt_rounds(tls), int32(1); g != e {
		t.Errorf("got %v, expected %v", g, e)
	}

	// clzl counts leading zeros of the C unsigned long, which is 32 bits
	// wide on windows and on the 32 bit ports.
	bits := int32(reflect.TypeOf(X__builtin_clzl).In(1).Size() * 8)
	if g, e := X__builtin_clzl(tls, 1), bits-1; g != e {
		t.Errorf("got %v, expected %v", g, e)
	}
	if g, e := X__builtin_clzl(tls, 1<<31), bits-32; g != e {
		t.Errorf("got %v, expected %v", g, e)
	}

	dest := Xmalloc(tls, 32)
	defer Xfree(tls, dest)

	src := issue56CString(tls, "cdef")
	defer Xfree(tls, src)

	copy(unsafe.Slice((*byte)(unsafe.Pointer(dest)), 3), "ab\x00")
	if g, e := X__builtin___strncat_chk(tls, dest, src, 3, 32), dest; g != e {
		t.Errorf("got %#x, expected %#x", g, e)
	}
	if g, e := GoString(dest), "abcde"; g != e {
		t.Errorf("got %q, expected %q", g, e)
	}
	X__builtin___strncat_chk(tls, dest, src, 100, ^Tsize_t(0))
	if g, e := GoString(dest), "abcdecdef"; g != e {
		t.Errorf("got %q, expected %q", g, e)
	}

	format := issue56CString(tls, "%s|%d")
	defer Xfree(tls, format)

	arg := issue56CString(tls, "xyz")
	defer Xfree(tls, arg)

	va := Xmalloc(tls, 64)
	defer Xfree(tls, va)

	if g, e := X__builtin___vsprintf_chk(tls, dest, 0, 32, format, VaList(va, arg, int32(42))), int32(6); g != e {
		t.Errorf("got %v, expected %v", g, e)
	}
	if g, e := GoString(dest), "xyz|42"; g != e {
		t.Errorf("got %q, expected %q", g, e)
	}
}
