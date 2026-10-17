.PHONY: all check test lint contract-sync android android-test ios-framework ios-test mobile-build mobile-build-android mobile-build-ios screenshots clean

UNAME_S := $(shell uname -s)
IOS_DEST := $(shell xcodebuild -showdestinations -project ios/BranchDamApp.xcodeproj -scheme BranchDamApp 2>/dev/null | grep 'platform:iOS Simulator' | grep 'iPhone' | grep -v 'Placeholder' | head -1 | sed -n 's/.*id:\([^,]*\).*/id=\1/p')
ifeq ($(IOS_DEST),)
IOS_DEST := platform=iOS Simulator,name=iPhone 16
endif

all: check

check: lint test

test:
	cd core && go test -race -v ./...
	cd . && go test -race -v ./.
ifeq ($(UNAME_S),Darwin)
	@echo "==> Running iOS unit tests..."
	xcodebuild test -project ios/BranchDamApp.xcodeproj -scheme BranchDamApp -destination '$(IOS_DEST)' CODE_SIGNING_ALLOWED=NO
else
	@echo "==> Linux detected: Skipping iOS xcodebuild tests."
endif

# BRANCHDAM points at a branchDAM checkout (default: a sibling directory).
BRANCHDAM ?= ../branchdam
# Refresh core/client/testdata/contract from branchDAM's generated
# agent-API fixtures, then run the conformance tests against them.
# contract-sync copies the fixtures of a clean branchDAM checkout, records
# its commit in SOURCE and runs the conformance tests. If `make lint` then
# flags a fixture value as a secret (detect-secrets), refresh the baseline:
# detect-secrets scan --baseline .secrets.baseline
contract-sync:
	@test -d "$(BRANCHDAM)/docs/contract/fixtures" || { echo "no $(BRANCHDAM)/docs/contract/fixtures -- set BRANCHDAM=<branchdam checkout>"; exit 1; }
	@git -C "$(BRANCHDAM)" rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "$(BRANCHDAM) is not a git checkout; SOURCE needs its commit -- use a clone"; exit 1; }
	@test -z "$$(git -C "$(BRANCHDAM)" status --porcelain -- docs/contract/fixtures)" || { echo "$(BRANCHDAM)/docs/contract/fixtures has uncommitted changes; SOURCE would name a commit without them -- commit first"; exit 1; }
	rm -f core/client/testdata/contract/*.json
	cp "$(BRANCHDAM)"/docs/contract/fixtures/*.json core/client/testdata/contract/
	git -C "$(BRANCHDAM)" log -1 --format='branchdam %H %s' -- docs/contract/fixtures > core/client/testdata/contract/SOURCE
	@test -s core/client/testdata/contract/SOURCE || { echo "no commit touches $(BRANCHDAM)/docs/contract/fixtures; SOURCE is empty"; exit 1; }
	cd core && go test ./client/ ./engine/ -run Contract -count=1

lint:
	pre-commit run --all-files
	cd core && go vet ./...
	cd . && go vet ./.

android:
	cd android && if [ -f "./gradlew" ]; then ./gradlew assembleDebug; fi

# Single gradlew invocation runs unit tests and release compilation in one daemon pass.
android-test:
	cd android && if [ -f "./gradlew" ]; then ./gradlew testDebugUnitTest compileReleaseKotlin; fi

# Build the gomobile-bound branchdam library (Android AAR + iOS xcframework).
# Produces android/app/libs/branchdam.aar and ios/Frameworks/BranchDam.xcframework.
# Sub-issue A replaces the legacy ios-framework target; old name removed.
mobile-build:
	./scripts/build-mobile.sh

mobile-build-android:
	./scripts/build-mobile.sh --android-only

mobile-build-ios:
	./scripts/build-mobile.sh --ios-only

# Android screenshots from a headless emulator paired with a throwaway
# branchDAM demo (../branchDAM, `make demo`). See scripts/screenshots.sh.
# A relative OUT is resolved against this repo's root.
OUT ?= docs/screenshots
screenshots:
	./scripts/screenshots.sh --out "$(OUT)"

ios-test:
ifeq ($(UNAME_S),Darwin)
	xcodebuild test -project ios/BranchDamApp.xcodeproj -scheme BranchDamApp -destination '$(IOS_DEST)' CODE_SIGNING_ALLOWED=NO
else
	@echo "==> Linux detected: xcodebuild is unavailable on Linux."
endif

clean:
	rm -rf dist/ coverage.txt core/coverage.txt \
		android/app/libs/branchdam.aar \
		ios/Frameworks/branchdam.xcframework \
		ios/Frameworks/BranchDam.xcframework \
		ios/BranchDamCore.xcframework
	cd android && if [ -f "./gradlew" ]; then ./gradlew clean; fi
