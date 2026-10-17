// Copyright 2026 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build darwin

package libc // import "modernc.org/libc"

import (
	"os"
	"path/filepath"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
	"modernc.org/libc/errno"
	"modernc.org/libc/fcntl"
	"modernc.org/libc/netinet/in"
	"modernc.org/libc/signal"
	"modernc.org/libc/sys/types"
	"modernc.org/libc/unistd"
)

// https://gitlab.com/cznic/libc/-/issues/54

func issue54CString(t *TLS, s string) uintptr {
	p := Xmalloc(t, uint64(len(s)+1))
	copy(unsafe.Slice((*byte)(unsafe.Pointer(p)), len(s)+1), s+"\x00")
	return p
}

func issue54Errno(t *TLS) int32 {
	return *(*int32)(unsafe.Pointer(X__errno_location(t)))
}

func TestIssue54Signatures(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	if g := Xsched_yield(tls); g != 0 {
		t.Errorf("sched_yield: %v", g)
	}

	// wcschr returns a pointer, also for the terminator.
	w := Xmalloc(tls, 16)
	defer Xfree(tls, w)

	for i, c := range []wchar_t{'a', 'b', 'c', 0} {
		*(*wchar_t)(unsafe.Pointer(w + uintptr(i)*unsafe.Sizeof(wchar_t(0)))) = c
	}
	if g, e := Xwcschr(tls, w, 'b'), w+unsafe.Sizeof(wchar_t(0)); g != e {
		t.Errorf("wcschr: got %#x, expected %#x", g, e)
	}
	if g, e := Xwcschr(tls, w, 0), w+3*unsafe.Sizeof(wchar_t(0)); g != e {
		t.Errorf("wcschr(0): got %#x, expected %#x", g, e)
	}
	if g := Xwcschr(tls, w, 'z'); g != 0 {
		t.Errorf("wcschr(z): got %#x, expected 0", g)
	}
}

func TestIssue54Files(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	dir := t.TempDir()
	cdir := issue54CString(tls, dir)
	defer Xfree(tls, cdir)

	dfd := Xopen(tls, cdir, fcntl.O_RDONLY|fcntl.O_DIRECTORY, 0)
	if dfd < 0 {
		t.Fatalf("open dir: errno %v", issue54Errno(tls))
	}

	defer Xclose(tls, dfd)

	name := issue54CString(tls, "f")
	defer Xfree(tls, name)

	va := Xmalloc(tls, 32)
	defer Xfree(tls, va)

	fd := Xopenat(tls, dfd, name, fcntl.O_RDWR|fcntl.O_CREAT, VaList(va, int32(0600)))
	if fd < 0 {
		t.Fatalf("openat: errno %v", issue54Errno(tls))
	}
	if g := Xwrite(tls, fd, cdir, 5); g != 5 {
		t.Fatalf("write: %v", g)
	}
	Xclose(tls, fd)

	st := Xmalloc(tls, uint64(unsafe.Sizeof(unix.Stat_t{})))
	defer Xfree(tls, st)

	if g := Xfstatat(tls, dfd, name, st, 0); g != 0 {
		t.Fatalf("fstatat: errno %v", issue54Errno(tls))
	}
	if g := (*unix.Stat_t)(unsafe.Pointer(st)).Size; g != 5 {
		t.Fatalf("fstatat size %v", g)
	}

	// truncate through the path, then check with stat.
	cpath := issue54CString(tls, filepath.Join(dir, "f"))
	defer Xfree(tls, cpath)

	if g := Xtruncate(tls, cpath, 2); g != 0 {
		t.Fatalf("truncate: errno %v", issue54Errno(tls))
	}
	if g := Xfstatat(tls, dfd, name, st, 0); g != 0 || (*unix.Stat_t)(unsafe.Pointer(st)).Size != 2 {
		t.Fatalf("fstatat after truncate: %v size %v", g, (*unix.Stat_t)(unsafe.Pointer(st)).Size)
	}

	sub := issue54CString(tls, "sub")
	defer Xfree(tls, sub)

	if g := Xmkdirat(tls, dfd, sub, 0700); g != 0 {
		t.Fatalf("mkdirat: errno %v", issue54Errno(tls))
	}
	if g := Xfaccessat(tls, dfd, sub, unistd.X_OK, 0); g != 0 {
		t.Fatalf("faccessat: errno %v", issue54Errno(tls))
	}
	if g := Xfchmodat(tls, dfd, sub, 0500, 0); g != 0 {
		t.Fatalf("fchmodat: errno %v", issue54Errno(tls))
	}
	if g := Xfchownat(tls, dfd, sub, types.Uid_t(unix.Getuid()), types.Gid_t(unix.Getgid()), 0); g != 0 {
		t.Fatalf("fchownat: errno %v", issue54Errno(tls))
	}

	link := issue54CString(tls, "l")
	defer Xfree(tls, link)

	if g := Xsymlinkat(tls, name, dfd, link); g != 0 {
		t.Fatalf("symlinkat: errno %v", issue54Errno(tls))
	}
	buf := Xmalloc(tls, 64)
	defer Xfree(tls, buf)

	if g := Xreadlinkat(tls, dfd, link, buf, 64); g != 1 || *(*byte)(unsafe.Pointer(buf)) != 'f' {
		t.Fatalf("readlinkat: %v %q", g, GoString(buf))
	}
	if g := Xlchmod(tls, issue54CString(tls, filepath.Join(dir, "l")), 0600); g != 0 {
		t.Fatalf("lchmod: errno %v", issue54Errno(tls))
	}

	hard := issue54CString(tls, "h")
	defer Xfree(tls, hard)

	if g := Xlinkat(tls, dfd, name, dfd, hard, 0); g != 0 {
		t.Fatalf("linkat: errno %v", issue54Errno(tls))
	}
	if g := Xfstatat(tls, dfd, hard, st, 0); g != 0 || (*unix.Stat_t)(unsafe.Pointer(st)).Nlink != 2 {
		t.Fatalf("fstatat of the hard link: %v nlink %v", g, (*unix.Stat_t)(unsafe.Pointer(st)).Nlink)
	}
	renamed := issue54CString(tls, "r")
	defer Xfree(tls, renamed)

	if g := Xrenameat(tls, dfd, hard, dfd, renamed); g != 0 {
		t.Fatalf("renameat: errno %v", issue54Errno(tls))
	}

	// utimensat and futimens set the mtime.
	ts := Xmalloc(tls, uint64(2*unsafe.Sizeof(unix.Timespec{})))
	defer Xfree(tls, ts)

	*(*[2]unix.Timespec)(unsafe.Pointer(ts)) = [2]unix.Timespec{{Sec: 1000000, Nsec: 0}, {Sec: 2000000, Nsec: 0}}
	if g := Xutimensat(tls, dfd, name, ts, 0); g != 0 {
		t.Fatalf("utimensat: errno %v", issue54Errno(tls))
	}
	if g := Xfstatat(tls, dfd, name, st, 0); g != 0 || (*unix.Stat_t)(unsafe.Pointer(st)).Mtim.Sec != 2000000 {
		t.Fatalf("fstatat after utimensat: %v mtime %v", g, (*unix.Stat_t)(unsafe.Pointer(st)).Mtim.Sec)
	}
	fd = Xopenat(tls, dfd, name, fcntl.O_RDWR, 0)
	if fd < 0 {
		t.Fatalf("openat: errno %v", issue54Errno(tls))
	}
	(*[2]unix.Timespec)(unsafe.Pointer(ts))[1].Sec = 3000000
	if g := Xfutimens(tls, fd, ts); g != 0 {
		t.Fatalf("futimens: errno %v", issue54Errno(tls))
	}
	if g := Xfstat(tls, fd, st); g != 0 || (*unix.Stat_t)(unsafe.Pointer(st)).Mtim.Sec != 3000000 {
		t.Fatalf("fstat after futimens: %v mtime %v", g, (*unix.Stat_t)(unsafe.Pointer(st)).Mtim.Sec)
	}

	// dup2 and readv.
	if g := Xdup2(tls, fd, fd+10); g != fd+10 {
		t.Fatalf("dup2: %v errno %v", g, issue54Errno(tls))
	}
	Xlseek(tls, fd+10, 0, unistd.SEEK_SET)
	iov := Xmalloc(tls, 32)
	defer Xfree(tls, iov)

	*(*[2]struct {
		base uintptr
		len  types.Size_t
	})(unsafe.Pointer(iov)) = [2]struct {
		base uintptr
		len  types.Size_t
	}{{buf, 1}, {buf + 1, 8}}
	if g := Xreadv(tls, fd+10, iov, 2); g != 2 {
		t.Fatalf("readv: %v errno %v", g, issue54Errno(tls))
	}
	Xclose(tls, fd+10)
	Xclose(tls, fd)

	// fdopendir lists the directory.
	dfd2 := Xopen(tls, cdir, fcntl.O_RDONLY|fcntl.O_DIRECTORY, 0)
	d := Xfdopendir(tls, dfd2)
	if d == 0 {
		t.Fatal("fdopendir failed")
	}
	names := map[string]bool{}
	for {
		e := Xreaddir(tls, d)
		if e == 0 {
			break
		}

		names[GoString(e+unsafe.Offsetof(unix.Dirent{}.Name))] = true
	}
	Xclosedir(tls, d)
	for _, v := range []string{"f", "sub", "l", "r"} {
		if !names[v] {
			t.Errorf("readdir did not list %q: %v", v, names)
		}
	}

	if g := Xunlinkat(tls, dfd, sub, unix.AT_REMOVEDIR); g != 0 {
		t.Fatalf("unlinkat: errno %v", issue54Errno(tls))
	}
	if g := Xunlinkat(tls, dfd, renamed, 0); g != 0 {
		t.Fatalf("unlinkat: errno %v", issue54Errno(tls))
	}

	// chown to ourselves and mknod of a fifo.
	if g := Xchown(tls, cpath, types.Uid_t(unix.Getuid()), types.Gid_t(unix.Getgid())); g != 0 {
		t.Fatalf("chown: errno %v", issue54Errno(tls))
	}
	fifo := issue54CString(tls, filepath.Join(dir, "fifo"))
	defer Xfree(tls, fifo)

	if g := Xmknod(tls, fifo, unix.S_IFIFO|0600, 0); g != 0 {
		t.Fatalf("mknod: errno %v", issue54Errno(tls))
	}
	if g := Xlink(tls, cpath, issue54CString(tls, filepath.Join(dir, "f2"))); g != 0 {
		t.Fatalf("link: errno %v", issue54Errno(tls))
	}
}

func TestIssue54Misc(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	p := Xmalloc(tls, 256)
	defer Xfree(tls, p)

	if g := Xgetgid(tls); g != types.Gid_t(unix.Getgid()) {
		t.Errorf("getgid: %v", g)
	}
	if g := Xgetegid(tls); g != types.Gid_t(unix.Getegid()) {
		t.Errorf("getegid: %v", g)
	}
	if g := Xissetugid(tls); g != 0 {
		t.Errorf("issetugid: %v", g)
	}
	if g := Xgetrusage(tls, 0, p); g != 0 {
		t.Errorf("getrusage: errno %v", issue54Errno(tls))
	}
	if g := Xgettimeofday(tls, p, p+16); g != 0 || (*unix.Timeval)(unsafe.Pointer(p)).Sec == 0 {
		t.Errorf("gettimeofday with a timezone: %v", g)
	}
	if g := Xsysconf(tls, unistd.X_SC_CLK_TCK); g != 100 {
		t.Errorf("sysconf(_SC_CLK_TCK): %v", g)
	}
	if g := Xsysconf(tls, unistd.X_SC_OPEN_MAX); g < 256 {
		t.Errorf("sysconf(_SC_OPEN_MAX): %v", g)
	}
	if g := Xsysconf(tls, 99999); g != -1 || issue54Errno(tls) != errno.EINVAL {
		t.Errorf("sysconf(bogus): %v errno %v", g, issue54Errno(tls))
	}
	if g := Xpathconf(tls, issue54CString(tls, "/"), unistd.X_PC_PATH_MAX); g != 1024 {
		t.Errorf("pathconf(_PC_PATH_MAX): %v", g)
	}
	if g := Xfpathconf(tls, 0, unistd.X_PC_NAME_MAX); g != 255 {
		t.Errorf("fpathconf(_PC_NAME_MAX): %v", g)
	}
	if g := Xconfstr(tls, unistd.X_CS_PATH, p, 256); g == 0 || GoString(p) != "/usr/bin:/bin:/usr/sbin:/sbin" {
		t.Errorf("confstr: %v %q", g, GoString(p))
	}
	if g := Xconfstr(tls, unistd.X_CS_PATH, p, 4); g != 30 || GoString(p) != "/us" {
		t.Errorf("confstr with a short buffer: %v %q", g, GoString(p))
	}
	if g := Xkill(tls, types.Pid_t(os.Getpid()), 0); g != 0 {
		t.Errorf("kill(self, 0): errno %v", issue54Errno(tls))
	}
	start := time.Now()
	if g := Xpoll(tls, 0, 0, 20); g != 0 || time.Since(start) < 15*time.Millisecond {
		t.Errorf("poll with no descriptors: %v after %v", g, time.Since(start))
	}

	var ia in.In_addr
	*(*[4]byte)(unsafe.Pointer(&ia)) = [4]byte{127, 0, 0, 1}
	if g := GoString(Xinet_ntoa(tls, ia)); g != "127.0.0.1" {
		t.Errorf("inet_ntoa: %q", g)
	}

	if g := Xgetloadavg(tls, p, 3); g != 3 || *(*float64)(unsafe.Pointer(p)) < 0 {
		t.Errorf("getloadavg: %v %v", g, *(*float64)(unsafe.Pointer(p)))
	}
	if g := Xgetlogin_r(tls, p, 256); g != 0 || GoString(p) == "" {
		t.Errorf("getlogin_r: %v %q", g, GoString(p))
	}

	// Thread names.
	if g := Xpthread_setname_np(tls, issue54CString(tls, "worker")); g != 0 {
		t.Errorf("pthread_setname_np: %v", g)
	}
	if g := Xpthread_getname_np(tls, Xpthread_self(tls), p, 64); g != 0 || GoString(p) != "worker" {
		t.Errorf("pthread_getname_np: %v %q", g, GoString(p))
	}
}

func TestIssue54Pty(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	p := Xmalloc(tls, 256)
	defer Xfree(tls, p)

	m := Xposix_openpt(tls, fcntl.O_RDWR|fcntl.O_NOCTTY)
	if m < 0 {
		t.Fatalf("posix_openpt: errno %v", issue54Errno(tls))
	}
	if g := Xgrantpt(tls, m); g != 0 {
		t.Fatalf("grantpt: errno %v", issue54Errno(tls))
	}
	if g := Xunlockpt(tls, m); g != 0 {
		t.Fatalf("unlockpt: errno %v", issue54Errno(tls))
	}
	if g := Xptsname_r(tls, m, p, 256); g != 0 || GoString(p) == "" {
		t.Fatalf("ptsname_r: %v %q", g, GoString(p))
	}
	if g := GoString(Xptsname(tls, m)); g != GoString(p) {
		t.Fatalf("ptsname: %q", g)
	}
	if g := Xptsname_r(tls, m, p, 3); g != errno.ERANGE {
		t.Fatalf("ptsname_r with a short buffer: %v", g)
	}
	Xclose(tls, m)

	// openpty: what is written to the master can be read from the slave.
	master, slave, name := p, p+4, p+8
	if g := Xopenpty(tls, master, slave, name, 0, 0); g != 0 {
		t.Fatalf("openpty: errno %v", issue54Errno(tls))
	}
	if GoString(name) == "" {
		t.Fatal("openpty gave no name")
	}
	mfd, sfd := *(*int32)(unsafe.Pointer(master)), *(*int32)(unsafe.Pointer(slave))
	if g := Xwrite(tls, mfd, issue54CString(tls, "x\n"), 2); g != 2 {
		t.Fatalf("write to the master: %v", g)
	}
	buf := Xmalloc(tls, 16)
	defer Xfree(tls, buf)

	if g := Xread(tls, sfd, buf, 16); g < 1 || *(*byte)(unsafe.Pointer(buf)) != 'x' {
		t.Fatalf("read from the slave: %v", g)
	}
	Xclose(tls, sfd)
	Xclose(tls, mfd)
}

func TestIssue54Kqueue(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	kq := Xkqueue(tls)
	if kq < 0 {
		t.Fatalf("kqueue: errno %v", issue54Errno(tls))
	}

	defer Xclose(tls, kq)

	fds := Xmalloc(tls, 8)
	defer Xfree(tls, fds)

	if g := Xpipe(tls, fds); g != 0 {
		t.Fatalf("pipe: %v", g)
	}
	r, w := *(*int32)(unsafe.Pointer(fds)), *(*int32)(unsafe.Pointer(fds + 4))
	ev := Xmalloc(tls, uint64(2*unsafe.Sizeof(unix.Kevent_t{})))
	defer Xfree(tls, ev)

	*(*unix.Kevent_t)(unsafe.Pointer(ev)) = unix.Kevent_t{Ident: uint64(r), Filter: unix.EVFILT_READ, Flags: unix.EV_ADD}
	if g := Xkevent(tls, kq, ev, 1, 0, 0, 0); g != 0 {
		t.Fatalf("kevent register: %v errno %v", g, issue54Errno(tls))
	}
	ts := Xmalloc(tls, uint64(unsafe.Sizeof(unix.Timespec{})))
	defer Xfree(tls, ts)

	*(*unix.Timespec)(unsafe.Pointer(ts)) = unix.Timespec{}
	if g := Xkevent(tls, kq, 0, 0, ev, 1, ts); g != 0 {
		t.Fatalf("kevent on an empty pipe: %v", g)
	}
	Xwrite(tls, w, fds, 1)
	*(*unix.Timespec)(unsafe.Pointer(ts)) = unix.Timespec{Sec: 5}
	if g := Xkevent(tls, kq, 0, 0, ev, 1, ts); g != 1 || (*unix.Kevent_t)(unsafe.Pointer(ev)).Ident != uint64(r) {
		t.Fatalf("kevent after a write: %v", g)
	}
	Xclose(tls, r)
	Xclose(tls, w)
}

func TestIssue54Raise(t *testing.T) {
	tls := NewTLS()
	defer tls.Close()

	issue54Raised = 0
	// Handlers are function values on this port, see startSignalHandler.
	f := issue54Handler
	handler := *(*uintptr)(unsafe.Pointer(&f))
	Xsignal(tls, int32(unix.SIGUSR1), handler)
	if g := Xraise(tls, int32(unix.SIGUSR1)); g != 0 {
		t.Fatalf("raise: %v", g)
	}
	for deadline := time.Now().Add(10 * time.Second); issue54Raised == 0 && time.Now().Before(deadline); {
		time.Sleep(time.Millisecond)
	}
	Xsignal(tls, int32(unix.SIGUSR1), signal.SIG_DFL)
	if issue54Raised != int32(unix.SIGUSR1) {
		t.Fatalf("handler got %v", issue54Raised)
	}
}

var issue54Raised int32

func issue54Handler(tls *TLS, sig int32) {
	issue54Raised = sig
}
