// Copyright 2024 The Libc Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build ignore
// +build ignore

package main

// https://musl.libc.org/releases.html

// https://posixtest.sourceforge.net/

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"modernc.org/cc/v4"
	ccgo "modernc.org/ccgo/v4/lib"
	util "modernc.org/fileutil/ccgo"
	"modernc.org/libc/internal/archive"
)

var (
	extractedArchivePath string
	goarch               = or(os.Getenv("GO_GENERATE_GOARCH"), runtime.GOARCH)
	goos                 = runtime.GOOS
	j                    = fmt.Sprint(runtime.GOMAXPROCS(-1))
	muslArch             string
	target               = fmt.Sprintf("%s/%s", goos, goarch)
)

func fail(rc int, msg string, args ...any) {
	fmt.Fprintln(os.Stderr, strings.TrimSpace(fmt.Sprintf(msg, args...)))
	os.Exit(rc)
}

func or(s ...string) string {
	for _, v := range s {
		if v != "" {
			return v
		}
	}
	return ""
}

func main() {
	if goos != "linux" {
		fail(1, "invalid GOOS, expected linux: %s", goos)
	}

	if ccgo.IsExecEnv() {
		if err := ccgo.NewTask(goos, goarch, os.Args, os.Stdout, os.Stderr, nil).Main(); err != nil {
			fmt.Fprintln(os.Stderr, err)
		}
		return
	}

	f, err := os.Open(archive.File)
	if err != nil {
		fail(1, "cannot open archive file: %v\n", err)
	}

	f.Close()

	switch goarch {
	case "386":
		muslArch = "i386"
	case "amd64":
		muslArch = "x86_64"
	case "arm":
		muslArch = "arm"
	case "arm64":
		muslArch = "aarch64"
	case "loong64":
		muslArch = "loongarch64"
	case "ppc64le":
		muslArch = "powerpc64"
	case "riscv64":
		muslArch = "riscv64"
	case "s390x":
		muslArch = "s390x"
	default:
		fail(1, "unsupported goarch: %s", goarch)
	}

	extractedArchivePath = archive.Version
	tempDir := os.Getenv("GO_GENERATE_DIR")
	dev := os.Getenv("GO_GENERATE_DEV") != ""
	switch {
	case tempDir != "":
		util.MustShell(true, nil, "sh", "-c", fmt.Sprintf("rm -rf %s", filepath.Join(tempDir, extractedArchivePath)))
	default:
		var err error
		if tempDir, err = os.MkdirTemp("", "libc-generate"); err != nil {
			fail(1, "creating temp dir: %v\n", err)
		}

		defer func() {
			switch os.Getenv("GO_GENERATE_KEEP") {
			case "":
				os.RemoveAll(tempDir)
			default:
				fmt.Printf("%s: temporary directory kept\n", tempDir)
			}
		}()
	}
	libRoot := filepath.Join(tempDir, extractedArchivePath)
	makeRoot := libRoot
	fmt.Fprintf(os.Stderr, "archive %s\n", archive.Version)
	fmt.Fprintf(os.Stderr, "extractedArchivePath %s\n", extractedArchivePath)
	fmt.Fprintf(os.Stderr, "tempDir %s\n", tempDir)
	fmt.Fprintf(os.Stderr, "libRoot %s\n", libRoot)
	fmt.Fprintf(os.Stderr, "makeRoot %s\n", makeRoot)

	util.MustShell(true, nil, "tar", "xfz", archive.File, "-C", tempDir)

	util.Shell(nil, "find", filepath.Join(libRoot), "-name", "*.s", "-delete")

	util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -rf %s", filepath.Join(libRoot, "ldso/*")))
	util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -rf %s", filepath.Join(libRoot, "src", "aio/*")))
	util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -rf %s", filepath.Join(libRoot, "src", "ldso/*")))
	util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -rf %s", filepath.Join(libRoot, "src", "malloc/*")))
	util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -rf %s", filepath.Join(libRoot, "src", "math", muslArch+"/*")))
	util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -rf %s", filepath.Join(libRoot, "src", "mq/*")))
	// pthreads are implemented in pthread_musl.go and sched_yield in
	// libc_musl.go. Keep only what translates on top of the Go atomics and the
	// Go __timedwait_cp/__wake, see https://gitlab.com/cznic/libc/-/issues/55.
	// sched_getcpu.c uses the vDSO.
	keepOnly(filepath.Join(libRoot, "src", "sched"),
		"affinity.c",
		"sched_cpucount.c",
		"sched_get_priority_max.c",
		"sched_getparam.c",
		"sched_getscheduler.c",
		"sched_rr_get_interval.c",
		"sched_setparam.c",
		"sched_setscheduler.c",
	)
	keepOnly(filepath.Join(libRoot, "src", "thread"),
		"pthread_kill.c",
		"pthread_testcancel.c",
		"sem_destroy.c",
		"sem_getvalue.c",
		"sem_init.c",
		"sem_open.c",
		"sem_post.c",
		"sem_timedwait.c",
		"sem_trywait.c",
		"sem_unlink.c",
		"sem_wait.c",
	)
	util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -rf %s", filepath.Join(libRoot, "src", "string", "aarch64")))

	switch target {
	case "linux/386":
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "aio_suspend_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "cnd_timedwait_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "mq_timedreceive_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "mq_timedsend_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "mtx_timedlock_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "pthread_cond_timedwait_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "pthread_mutex_timedlock_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "pthread_rwlock_timedrdlock_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "pthread_rwlock_timedwrlock_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "pthread_timedjoin_np_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "sched_rr_get_interval_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "sem_timedwait_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "thrd_sleep_time32.c")))
	case "linux/arm":
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "aio_suspend_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "cnd_timedwait_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "mq_timedreceive_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "mq_timedsend_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "mtx_timedlock_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "pthread_cond_timedwait_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "pthread_mutex_timedlock_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "pthread_rwlock_timedrdlock_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "pthread_rwlock_timedwrlock_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "pthread_timedjoin_np_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "sched_rr_get_interval_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "sem_timedwait_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "compat", "time32", "thrd_sleep_time32.c")))
		util.Shell(nil, "sh", "-c", fmt.Sprintf("rm -f %s", filepath.Join(libRoot, "src", "exit", "arm", "__aeabi_atexit.c")))
	}

	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "env", "__init_tls.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "env", "__libc_start_main.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "errno", "__errno_location.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "exit", "abort.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "exit", "atexit.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "exit", "exit.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "fenv", "__flt_rounds.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "fenv", "fegetexceptflag.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "fenv", "feholdexcept.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "fenv", "fesetexceptflag.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "fenv", "fesetround.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "fenv", "feupdateenv.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "legacy", "daemon.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "legacy", "valloc.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "linux", "clone.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "linux", "gettid.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "linux", "membarrier.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "linux", "setgroups.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "math", "fmaf.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "math", "nearbyint.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "math", "nearbyintf.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "math", "nearbyintl.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "misc", "forkpty.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "misc", "initgroups.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "misc", "wordexp.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "network", "getservbyport.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "network", "getservbyport_r.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "network", "res_query.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "network", "res_querydomain.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "passwd", "fgetspent.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "passwd", "getspnam.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "passwd", "getspnam_r.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "process", "_Fork.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "process", "posix_spawn.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "process", "posix_spawnp.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "process", "system.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "signal", "sighold.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "signal", "sigignore.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "signal", "siginterrupt.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "signal", "siglongjmp.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "signal", "signal.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "signal", "sigpause.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "signal", "sigrelse.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "signal", "sigset.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "stdio", "__lockfile.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "stdio", "popen.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "temp", "__randname.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "time", "timer_create.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "unistd", "setegid.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "unistd", "seteuid.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "unistd", "setregid.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "unistd", "setresgid.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "unistd", "setresuid.c"))
	util.Shell(nil, "rm", filepath.Join(libRoot, "src", "unistd", "setreuid.c"))

	util.Shell(nil, "mkdir", filepath.Join(libRoot, "src", "malloc", "mallocng"))

	util.MustCopyFile(true, "COPYRIGHT-MUSL", filepath.Join(makeRoot, "COPYRIGHT"), nil)
	util.MustInDir(true, makeRoot, func() (err error) {
		cflags := []string{
			"-DNDEBUG",
		}
		switch target {
		case "linux/ppc64le":
			if s := cc.LongDouble64Flag(goos, goarch); s != "" {
				cflags = append(cflags, s)
			}
		}
		util.MustShell(true, nil, "sh", "-c", fmt.Sprintf("CFLAGS='%s' ./configure "+
			"--disable-static "+
			"--disable-optimize "+
			"",
			strings.Join(cflags, " "),
		))
		return nil
	})
	util.MustCopyDir(true, libRoot, filepath.Join("internal", "overlay", "musl"), nil)
	util.CopyDir(libRoot, filepath.Join("internal", "overlay", goos, goarch, "musl"), nil)
	util.MustInDir(true, makeRoot, func() (err error) {
		args := []string{
			os.Args[0],

			"--package-name=libc",
			"--prefix-enumerator=_",
			"--prefix-external=x_",
			"--prefix-field=F",
			"--prefix-static-internal=_",
			"--prefix-static-none=_",
			"--prefix-tagged-enum=_",
			"--prefix-tagged-struct=T",
			"--prefix-tagged-union=T",
			"--prefix-typename=T",
			"--prefix-undefined=_",
			"-emit-func-aliases",
			"-eval-all-macros",
			"-extended-errors",
			"-ignore-asm-errors",
			"-ignore-unsupported-alignment",
			"-isystem", "",
			"-hide", "strlen", // https://gitlab.com/cznic/sqlite/-/merge_requests/88
			"-hide", "__libc_sigaction", // implemented in libc_musl.go, https://gitlab.com/cznic/libc/-/issues/53
		}
		switch target {
		case "linux/s390x":
			args = append(args, "-hide", "__mmap")
		}
		if s := cc.LongDouble64Flag(goos, goarch); s != "" {
			args = append(args, s)
		}
		if dev {
			args = append(
				args,
				"-absolute-paths",
				"-keep-object-files",
				"-positions",
			)
		}
		return ccgo.NewTask(goos, goarch, append(args, "-exec", "make", "-j", j, "lib/libc.so"), os.Stdout, os.Stderr, nil).Exec()
	})

	os.RemoveAll(filepath.Join("include", goos, goarch))
	util.MustCopyDir(true, filepath.Join("include", goos, goarch), filepath.Join(tempDir, extractedArchivePath, "include"), nil)
	util.MustCopyDir(true, filepath.Join("include", goos, goarch, "bits"), filepath.Join(tempDir, extractedArchivePath, "obj", "include", "bits"), nil)
	util.MustCopyDir(true, filepath.Join("include", goos, goarch, "bits"), filepath.Join(tempDir, extractedArchivePath, "arch", "generic", "bits"), nil)
	util.MustCopyDir(true, filepath.Join("include", goos, goarch, "bits"), filepath.Join(tempDir, extractedArchivePath, "arch", muslArch, "bits"), nil)

	fn := fmt.Sprintf("ccgo_%s_%s.go", goos, goarch)
	util.MustShell(true, nil, "cp", filepath.Join(makeRoot, "lib", "libc.so.go"), fn)

	util.MustShell(true, nil, "sed", "-i", `s/\<T__\([a-zA-Z0-9][a-zA-Z0-9_]\+\)/t__\1/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<____errno_location\>/X__errno_location/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<___fstatfs\>/Xfstatfs/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<___libc_calloc\>/Xcalloc/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<___libc_free\>/Xfree/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<___libc_malloc\>/Xmalloc/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<___syscall\([0-6]\)\>/X__syscall\1/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<_abort\>/Xabort/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<_calloc\>/Xcalloc/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<_free\>/Xfree/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<_malloc\>/Xmalloc/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<_realloc\>/Xrealloc/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<x_\([a-zA-Z0-9_][a-zA-Z0-9_]\+\)/X\1/g`, fn)

	util.MustShell(true, nil, "sed", "-i", `s/\<X__daylight\>/Xdaylight/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<X__environ\>/Xenviron/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<X__optreset\>/Xoptreset/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<X__progname\>/Xprogram_invocation_short_name/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<X__progname_full\>/Xprogram_invocation_name/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<X__signgam\>/Xsigngam/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<X__timezone\>/Xtimezone/g`, fn)
	util.MustShell(true, nil, "sed", "-i", `s/\<X__tzname\>/Xtzname/g`, fn)

	m, err := filepath.Glob(fmt.Sprintf("*_%s_%s.go", runtime.GOOS, runtime.GOARCH))
	if err != nil {
		fail(1, "%s\n", err)
	}

	format := false
	for _, fn := range m {
		b, err := os.ReadFile(fn)
		if err != nil {
			fail(1, "%s\n", err)
		}

		a := strings.Split(string(b), "\n")
		w := false
		for i, v := range a {
			if strings.HasPrefix(v, "func X") {
				if i+1 < len(a) && !strings.Contains(a[i+1], "__ccgo_strace") {
					a[i] += "\n\t" + traceLine(v)
					w = true
					format = true
				}
			}
		}
		if w {
			if err := os.WriteFile(fn, []byte(strings.Join(a, "\n")), 0660); err != nil {
				fail(1, "%s\n", err)
			}
		}
	}

	// native_musl.go implements these hot routines natively on every musl
	// target. Rename the transpiled definitions out of the way after adding
	// tracing; call sites keep the X names and so reach the native code, and
	// native_musl_test.go checks the native routines against the renamed
	// originals. (On linux/arm musl's memcpy is assembly and never
	// transpiled; the hand-written one in libc_musl_linux_arm.go carries the
	// ___musl_memcpy name itself, so that sed is a no-op there.)
	for _, name := range []string{"fabs", "memcmp", "memcpy", "memmove", "memset", "qsort", "strcspn"} {
		util.MustShell(true, nil, "sed", "-i", fmt.Sprintf(`s/^func X%s(/func ___musl_%s(/`, name, name), fn)
	}

	// The environment functions are renamed the same way and wrapped, in the
	// generated file so that every arch stays consistent until regenerated,
	// by versions that mirror the change to the Go environment. See
	// https://gitlab.com/cznic/libc/-/issues/58.
	for _, name := range []string{"clearenv", "putenv", "setenv", "unsetenv"} {
		util.MustShell(true, nil, "sed", "-i", fmt.Sprintf(`s/^func X%s(/func ___musl_%s(/`, name, name), fn)
	}
	envOut, err := os.OpenFile(fn, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		fail(1, "%s\n", err)
	}

	if _, err := envOut.WriteString(envWrappers); err != nil {
		fail(1, "%s\n", err)
	}

	if err := envOut.Close(); err != nil {
		fail(1, "%s\n", err)
	}

	//TODO use qbecc/lib
	// switch target {
	// case "linux/amd64":
	// 	util.MustShell(true, nil, "go", "run", "genasm.go")
	// }
	if format {
		util.MustShell(true, nil, "sh", "-c", "gofmt -w *.go")
	}
	util.MustShell(true, nil, "go", "test", "-run", "@")
}

// func Xaio_fsync(tls *TLS, op int32, cb uintptr) (r int32) {
func traceLine(s string) string {
	var b strings.Builder
	parts := strings.Split(s, "(")
	for i, v := range parts {
		switch i {
		case 0:
			// "func Xaio_fsync"
		case 1:
			// "tls *TLS, op int32, cb uintptr) "
			a := strings.Split(v, ",")
			b.WriteString(`if __ccgo_strace { trc("`)
			var vals []string
			for j, w := range a {
				w = strings.TrimSpace(w)
				if x := strings.Index(w, " "); x > 0 {
					w := w[:x]
					if j != 0 {
						b.WriteString(" ")
					}
					fmt.Fprintf(&b, "%s=%%v", w)
					vals = append(vals, w)
				}
			}
			fmt.Fprintf(&b, `, (%%v:)", %s, origin(2))`, strings.Join(vals, ", "))
		case 2:
			// "r int32) {"
			r := v[:strings.Index(v, " ")]
			fmt.Fprintf(&b, `; defer func() { trc("-> %%v", %s)}()`, r)
		}
	}
	b.WriteString("}")
	return b.String()
}

// keepOnly removes everything in dir except the named entries.
func keepOnly(dir string, keep ...string) {
	m := map[string]bool{}
	for _, v := range keep {
		m[v] = true
	}
	ents, err := os.ReadDir(dir)
	if err != nil {
		util.Fatalf(true, "%v", err)
	}

	for _, e := range ents {
		if !m[e.Name()] {
			util.Shell(nil, "rm", "-rf", filepath.Join(dir, e.Name()))
		}
	}
}

// envWrappers is appended to the generated file. The transpiled musl
// functions keep the C environment; these keep the Go environment in step so
// that os.Getenv and friends agree with getenv. The helpers are in
// libc_musl.go.
const envWrappers = `
// The environment functions below wrap the transpiled musl versions, renamed
// to ___musl_*, and mirror successful changes to the Go environment. See
// https://gitlab.com/cznic/libc/-/issues/58.

func Xsetenv(tls *TLS, var1 uintptr, value uintptr, overwrite int32) (r int32) {
	if r = ___musl_setenv(tls, var1, value, overwrite); r == 0 {
		envMirrorSet(GoString(var1), GoString(Xgetenv(tls, var1)))
	}
	return r
}

func Xunsetenv(tls *TLS, name uintptr) (r int32) {
	if r = ___musl_unsetenv(tls, name); r == 0 {
		envMirrorUnset(GoString(name))
	}
	return r
}

func Xputenv(tls *TLS, s uintptr) (r int32) {
	if r = ___musl_putenv(tls, s); r == 0 {
		envMirrorPut(GoString(s))
	}
	return r
}

func Xclearenv(tls *TLS) (r int32) {
	if r = ___musl_clearenv(tls); r == 0 {
		envMirrorClear()
	}
	return r
}
`
