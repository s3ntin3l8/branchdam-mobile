.PHONY: all check test lint android android-test ios-framework ios-test mobile-build mobile-build-android mobile-build-ios clean

all: check

check: lint test

test:
	cd core && go test -race -v ./...
	cd . && go test -race -v ./.

lint:
	pre-commit run --all-files
	cd core && go vet ./...
	cd . && go vet ./.

android:
	cd android && if [ -f "./gradlew" ]; then ./gradlew assembleDebug; fi

android-test:
	cd android && if [ -f "./gradlew" ]; then ./gradlew test; fi

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
	xcodebuild test -project ios/BranchDamApp.xcodeproj -scheme BranchDamApp -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO

clean:
	rm -rf dist/ coverage.txt core/coverage.txt \
		android/app/libs/branchdam.aar \
		ios/Frameworks/branchdam.xcframework \
		ios/Frameworks/BranchDam.xcframework \
		ios/BranchDamCore.xcframework
	cd android && if [ -f "./gradlew" ]; then ./gradlew clean; fi
