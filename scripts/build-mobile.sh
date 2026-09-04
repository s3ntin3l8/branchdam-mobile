#!/usr/bin/env bash
set -euo pipefail

# Build the branchdam mobile library for Android and iOS from the public
# Go package at the repo root (package `branchdam`).
#
# Outputs:
#   android/app/libs/branchdam.aar       (Kotlin/Java consumer)
#   ios/Frameworks/branchdam.xcframework (Swift consumer; ios + iossimulator)
#
# Required environment:
#   For Android:  ANDROID_HOME, ANDROID_NDK_HOME, javac (1.8+)
#   For iOS:      Xcode (must run on macOS)
#
# Reproducibility: gomobile and gobind are installed at the pseudo-version
# pinned in go.mod's tool directive. Using @latest here would let the
# toolchain drift between runs and across CI; pinning matches the build
# of the public Go package itself.
#
# NDK compatibility: gomobile v0.0.0-20260821190718 defaults to
# -androidapi=16, which is below every recent NDK's supported min
# (r25: 19..33, r26: 21..35, r27: 21..35). The script passes
# -androidapi=21, the lowest API the r26/r27 NDKs support, which is
# below the app's minSdk = 28 (gomobile's AAR minSdk is independent
# of the consumer's minSdk; the AAR links against API 21 stubs that
# are no-ops on devices above 21).
#
# Sub-issue A ships this as the single source of truth for the mobile
# library build. Sub-issues B+ add the real Engine API; this script does
# not change.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLIC_PKG="github.com/s3ntin3l8/branchdam-mobile"
# Must match the pseudo-version pinned in go.mod's tool directive.
GOMOBILE_REV="v0.0.0-20260821190718-4776eadac327"

GOPATH_BIN="$(go env GOPATH)/bin"
export PATH="${GOPATH_BIN}:${PATH}"
export GOTOOLCHAIN=auto

if ! command -v gomobile >/dev/null 2>&1; then
    echo "gomobile not found in PATH. Installing gomobile and gobind at ${GOMOBILE_REV}..."
    go install "golang.org/x/mobile/cmd/gomobile@${GOMOBILE_REV}"
    go install "golang.org/x/mobile/cmd/gobind@${GOMOBILE_REV}"
fi

# Allow callers to skip a target. Usage: build-mobile.sh --android-only / --ios-only
BUILD_ANDROID=1
BUILD_IOS=1
for arg in "$@"; do
    case "$arg" in
        --android-only) BUILD_IOS=0 ;;
        --ios-only) BUILD_ANDROID=0 ;;
        --help|-h)
            sed -n '2,18p' "$0"
            exit 0
            ;;
        *) echo "unknown flag: $arg" >&2; exit 2 ;;
    esac
done

cd "${REPO_ROOT}"

if [[ "${BUILD_ANDROID}" -eq 1 ]]; then
    : "${ANDROID_HOME:?ANDROID_HOME must be set to build the Android AAR}"
    : "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must be set to build the Android AAR}"
    if ! command -v javac >/dev/null 2>&1; then
        echo "javac not found in PATH (need JDK 1.8+ for the Android target)" >&2
        exit 1
    fi

    echo "=== Building Android AAR (arm64, amd64) ==="
    mkdir -p android/app/libs
    # 16 KB page-size alignment: Android 15+ devices (Pixel 8+, Android 15
    # emulators) use 16 KB kernel pages. Every PT_LOAD segment in the
    # bundled .so files must have p_align >= 0x4000. -ldflags is threaded
    # to `go build` (cmd/gomobile/build.go:323); since -buildmode=c-shared
    # forces the external linker (clang), the flag must be wrapped in
    # -extldflags so it reaches the system linker.
    # See: https://github.com/golang/go/issues/68754
    #
    # -androidapi 21: matches the project's minSdk = 28 floor and is within
    # the r25/r26 NDK's supported range (r25: 19..33, r26: 21..35).
    # Without -androidapi, gomobile defaults to API 16, which is below
    # every recent NDK's min API and fails the env check.
    gomobile bind \
        -target android \
        -androidapi 21 \
        -javapkg io.branchdam.core \
        -ldflags="-extldflags=-Wl,-z,max-page-size=16384" \
        -o android/app/libs/branchdam.aar \
        "${PUBLIC_PKG}"
    echo "AAR: $(ls -la android/app/libs/branchdam.aar)"
fi

if [[ "${BUILD_IOS}" -eq 1 ]]; then
    if [[ "$(uname -s)" != "Darwin" ]]; then
        echo "iOS xcframework build requires macOS (got: $(uname -s))" >&2
        exit 1
    fi
    if ! command -v xcodebuild >/dev/null 2>&1; then
        echo "xcodebuild not found in PATH" >&2
        exit 1
    fi

    echo "=== Building iOS xcframework (ios, iossimulator) ==="
    mkdir -p ios/Frameworks
    # The Swift module name is taken from the .xcframework directory
    # basename; Obj-C class names are built as <Prefix><Title(pkgName)>.
    # With pkgName="branchdam", the natural Obj-C class prefix is
    # "Branchdam" (Title of the lowercase package name). We use a
    # lowercase .xcframework directory so the Swift module name
    # "branchdam" matches the Obj-C class prefix "Branchdam" —
    # Swift's import is case-insensitive but its Obj-C prefix-stripping
    # logic is case-sensitive, so the names must match exactly.
    gomobile bind \
        -target ios,iossimulator \
        -o ios/Frameworks/branchdam.xcframework \
        "${PUBLIC_PKG}"
    echo "xcframework: $(ls -la ios/Frameworks/branchdam.xcframework)"
fi

echo "=== build-mobile.sh: done ==="
