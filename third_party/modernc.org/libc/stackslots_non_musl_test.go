// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build !(linux && (amd64 || arm64 || loong64 || ppc64le || s390x || riscv64 || 386 || arm))

package libc // import "modernc.org/libc"

import "testing"

// https://gitlab.com/cznic/libc/-/issues/58

func TestIssue58StackSlots(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	n := tls.StackSlots()
	tls.Alloc(8)
	tls.Alloc(1024)
	if g, e := tls.StackSlots(), n+2; g != e {
		t.Fatalf("got %v, expected %v", g, e)
	}
	tls.Free(1024)
	tls.Free(8)
	if g, e := tls.StackSlots(), n; g != e {
		t.Fatalf("got %v, expected %v", g, e)
	}
}
