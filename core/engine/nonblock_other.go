//go:build !(linux || darwin)

package engine

// openFlagsNonblock is 0 on platforms with no O_NONBLOCK.
//
// These platforms have no POSIX FIFO, so a path that is not a regular
// file is rejected by the regular-file check before the open happens,
// and the open itself cannot block on a FIFO.
const openFlagsNonblock = 0
