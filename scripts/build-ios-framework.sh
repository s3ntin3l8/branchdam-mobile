#!/usr/bin/env bash
set -euo pipefail

# Build script for BranchDamCore.xcframework from core/ Go package
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_FRAMEWORK="${REPO_ROOT}/ios/BranchDamCore.xcframework"

echo "=== Building iOS xcframework from core ==="

GOPATH_BIN="$(go env GOPATH)/bin"
export PATH="${GOPATH_BIN}:${PATH}"
export GOTOOLCHAIN=auto

if ! command -v gomobile &>/dev/null; then
    echo "gomobile not found in PATH. Installing gomobile and gobind..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
fi

cd "${REPO_ROOT}/core"

echo "Running gomobile bind for iOS and iOS Simulator..."
gomobile bind \
    -target ios,iossimulator \
    -o "${OUTPUT_FRAMEWORK}" \
    ./bindings

echo "Successfully built: ${OUTPUT_FRAMEWORK}"
