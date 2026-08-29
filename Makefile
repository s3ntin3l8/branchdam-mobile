.PHONY: all check test lint android ios-framework ios-test clean

all: check

check: lint test

test:
	cd core && go test -race -v ./...

lint:
	pre-commit run --all-files
	cd core && go vet ./...

android:
	cd android && if [ -f "./gradlew" ]; then ./gradlew assembleDebug; fi

ios-framework:
	./scripts/build-ios-framework.sh

ios-test:
	xcodebuild test -project ios/BranchDamApp.xcodeproj -scheme BranchDamApp -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO

clean:
	rm -rf dist/ coverage.txt core/coverage.txt ios/BranchdamCore.xcframework
	cd android && if [ -f "./gradlew" ]; then ./gradlew clean; fi
