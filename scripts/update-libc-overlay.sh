#!/usr/bin/env bash
set -euo pipefail

# Rebuild third_party/modernc.org/libc as upstream modernc.org/libc <version>
# plus third_party/modernc.org/libc.patch (the x86_64 Android seccomp fix, #211).
#
# Usage: scripts/update-libc-overlay.sh v1.79.0
#
# Run it whenever go.mod's modernc.org/libc requirement moves (e.g. a sqlite
# bump); `make check-libc-overlay` fails until the overlay matches.

version="${1:?usage: $0 <libc version, e.g. v1.79.0>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
overlay="$root/third_party/modernc.org/libc"
patchfile="$root/third_party/modernc.org/libc.patch"

modcache="$(go env GOMODCACHE)"
(cd "$root/core" && go mod download "modernc.org/libc@$version")
src="$modcache/modernc.org/libc@$version"
[ -d "$src" ] || { echo "could not download modernc.org/libc@$version" >&2; exit 1; }
commit="$(sed -n 's/.*"Hash": *"\([0-9a-f]*\)".*/\1/p' "$modcache/cache/download/modernc.org/libc/@v/$version.info" | head -1)"

rm -rf "$overlay"
mkdir -p "$overlay"
cp -R "$src"/. "$overlay"/
chmod -R u+w "$overlay"

# Trim what the overlay never needs: tests, fixtures, upstream dev files, and
# libc_linux_amd64.go (build-tagged !(linux && amd64), dead on our target).
find "$overlay" -name '*_test.go' -delete
rm -rf "$overlay/testdata"
rm -f "$overlay"/{Makefile,CLAUDE.md,GEMINI.md,.gitignore,build_all_targets.sh,libc_linux_amd64.go}

(cd "$overlay" && patch -p1 --forward --no-backup-if-mismatch < "$patchfile")
find "$overlay" \( -name '*.rej' -o -name '*.orig' \) -print -quit | grep -q . && { echo "patch left rejects" >&2; exit 1; }

header="# modernc.org/libc (branchDAM Mobile Overlay)

This directory contains a vendored overlay of \`modernc.org/libc\` \`$version\`${commit:+ (Git commit \`$commit\`)}.
Regenerate it with \`scripts/update-libc-overlay.sh <version>\`; the patch lives in \`third_party/modernc.org/libc.patch\`.

## Patch Details (x86_64 Android Seccomp Fix - #211)
Android's per-app Zygote seccomp filter on x86_64 devices and emulators blocks legacy non-\`*at\` syscalls (\`SYS_lstat\`, \`SYS_stat\`, \`SYS_open\`, \`SYS_chmod\`, \`SYS_chown\`, \`SYS_mkdir\`, \`SYS_unlink\`, \`SYS_rmdir\`, \`SYS_rename\`, \`SYS_link\`, \`SYS_symlink\`, \`SYS_readlink\`), killing the Go core engine in \`EngineInit\` with \`SIGSYS\` (signal 31).

\`ccgo_linux_amd64.go\` and \`syscall_musl.go\` are patched to use the modern \`*at\` equivalents (\`newfstatat\`, \`openat\`, \`fchmodat\`, \`unlinkat\`, \`renameat\`, \`fchownat\`, \`linkat\`, \`readlinkat\`, \`mkdirat\`, \`symlinkat\`), which comply with the Zygote filter and run on all Linux 2.6.16+ kernels.

---
"
{ printf '%s\n' "$header"; cat "$overlay/README.md"; } > "$overlay/README.md.new"
mv "$overlay/README.md.new" "$overlay/README.md"
echo "overlay now at modernc.org/libc $version; update the go.mod replace comments and run go mod tidy in / and /core"
