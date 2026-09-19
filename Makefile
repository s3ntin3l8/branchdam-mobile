.PHONY: all check test lint android android-test ios-framework ios-test mobile-build mobile-build-android mobile-build-ios clean

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

lint:
	pre-commit run --all-files
	cd core && go vet ./...
	cd . && go vet ./.

android:
	cd android && if [ -f "./gradlew" ]; then ./gradlew assembleDebug; fi

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
