// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build windows

package libc // import "modernc.org/libc"

import (
	"os"
	"path/filepath"
	"testing"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
	"modernc.org/libc/errno"
	"modernc.org/libc/fcntl"
	"modernc.org/libc/sys/types"
)

// https://gitlab.com/cznic/libc/-/issues/57

func issue57CString(t *TLS, s string) uintptr {
	p := Xmalloc(t, types.Size_t(len(s)+1))
	copy(unsafe.Slice((*byte)(unsafe.Pointer(p)), len(s)+1), s+"\x00")
	return p
}

func issue57WString(t *TLS, s string) uintptr {
	return allocW(t, s)
}

func TestIssue57Environ(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	// _wenviron is null terminated and lists every variable.
	os.Setenv("LIBC_ISSUE57_BOOT", "1")
	wenvValid = false
	env := *(*uintptr)(unsafe.Pointer(X__p__wenviron(tls)))
	n := 0
	seen := false
	for ; ; n++ {
		q := *(*uintptr)(unsafe.Pointer(env + uintptr(n)*unsafe.Sizeof(uintptr(0))))
		if q == 0 {
			break
		}

		if goWideStringNZ(q) == "LIBC_ISSUE57_BOOT=1" {
			seen = true
		}
	}
	if !seen || n != len(os.Environ()) {
		t.Fatalf("_wenviron: %v entries, os has %v, variable seen %v", n, len(os.Environ()), seen)
	}

	// The last entry is found, and one the Go side sets later too.
	k := issue57WString(tls, "LIBC_ISSUE57_BOOT")
	if g := X_wgetenv(tls, k); g == 0 || goWideStringNZ(g) != "1" {
		t.Fatalf("_wgetenv: %#x %q", g, goWideStringNZ(g))
	}
	os.Setenv("LIBC_ISSUE57_LATE", "late")
	if g := X_wgetenv(tls, issue57WString(tls, "LIBC_ISSUE57_LATE")); g == 0 || goWideStringNZ(g) != "late" {
		t.Fatalf("_wgetenv of a variable set after boot: %#x", g)
	}

	// _wputenv is visible to the narrow getenv and to Go.
	if g := X_wputenv(tls, issue57WString(tls, "LIBC_ISSUE57_PUT=wide")); g != 0 {
		t.Fatalf("_wputenv: %v", g)
	}
	if g := Xgetenv(tls, issue57CString(tls, "LIBC_ISSUE57_PUT")); g == 0 || GoString(g) != "wide" {
		t.Fatalf("getenv after _wputenv: %#x %q", g, GoString(g))
	}
	if g := os.Getenv("LIBC_ISSUE57_PUT"); g != "wide" {
		t.Fatalf("Go environment after _wputenv: %q", g)
	}
	if g := X_wputenv(tls, issue57WString(tls, "LIBC_ISSUE57_PUT=again")); g != 0 {
		t.Fatalf("_wputenv: %v", g)
	}
	if g := GoString(Xgetenv(tls, issue57CString(tls, "LIBC_ISSUE57_PUT"))); g != "again" {
		t.Fatalf("getenv after the second _wputenv: %q", g)
	}
	if g := X_wputenv(tls, issue57WString(tls, "NOEQUALS")); g != -1 {
		t.Fatalf("_wputenv without '=': %v", g)
	}
}

func TestIssue57Strings(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	w := issue57WString(tls, "abc")
	defer Xfree(tls, w)

	if g := Xwcschr(tls, w, 'b'); g != w+2 {
		t.Errorf("wcschr: got %#x, expected %#x", g, w+2)
	}
	if g := Xwcschr(tls, w, 0); g != w+6 {
		t.Errorf("wcschr(0): got %#x, expected %#x", g, w+6)
	}
	if g := Xwcschr(tls, w, 'z'); g != 0 {
		t.Errorf("wcschr(z): got %#x", g)
	}

	if g := GoString(Xsetlocale(tls, 0, 0)); g != "C" {
		t.Errorf("setlocale query: %q", g)
	}
	if g := GoString(Xsetlocale(tls, 0, issue57CString(tls, ""))); g != "C" {
		t.Errorf("setlocale(\"\"): %q", g)
	}
	if g := Xsetlocale(tls, 0, issue57CString(tls, "de_DE")); g != 0 {
		t.Errorf("setlocale(de_DE): %#x", g)
	}

	buf := Xmalloc(tls, 32)
	defer Xfree(tls, buf)

	copy(unsafe.Slice((*byte)(unsafe.Pointer(buf)), 3), "ab\x00")
	if g := Xstrncat(tls, buf, issue57CString(tls, "cdef"), 3); g != buf || GoString(buf) != "abcde" {
		t.Errorf("strncat: %q", GoString(buf))
	}

	end := buf + 16
	if g := Xstrtoll(tls, issue57CString(tls, "  -123xyz"), end, 10); g != -123 || GoString(*(*uintptr)(unsafe.Pointer(end))) != "xyz" {
		t.Errorf("strtoll: %v %q", g, GoString(*(*uintptr)(unsafe.Pointer(end))))
	}
	if g := X_strtoi64(tls, issue57CString(tls, "0x7fffffffffffffff"), 0, 0); g != 1<<63-1 {
		t.Errorf("_strtoi64 hex: %v", g)
	}
	*(*int32)(unsafe.Pointer(X__errno_location(tls))) = 0
	if g := Xstrtoll(tls, issue57CString(tls, "9223372036854775808"), 0, 10); g != 1<<63-1 || *(*int32)(unsafe.Pointer(X__errno_location(tls))) != errno.ERANGE {
		t.Errorf("strtoll overflow: %v errno %v", g, *(*int32)(unsafe.Pointer(X__errno_location(tls))))
	}
	if g := Xstrtoull(tls, issue57CString(tls, "18446744073709551615"), 0, 10); g != 1<<64-1 {
		t.Errorf("strtoull: %v", g)
	}
	if g := X_strtoui64(tls, issue57CString(tls, "077"), 0, 0); g != 63 {
		t.Errorf("_strtoui64 octal: %v", g)
	}
	if g := Xstrtoll(tls, issue57CString(tls, "zz"), end, 10); g != 0 || GoString(*(*uintptr)(unsafe.Pointer(end))) != "zz" {
		t.Errorf("strtoll with no digits: %v", g)
	}
}

func TestIssue57Handles(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	// Not windows.InvalidHandle: that is also the current process pseudo handle,
	// which CloseHandle accepts.
	if g := XCloseHandle(tls, 0); g != 0 {
		t.Errorf("CloseHandle of an invalid handle: %v, expected 0", g)
	}

	var r, w windows.Handle
	if err := windows.CreatePipe(&r, &w, nil, 0); err != nil {
		t.Fatal(err)
	}
	if g := XSetHandleInformation(tls, uintptr(r), windows.HANDLE_FLAG_INHERIT, windows.HANDLE_FLAG_INHERIT); g != 1 {
		t.Errorf("SetHandleInformation: %v", g)
	}
	var flags uint32
	if r0, _, err := modkernel32.NewProc("GetHandleInformation").Call(uintptr(r), uintptr(unsafe.Pointer(&flags))); r0 == 0 || flags&windows.HANDLE_FLAG_INHERIT == 0 {
		t.Errorf("GetHandleInformation: %v %#x", err, flags)
	}
	if g := XCloseHandle(tls, uintptr(r)); g != 1 {
		t.Errorf("CloseHandle: %v", g)
	}
	// GetOverlappedResult reports the status the OVERLAPPED records in its
	// Internal field, whatever the handle: TRUE for a completed request, FALSE
	// for a failed one.
	ov := Xcalloc(tls, 1, 64)
	defer Xfree(tls, ov)

	if g := XGetOverlappedResult(tls, uintptr(w), ov, ov+40, 0); g != 1 {
		t.Errorf("GetOverlappedResult of a completed request: %v", g)
	}
	(*windows.Overlapped)(unsafe.Pointer(ov)).Internal = uintptr(windows.STATUS_END_OF_FILE)
	if g := XGetOverlappedResult(tls, uintptr(w), ov, ov+40, 0); g != 0 {
		t.Errorf("GetOverlappedResult of a failed request: %v", g)
	}
	windows.CloseHandle(w)
	XOutputDebugStringW(tls, issue57WString(tls, "libc issue 57"))
}

func TestIssue57Files(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	dir := t.TempDir()
	// A path that a narrow conversion would mangle.
	path := filepath.Join(dir, "däté中.txt")
	if err := os.WriteFile(path, []byte("hello"), 0600); err != nil {
		t.Fatal(err)
	}
	wpath := issue57WString(tls, path)
	defer Xfree(tls, wpath)

	fd := X_wopen(tls, wpath, fcntl.O_RDONLY, 0)
	if fd <= 0 {
		t.Fatalf("_wopen: %v", fd)
	}
	buf := Xmalloc(tls, 64)
	defer Xfree(tls, buf)

	if g := Xread(tls, fd, buf, 5); g != 5 || GoString(buf) != "hello" {
		t.Fatalf("read: %v %q", g, GoString(buf))
	}

	// dup2 onto a fresh descriptor, read from it.
	if g := Xdup2(tls, fd, 1000); g != 1000 {
		t.Fatalf("dup2: %v", g)
	}
	Xlseek(tls, 1000, 0, 0)
	*(*byte)(unsafe.Pointer(buf)) = 0
	if g := Xread(tls, 1000, buf, 2); g != 2 || GoString(buf)[:2] != "he" {
		t.Fatalf("read from the dup: %v %q", g, GoString(buf))
	}
	Xclose(tls, 1000)
	Xclose(tls, fd)

	// feof and ungetc on a stream.
	cpath := issue57CString(tls, path)
	f := Xfopen(tls, cpath, issue57CString(tls, "rb"))
	if f == 0 {
		t.Fatalf("fopen: errno %v", *(*int32)(unsafe.Pointer(X__errno_location(tls))))
	}
	if g := Xfeof(tls, f); g != 0 {
		t.Fatalf("feof at the start: %v", g)
	}
	if g := Xfgetc(tls, f); g != 'h' {
		t.Fatalf("fgetc: %v", g)
	}
	if g := Xungetc(tls, 'H', f); g != 'H' {
		t.Fatalf("ungetc: %v", g)
	}
	if g := Xfread(tls, buf, 1, 64, f); g != 5 || string(unsafe.Slice((*byte)(unsafe.Pointer(buf)), 5)) != "Hello" {
		t.Fatalf("fread after ungetc: %v %q", g, string(unsafe.Slice((*byte)(unsafe.Pointer(buf)), 5)))
	}
	if g := Xfeof(tls, f); g == 0 {
		t.Fatal("feof after a short read: 0")
	}
	if g := Xfgetc(tls, f); g != -1 {
		t.Fatalf("fgetc at eof: %v", g)
	}
	Xclearerr(tls, f)
	if g := Xfeof(tls, f); g != 0 {
		t.Fatal("feof after clearerr")
	}
	Xrewind(tls, f)
	if g := Xfgetc(tls, f); g != 'h' {
		t.Fatalf("fgetc after rewind: %v", g)
	}
	Xfclose(tls, f)

	_ = utf16.Encode
}
