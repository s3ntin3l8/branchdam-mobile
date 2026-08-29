#!/usr/bin/env bash
set -euo pipefail

# Build script for BranchdamCore.xcframework from core/ Go package
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_FRAMEWORK="${REPO_ROOT}/ios/BranchdamCore.xcframework"

echo "=== Building iOS xcframework from core ==="

if ! command -v gomobile &>/dev/null; then
    echo "gomobile not found in PATH. Installing gomobile..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
    gomobile init
fi

cd "${REPO_ROOT}/core"

echo "Running gomobile bind for iOS and iOS Simulator..."
gomobile bind \
    -target ios,iossimulator \
    -o "${OUTPUT_FRAMEWORK}" \
    ./bindings

echo "Successfully built: ${OUTPUT_FRAMEWORK}"
