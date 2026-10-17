// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build unix

package libc // import "modernc.org/libc"

import (
	"os"
	"testing"
	"unsafe"

	"golang.org/x/sys/unix"
)

// TestStrlenGuardPage verifies that strlen does not read past the end
// of the page holding the terminating NUL: the string is placed so
// the NUL is the last byte before a PROT_NONE guard page, where any
// overread faults.
func TestStrlenGuardPage(t *testing.T) {
	pg := os.Getpagesize()
	mem, err := unix.Mmap(-1, 0, 2*pg, unix.PROT_READ|unix.PROT_WRITE, unix.MAP_ANON|unix.MAP_PRIVATE)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Munmap(mem)
	if err := unix.Mprotect(mem[pg:], unix.PROT_NONE); err != nil {
		t.Fatal(err)
	}

	base := uintptr(unsafe.Pointer(&mem[0]))
	for i := 0; i < pg-1; i++ {
		mem[i] = 'x'
	}
	mem[pg-1] = 0
	for _, n := range []int{0, 1, 2, 7, 8, 9, 63, 64, 65, 511, 4095} {
		if n >= pg {
			continue
		}
		s := base + uintptr(pg) - uintptr(n) - 1
		if got := strlen(s); got != Tsize_t(n) {
			t.Fatalf("strlen: n=%d got=%d", n, got)
		}
		if got := strlenIndexByte(s); got != Tsize_t(n) {
			t.Fatalf("strlenIndexByte: n=%d got=%d", n, got)
		}
		if got := strlenWords(s); got != Tsize_t(n) {
			t.Fatalf("strlenWords: n=%d got=%d", n, got)
		}
	}
}
