.PHONY: all check test lint android clean

all: check

check: lint test

test:
	cd core && go test -race -v ./...

lint:
	pre-commit run --all-files
	cd core && go vet ./...

android:
	cd android && if [ -f "./gradlew" ]; then ./gradlew assembleDebug; fi

clean:
	rm -rf dist/ coverage.txt core/coverage.txt
	cd android && if [ -f "./gradlew" ]; then ./gradlew clean; fi
