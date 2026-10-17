// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build linux && (amd64 || arm64 || loong64 || ppc64le || s390x || riscv64)

package libc // import "modernc.org/libc"

import (
	"testing"
	"unsafe"
)

// https://gitlab.com/cznic/libc/-/issues/53

func TestIssue53Atomics(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	p := Xmalloc(tls, 32)
	defer Xfree(tls, p)

	w := (*int32)(unsafe.Pointer(p))
	*w = 7
	if g := _a_swap(tls, p, 1); g != 7 || *w != 1 {
		t.Errorf("a_swap: got %v, value %v", g, *w)
	}
	if g := _a_fetch_add(tls, p, 4); g != 1 || *w != 5 {
		t.Errorf("a_fetch_add: got %v, value %v", g, *w)
	}
	_a_or(tls, p, 8)
	if *w != 13 {
		t.Errorf("a_or: value %v", *w)
	}
	_a_and(tls, p, 12)
	if *w != 12 {
		t.Errorf("a_and: value %v", *w)
	}
	_a_store(tls, p, 3)
	if *w != 3 {
		t.Errorf("a_store: value %v", *w)
	}

	pp := p + 8
	*(*uintptr)(unsafe.Pointer(pp)) = 0x10
	if g := _a_cas_p(tls, pp, 0x10, 0x20); g != 0x10 {
		t.Errorf("a_cas_p: got %#x", g)
	}
	if g := _a_cas_p(tls, pp, 0x10, 0x30); g != 0x20 {
		t.Errorf("a_cas_p: got %#x", g)
	}

	q := p + 16
	w64 := (*uint64)(unsafe.Pointer(q))
	*w64 = 0xf0
	_a_or_64(tls, q, 0x0f)
	if *w64 != 0xff {
		t.Errorf("a_or_64: value %#x", *w64)
	}
	_a_and_64(tls, q, 0x3c)
	if *w64 != 0x3c {
		t.Errorf("a_and_64: value %#x", *w64)
	}

	if g, e := _a_clz_64(tls, 1), int32(63); g != e {
		t.Errorf("a_clz_64: got %v, expected %v", g, e)
	}
	if g, e := _a_ctz_64(tls, 1<<40), int32(40); g != e {
		t.Errorf("a_ctz_64: got %v, expected %v", g, e)
	}
	_a_barrier(tls)
	_a_spin(tls)
}
