// Copyright 2024 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build linux && (amd64 || arm64 || loong64 || ppc64le || s390x || riscv64 || 386 || arm)

package libc // import "modernc.org/libc"

import (
	"golang.org/x/sys/unix"
	"runtime"
)

func translateSyscallAmd64(n, a1, a2, a3, a4, a5, a6 long) (sysno, p1, p2, p3, p4, p5, p6 long) {
	if runtime.GOARCH != "amd64" {
		return n, a1, a2, a3, a4, a5, a6
	}
	switch n {
	case 2: // SYS_open: open(path, flags, mode) -> openat(AT_FDCWD, path, flags, mode)
		return 257, long(unix.AT_FDCWD), a1, a2, a3, 0, 0
	case 4: // SYS_stat: stat(path, statbuf) -> newfstatat(AT_FDCWD, path, statbuf, 0)
		return 262, long(unix.AT_FDCWD), a1, a2, 0, 0, 0
	case 6: // SYS_lstat: lstat(path, statbuf) -> newfstatat(AT_FDCWD, path, statbuf, AT_SYMLINK_NOFOLLOW)
		return 262, long(unix.AT_FDCWD), a1, a2, long(unix.AT_SYMLINK_NOFOLLOW), 0, 0
	case 21: // SYS_access: access(path, mode) -> faccessat(AT_FDCWD, path, mode, 0)
		return 269, long(unix.AT_FDCWD), a1, a2, 0, 0, 0
	case 22: // SYS_pipe: pipe(pipefd) -> pipe2(pipefd, 0)
		return 293, a1, 0, 0, 0, 0, 0
	case 33: // SYS_dup2: dup2(oldfd, newfd) -> dup3(oldfd, newfd, 0)
		return 292, a1, a2, 0, 0, 0, 0
	case 82: // SYS_rename: rename(old, new) -> renameat(AT_FDCWD, old, AT_FDCWD, new)
		return 264, long(unix.AT_FDCWD), a1, long(unix.AT_FDCWD), a2, 0, 0
	case 83: // SYS_mkdir: mkdir(path, mode) -> mkdirat(AT_FDCWD, path, mode)
		return 258, long(unix.AT_FDCWD), a1, a2, 0, 0, 0
	case 84: // SYS_rmdir: rmdir(path) -> unlinkat(AT_FDCWD, path, AT_REMOVEDIR)
		return 263, long(unix.AT_FDCWD), a1, long(unix.AT_REMOVEDIR), 0, 0, 0
	case 86: // SYS_link: link(old, new) -> linkat(AT_FDCWD, old, AT_FDCWD, new, 0)
		return 265, long(unix.AT_FDCWD), a1, long(unix.AT_FDCWD), a2, 0, 0
	case 87: // SYS_unlink: unlink(path) -> unlinkat(AT_FDCWD, path, 0)
		return 263, long(unix.AT_FDCWD), a1, 0, 0, 0, 0
	case 88: // SYS_symlink: symlink(old, new) -> symlinkat(old, AT_FDCWD, new)
		return 266, a1, long(unix.AT_FDCWD), a2, 0, 0, 0
	case 89: // SYS_readlink: readlink(path, buf, size) -> readlinkat(AT_FDCWD, path, buf, size)
		return 267, long(unix.AT_FDCWD), a1, a2, a3, 0, 0
	case 90: // SYS_chmod: chmod(path, mode) -> fchmodat(AT_FDCWD, path, mode)
		return 268, long(unix.AT_FDCWD), a1, a2, 0, 0, 0
	case 92: // SYS_chown: chown(path, uid, gid) -> fchownat(AT_FDCWD, path, uid, gid, 0)
		return 260, long(unix.AT_FDCWD), a1, a2, a3, 0, 0
	case 133: // SYS_mknod: mknod(path, mode, dev) -> mknodat(AT_FDCWD, path, mode, dev)
		return 259, long(unix.AT_FDCWD), a1, a2, a3, 0, 0
	}
	return n, a1, a2, a3, a4, a5, a6
}

func ___syscall_cp(tls *TLS, n, a, b, c, d, e, f long) long {
	n, a, b, c, d, e, f = translateSyscallAmd64(n, a, b, c, d, e, f)
	switch n {
	case SYS_nanosleep:
		// int nanosleep(const struct timespec *req, struct timespec *rem)
		return sleepSyscall(tls, n, [6]long{a, b, c, d, e, f}, 0, 1, 0)
	case SYS_clock_nanosleep, sysClockNanosleepTime64:
		// int clock_nanosleep(clockid_t clk, int flags,
		//	const struct timespec *req, struct timespec *rem)
		return sleepSyscall(tls, n, [6]long{a, b, c, d, e, f}, 2, 3, b)
	}

	r1, _, err := (unix.Syscall6(uintptr(n), uintptr(a), uintptr(b), uintptr(c), uintptr(d), uintptr(e), uintptr(f)))
	if err != 0 {
		return long(-err)
	}

	return long(r1)
}

// sleepScratchSize is the size of the buffer sleepSyscall gives the kernel to
// report the time remaining in. It must be at least the size of the widest
// struct timespec the sleep syscalls use, which is the 64-bit one - two 64-bit
// words - also on 32-bit targets, where it is what the time64 variants take.
const sleepScratchSize = 16

// sleepSyscall issues one of the sleep syscalls and restarts it when a signal
// interrupts it.
//
// A program translated to Go by ccgo shares its process with the Go runtime,
// which signals threads with SIGURG to preempt goroutines asynchronously. The
// translated C code never installed that handler and cannot reasonably cope
// with the EINTR it causes: musl reports the interruption faithfully, but
// callers commonly discard the return value - SQLite's unixSleep() does - so
// the sleep silently comes up short and timing-dependent code misbehaves, e.g.
// a busy handler that gives up before its timeout. Restarting the sleep for the
// time remaining hides the runtime's own signals from the C program, which is
// also how the hand-written darwin, freebsd, netbsd and openbsd ports behave:
// their Xnanosleep sleeps in Go and cannot be interrupted at all.
//
// iReq and iRem are the indices in args of the req and rem arguments and flags
// is the clock_nanosleep(2) flags argument, zero for nanosleep(2).
func sleepSyscall(tls *TLS, n long, args [6]long, iReq, iRem int, flags long) long {
	if args[iRem] == 0 && flags&TIMER_ABSTIME == 0 && tls != nil {
		// The caller does not want the remaining time reported, but restarting
		// an interrupted sleep needs it. The kernel writes it in the same
		// layout it reads the request in, so it can be passed back as the next
		// request unchanged.
		p := tls.Alloc(sleepScratchSize)
		defer tls.Free(sleepScratchSize)

		args[iRem] = long(p)
	}
	for {
		r1, _, err := unix.Syscall6(uintptr(n), uintptr(args[0]), uintptr(args[1]), uintptr(args[2]), uintptr(args[3]), uintptr(args[4]), uintptr(args[5]))
		switch {
		case err == 0:
			return long(r1)
		case err != unix.EINTR:
			return long(-err)
		case flags&TIMER_ABSTIME != 0:
			// Waiting for an absolute deadline: the original request is still
			// the right one and the kernel reports no remaining time.
			continue
		case args[iRem] == 0:
			// Nowhere for the kernel to report the remaining time, so the sleep
			// cannot be restarted without overshooting. Report the
			// interruption.
			return long(-err)
		}
		args[iReq] = args[iRem]
	}
}

func X__syscall0(tls *TLS, n long) long {
	switch n {
	case __NR_sched_yield:
		runtime.Gosched()
		return 0
	default:
		r1, _, err := unix.Syscall(uintptr(n), 0, 0, 0)
		if err != 0 {
			return long(-err)
		}

		return long(r1)
	}
}

func X__syscall1(tls *TLS, n, a1 long) long {
	n, a1, _, _, _, _, _ = translateSyscallAmd64(n, a1, 0, 0, 0, 0, 0)
	r1, _, err := unix.Syscall(uintptr(n), uintptr(a1), 0, 0)
	if err != 0 {
		return long(-err)
	}

	return long(r1)
}

func X__syscall2(tls *TLS, n, a1, a2 long) long {
	n, a1, a2, _, _, _, _ = translateSyscallAmd64(n, a1, a2, 0, 0, 0, 0)
	switch n {
	case SYS_tkill:
		// raise() and pthread_kill(pthread_self()): the tid is the emulated
		// one, which the kernel would resolve to an unrelated thread.
		if int32(a1) == tls.tid() {
			return tls.raiseSignal(int32(a2))
		}

		if t := tlsByTid(int32(a1)); t != nil {
			return t.killSignal(int32(a2))
		}
	}
	r1, _, err := unix.Syscall(uintptr(n), uintptr(a1), uintptr(a2), 0)
	if err != 0 {
		return long(-err)
	}

	return long(r1)
}

func X__syscall3(tls *TLS, n, a1, a2, a3 long) long {
	n, a1, a2, a3, _, _, _ = translateSyscallAmd64(n, a1, a2, a3, 0, 0, 0)
	switch n {
	case SYS_tgkill:
		if int32(a2) == tls.tid() {
			return tls.raiseSignal(int32(a3))
		}

		if t := tlsByTid(int32(a2)); t != nil {
			return t.killSignal(int32(a3))
		}
	}
	r1, _, err := unix.Syscall(uintptr(n), uintptr(a1), uintptr(a2), uintptr(a3))
	if err != 0 {
		return long(-err)
	}

	return long(r1)
}

func X__syscall4(tls *TLS, n, a1, a2, a3, a4 long) long {
	n, a1, a2, a3, a4, _, _ = translateSyscallAmd64(n, a1, a2, a3, a4, 0, 0)
	r1, _, err := unix.Syscall6(uintptr(n), uintptr(a1), uintptr(a2), uintptr(a3), uintptr(a4), 0, 0)
	if err != 0 {
		return long(-err)
	}

	return long(r1)
}

func X__syscall5(tls *TLS, n, a1, a2, a3, a4, a5 long) long {
	n, a1, a2, a3, a4, a5, _ = translateSyscallAmd64(n, a1, a2, a3, a4, a5, 0)
	r1, _, err := unix.Syscall6(uintptr(n), uintptr(a1), uintptr(a2), uintptr(a3), uintptr(a4), uintptr(a5), 0)
	if err != 0 {
		return long(-err)
	}

	return long(r1)
}

func X__syscall6(tls *TLS, n, a1, a2, a3, a4, a5, a6 long) long {
	n, a1, a2, a3, a4, a5, a6 = translateSyscallAmd64(n, a1, a2, a3, a4, a5, a6)
	r1, _, err := unix.Syscall6(uintptr(n), uintptr(a1), uintptr(a2), uintptr(a3), uintptr(a4), uintptr(a5), uintptr(a6))
	if err != 0 {
		return long(-err)
	}

	return long(r1)
}
