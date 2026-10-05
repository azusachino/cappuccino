SHELL := bash
MISE := mise exec --
SWIFT := xcrun swift
FORMAT := xcrun swift-format
SWIFT_PATHS := packages/apple/Package.swift packages/apple/Sources packages/apple/Tests apps/ios apps/macos
XCODEBUILD := xcodebuild -project Cappuccino.xcodeproj -derivedDataPath .build/xcode CODE_SIGNING_ALLOWED=NO
ANDROID := apps/android/gradlew --no-daemon -p apps/android
ANDROID_TEST_OUTPUT := apps/android/app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected
DESTINATION ?=

.DEFAULT_GOAL := help
.PHONY: help setup generate fmt fmt-apple fmt-android fmt-check fmt-check-apple fmt-check-android md-format md-check test test-apple test-android lint-android check check-apple check-android build-ios build-macos build-android ui-test ui-test-android validate validate-apple validate-android
.NOTPARALLEL: check validate check-apple check-android validate-apple validate-android

help: ## List project commands
	@grep -hE '^[a-zA-Z0-9_-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*## "}{printf "  %-20s %s\n", $$1, $$2}'

setup: ## Install pinned Apple/Markdown tools (SDKs and JDK are prerequisites)
	mise install

generate: ## Generate the ignored Xcode project from project.yml
	$(MISE) xcodegen generate --spec project.yml

fmt: md-format fmt-apple fmt-android ## Format all project-owned source

fmt-apple: ## Format Swift
	$(FORMAT) format --in-place --recursive $(SWIFT_PATHS)

fmt-android: ## Format Kotlin and Gradle scripts with two-space Google style
	$(ANDROID) :ktfmtFormat :app:ktfmtFormat

fmt-check: fmt-check-apple fmt-check-android ## Check Swift and Kotlin style

fmt-check-apple: ## Check Swift style
	$(FORMAT) lint --strict --recursive $(SWIFT_PATHS)

fmt-check-android: ## Check Kotlin and Gradle script style
	$(ANDROID) :ktfmtCheck :app:ktfmtCheck

md-format: ## Format Markdown
	$(MISE) rumdl fmt .

md-check: ## Check Markdown
	$(MISE) rumdl check .

test: test-apple test-android ## Run both hermetic core suites

test-apple: ## Run Swift core tests with compiler warnings as errors
	$(SWIFT) test --package-path packages/apple -Xswiftc -warnings-as-errors

test-android: ## Run pure Kotlin logic tests
	$(ANDROID) :app:testDebugUnitTest

lint-android: ## Run Android lint with warnings as errors
	$(ANDROID) :app:lintDebug

check: check-apple check-android ## Check all platforms; requires both toolchains

check-apple: fmt-check-apple md-check test-apple ## Focused Apple gate

check-android: fmt-check-android test-android lint-android ## Focused Android gate

build-ios: generate ## Build an unsigned iOS Simulator app
	$(XCODEBUILD) -scheme Cappuccino -destination 'generic/platform=iOS Simulator' build

build-macos: generate ## Build the shared macOS shell
	$(XCODEBUILD) -scheme CappuccinoMac -destination 'platform=macOS' build

build-android: ## Build debug app and instrumentation APKs
	$(ANDROID) :app:assembleDebug :app:assembleDebugAndroidTest

ui-test: generate ## Run iPhone shell smoke on an explicitly selected simulator
	@test -n "$(DESTINATION)" || { echo 'Set DESTINATION="platform=iOS Simulator,id=<simulator-id>"' >&2; exit 2; }
	$(XCODEBUILD) -scheme Cappuccino -destination '$(DESTINATION)' -parallel-testing-enabled NO test

ui-test-android: ## Run Android shell smoke on one task-owned emulator and export screenshots
	@test -n "$(ANDROID_SERIAL)" || { echo 'Set ANDROID_SERIAL=<task-owned emulator serial>' >&2; exit 2; }
	$(ANDROID) :app:connectedDebugAndroidTest
	mkdir -p .build/android/screens
	@set -e; for screen in Chats Attention Machines; do \
	  image=$$(find "$(ANDROID_TEST_OUTPUT)" -type f -name "$$screen.png"); \
	  test -n "$$image" && test -f "$$image" || { echo "Missing or ambiguous $$screen screenshot" >&2; exit 1; }; \
	  test "$$(file --brief --mime-type "$$image")" = image/png || { echo "Invalid $$screen screenshot" >&2; exit 1; }; \
	  cp "$$image" ".build/android/screens/$$screen.png"; \
	done

validate: validate-apple validate-android ## Build/check all apps; UI journeys remain explicit

validate-apple: check-apple build-ios build-macos ## Apple source/test/build acceptance

validate-android: check-android build-android ## Android source/test/build acceptance
