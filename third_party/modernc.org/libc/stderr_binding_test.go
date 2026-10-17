// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build darwin || freebsd || netbsd || openbsd

package libc // import "modernc.org/libc"

import (
	"testing"

	"modernc.org/libc/unistd"
)

// The X__std{in,out,err}p trio is the binding ccgo-generated code links C
// stdin/stdout/stderr against on these ports. Nothing inside libc reads them,
// so a wrong alias is invisible to a build and to every other test; it only
// shows up as a transpiled C program writing to the wrong descriptor. That is
// how X__stderrp spent five years pointing at Xstdout, copied from port to
// port. On netbsd and openbsd these also pin X__sF, which newFile returns for
// the three standard descriptors.
func TestStdioBinding(t *testing.T) {
	for _, v := range []struct {
		name string
		p    uintptr
		want int32
	}{
		{"X__stdinp", X__stdinp, unistd.STDIN_FILENO},
		{"X__stdoutp", X__stdoutp, unistd.STDOUT_FILENO},
		{"X__stderrp", X__stderrp, unistd.STDERR_FILENO},
	} {
		if v.p == 0 {
			t.Errorf("%s is nil", v.name)
			continue
		}

		if got := file(v.p).fd(); got != v.want {
			t.Errorf("%s is bound to fd %v, want %v", v.name, got, v.want)
		}
	}
}
