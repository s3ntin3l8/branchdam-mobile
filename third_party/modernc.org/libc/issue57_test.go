// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build !(linux && (amd64 || arm64 || loong64 || ppc64le || s390x || riscv64 || 386 || arm))

package libc // import "modernc.org/libc"

import (
	"strings"
	"testing"
	"unsafe"
)

// https://gitlab.com/cznic/libc/-/issues/57
//
// The scanner in scanf.go serves the hand-written ports; its %c conversion
// is checked on every one of them.
func TestIssue57ScanfC(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	p := Xmalloc(tls, 256)
	defer Xfree(tls, p)

	format, va, out := p, p+64, p+128
	copy(unsafe.Slice((*byte)(unsafe.Pointer(format)), 32), "%X:%X:%X:%X:%X:%X%c\x00")
	for i := uintptr(0); i < 7; i++ {
		*(*uint32)(unsafe.Pointer(out + 4*i)) = 0xdead
	}
	args := VaList(va, out, out+4, out+8, out+12, out+16, out+20, out+24)
	if g := scanf(strings.NewReader("01:23:45:67:89:ab-tail"), format, args); g != 7 {
		t.Fatalf("got %v conversions, expected 7", g)
	}
	for i, e := range []uint32{0x01, 0x23, 0x45, 0x67, 0x89, 0xab} {
		if g := *(*uint32)(unsafe.Pointer(out + 4*uintptr(i))); g != e {
			t.Errorf("field %d: got %#x, expected %#x", i, g, e)
		}
	}
	if g := *(*byte)(unsafe.Pointer(out + 24)); g != '-' {
		t.Errorf("%%c: got %q, expected '-'", g)
	}

	// A width reads that many bytes, whitespace included, and %*c discards.
	copy(unsafe.Slice((*byte)(unsafe.Pointer(format)), 32), "%3c%*c%d\x00")
	args = VaList(va, out, out+8)
	if g := scanf(strings.NewReader("a b!42"), format, args); g != 2 {
		t.Fatalf("got %v conversions, expected 2", g)
	}
	if g := string(unsafe.Slice((*byte)(unsafe.Pointer(out)), 3)); g != "a b" {
		t.Errorf("%%3c: got %q", g)
	}
	if g := *(*uint32)(unsafe.Pointer(out + 8)); g != 42 {
		t.Errorf("%%d after %%*c: got %v", g)
	}

	// Input ends before the character: no conversion.
	copy(unsafe.Slice((*byte)(unsafe.Pointer(format)), 32), "%d%c\x00")
	args = VaList(va, out, out+8)
	if g := scanf(strings.NewReader("7"), format, args); g != 1 {
		t.Fatalf("got %v conversions, expected 1", g)
	}
}
