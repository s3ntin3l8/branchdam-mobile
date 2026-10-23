//go:build linux || darwin

package branchdam

import "syscall"

// openFlagsNonblock makes opening a FIFO return immediately instead of
// waiting for a writer to open the other end.
//
// The path handed to ComputeHashes crosses the FFI boundary from the
// native shell, so it is not necessarily a regular file. Opening a FIFO
// read-only blocks until some process opens the write end, so without
// this flag a path that is -- or that becomes -- a FIFO would pin the
// caller's query thread forever. The flag is a no-op for regular files.
//
// The android build tag implies linux and the ios build tag implies
// darwin, so this file covers both mobile targets.
const openFlagsNonblock = syscall.O_NONBLOCK
