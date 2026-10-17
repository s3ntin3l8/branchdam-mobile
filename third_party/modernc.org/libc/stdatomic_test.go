// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package libc // import "modernc.org/libc"

import (
	"runtime"
	"testing"
	"unsafe"
)

// atomicSeqCst is the C __ATOMIC_SEQ_CST memory order. The package constant
// __ATOMIC_SEQ_CST is emitted only by the ccgo_linux_*.go generated files, so
// this test (which is built on every target) defines its own copy to stay
// portable. The float helpers ignore the memory order anyway.
const atomicSeqCst = 5

// alignedCells returns three 8-byte-aligned, 8-byte-wide cells (plus the
// backing slice the caller must keep alive). 64-bit atomics panic on unaligned
// addresses on 386/arm/32-bit-mips, and natural Go allocation only aligns
// 64-bit values to 4 bytes there (unsafe.Alignof(float64) == 4 on 386), so
// align manually within an over-allocated byte buffer. 8-byte alignment also
// satisfies the 32-bit float case.
func alignedCells(t *testing.T) (raw []byte, c0, c1, c2 unsafe.Pointer) {
	raw = make([]byte, 8*3+7)
	off := (8 - uintptr(unsafe.Pointer(&raw[0]))&7) & 7
	c0 = unsafe.Pointer(&raw[off])
	c1 = unsafe.Pointer(&raw[off+8])
	c2 = unsafe.Pointer(&raw[off+16])
	for _, p := range []unsafe.Pointer{c0, c1, c2} {
		if uintptr(p)&7 != 0 {
			t.Fatalf("cell %#x is not 8-byte aligned", uintptr(p))
		}
	}
	return raw, c0, c1, c2
}

// The float atomic helpers round-trip a value through the out-parameter ABI
// (__atomic_store writes *val into *ptr, __atomic_load writes *ptr into *ret),
// atomically copying the bit pattern. wasm2c emits them for f32/f64 access to
// shared linear memory; they delegate to the same-width integer helpers.
func TestAtomicLoadStoreFloat(t *testing.T) {
	tls := NewTLS()

	defer tls.Close()

	t.Run("Float32", func(t *testing.T) {
		raw, cp, vp, rp := alignedCells(t)
		defer runtime.KeepAlive(raw)
		cell, val, ret := (*float32)(cp), (*float32)(vp), (*float32)(rp)
		*val = 3.14159

		X__atomic_storeFloat32(tls, uintptr(cp), uintptr(vp), atomicSeqCst)
		if *cell != *val {
			t.Fatalf("store: cell = %v, want %v", *cell, *val)
		}

		X__atomic_loadFloat32(tls, uintptr(cp), uintptr(rp), atomicSeqCst)
		if *ret != *val {
			t.Fatalf("load: ret = %v, want %v", *ret, *val)
		}
	})

	t.Run("Float64", func(t *testing.T) {
		raw, cp, vp, rp := alignedCells(t)
		defer runtime.KeepAlive(raw)
		cell, val, ret := (*float64)(cp), (*float64)(vp), (*float64)(rp)
		*val = 2.718281828459045

		X__atomic_storeFloat64(tls, uintptr(cp), uintptr(vp), atomicSeqCst)
		if *cell != *val {
			t.Fatalf("store: cell = %v, want %v", *cell, *val)
		}

		X__atomic_loadFloat64(tls, uintptr(cp), uintptr(rp), atomicSeqCst)
		if *ret != *val {
			t.Fatalf("load: ret = %v, want %v", *ret, *val)
		}
	})
}
