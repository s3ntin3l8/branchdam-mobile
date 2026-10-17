// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

// The mirroring is done by wrappers the generator appends to the generated
// files, so it exists only on regenerated targets. Widen the constraint to
// every musl target once all of them have been regenerated.

//go:build linux && amd64

package libc // import "modernc.org/libc"

import (
	"os"
	"testing"
)

// https://gitlab.com/cznic/libc/-/issues/58

func TestIssue58EnvMirror(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	name, err := CString("LIBC_ISSUE58")
	if err != nil {
		t.Fatal(err)
	}

	defer Xfree(tls, name)

	value, err := CString("one")
	if err != nil {
		t.Fatal(err)
	}

	defer Xfree(tls, value)

	os.Unsetenv("LIBC_ISSUE58")
	if g := Xsetenv(tls, name, value, 1); g != 0 {
		t.Fatalf("setenv: %v", g)
	}
	if g, e := os.Getenv("LIBC_ISSUE58"), "one"; g != e {
		t.Fatalf("os.Getenv after setenv: %q, expected %q", g, e)
	}

	// Not overwriting leaves both environments alone.
	value2, _ := CString("two")
	defer Xfree(tls, value2)

	if g := Xsetenv(tls, name, value2, 0); g != 0 {
		t.Fatalf("setenv: %v", g)
	}
	if g, e := os.Getenv("LIBC_ISSUE58"), "one"; g != e {
		t.Fatalf("os.Getenv after setenv without overwrite: %q, expected %q", g, e)
	}
	if g, e := GoString(Xgetenv(tls, name)), "one"; g != e {
		t.Fatalf("getenv after setenv without overwrite: %q, expected %q", g, e)
	}

	kv, _ := CString("LIBC_ISSUE58=three")
	// putenv keeps the string, so it is not freed here.
	if g := Xputenv(tls, kv); g != 0 {
		t.Fatalf("putenv: %v", g)
	}
	if g, e := os.Getenv("LIBC_ISSUE58"), "three"; g != e {
		t.Fatalf("os.Getenv after putenv: %q, expected %q", g, e)
	}

	if g := Xunsetenv(tls, name); g != 0 {
		t.Fatalf("unsetenv: %v", g)
	}
	if _, ok := os.LookupEnv("LIBC_ISSUE58"); ok {
		t.Fatal("os.LookupEnv after unsetenv still finds the variable")
	}
	if g := Xgetenv(tls, name); g != 0 {
		t.Fatalf("getenv after unsetenv: %q", GoString(g))
	}
}
