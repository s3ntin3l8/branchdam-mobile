// Copyright 2025 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package libc // import "modernc.org/libc"

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"
	"unsafe"

	_ "golang.org/x/tools/go/packages" // genasm.go
	ccgo "modernc.org/ccgo/v4/lib"
	_ "modernc.org/goabi0" // genasm.go
)

var (
	goarch = runtime.GOARCH
	goos   = runtime.GOOS
)

// https://gitlab.com/cznic/libc/-/issues/42
func TestIssue42(t *testing.T) {
	if goos == "windows" {
		t.Skip("SKIP: windows")
	}

	dir := t.TempDir()

	defer os.Remove("test_pread_pwrite.txt")

	gof := filepath.Join(dir, "main.go")
	if err := ccgo.NewTask(
		goos, goarch,
		[]string{
			os.Args[0],
			"-ignore-unsupported-alignment",
			"-o", gof,
			filepath.Join("testdata", "pread_test.c"),
		},
		os.Stdout, os.Stderr,
		nil,
	).Main(); err != nil {
		t.Fatal(err)
	}

	b, err := exec.Command("go", "run", gof).CombinedOutput()
	if err != nil {
		t.Fatalf("FAIL err=%v\n%s", err, b)
	}

	t.Logf("%s", b)
}

func TestPutchar(t *testing.T) {
	tls := NewTLS()

	defer tls.Close()

	for c := '!'; c < 127; c++ {
		Xputchar(tls, int32(c))
	}
	Xputchar(tls, '\r')
	Xputchar(tls, '\n')
}

var (
	strlen0 = [...]byte{0}
	strlen1 = [...]byte{1, 0}
)

func TestStrlen(t *testing.T) {
	if g, e := strlen(0), Tsize_t(0); g != e {
		t.Fatal(g, e)
	}

	if g, e := strlen(uintptr(unsafe.Pointer(&strlen0[0]))), Tsize_t(0); g != e {
		t.Fatal(g, e)
	}

	if g, e := strlen(uintptr(unsafe.Pointer(&strlen1[0]))), Tsize_t(1); g != e {
		t.Fatal(g, e)
	}
}

// TestVaListString checks that a Go string is rejected instead of landing in the
// va_list as its 16 byte {ptr, len} header, which C would read as a char* and,
// Go strings not being NUL terminated, walk past the end of, appending adjacent
// Go heap. See https://gitlab.com/cznic/quickjs/-/issues/14.
func TestVaListString(t *testing.T) {
	defer func() {
		e := "libc.VaList: a Go string is not a valid C variadic argument, use CString"
		switch g := recover().(type) {
		case nil:
			t.Fatal("VaList accepted a Go string")
		case string:
			if g != e {
				t.Fatalf("got %q, expected %q", g, e)
			}
		default:
			t.Fatalf("got a %T panic value: %v", g, g)
		}
	}()

	VaList(uintptr(unsafe.Pointer(&valist[0])), "foo")
}
