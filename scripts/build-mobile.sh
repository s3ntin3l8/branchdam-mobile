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
# NDK compatibility: gomobile v0.0.0-20260821190718 validates the
# NDK's minSdkVersion and rejects versions outside 21..35. r25
# (25.1.8937393) is known to work; r26/r27 may report "unsupported
# API version 16" because of legacy NDK metadata.
#
# Sub-issue A ships this as the single source of truth for the mobile
# library build. Sub-issues B+ add the real Engine API; this script does
# not change.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLIC_PKG="github.com/s3ntin3l8/branchdam-mobile"

GOPATH_BIN="$(go env GOPATH)/bin"
export PATH="${GOPATH_BIN}:${PATH}"
export GOTOOLCHAIN=auto

if ! command -v gomobile >/dev/null 2>&1; then
    echo "gomobile not found in PATH. Installing gomobile and gobind..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
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

    echo "=== Building Android AAR (arm, arm64, 386, amd64) ==="
    mkdir -p android/app/libs
    gomobile bind \
        -target android \
        -javapkg io.branchdam.core \
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
