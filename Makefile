SHELL := bash
MISE := mise exec --
XTOOL := $(MISE) xtool
SWIFT := xcrun swift
FORMAT := xcrun swift-format
SWIFT_PATHS := apps/ios
XCODEBUILD := xcodebuild -project Cappuccino.xcodeproj -derivedDataPath .build/xcode CODE_SIGNING_ALLOWED=NO
ANDROID := apps/android/gradlew --no-daemon -p apps/android
ANDROID_TEST_OUTPUT := apps/android/app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected
DESTINATION ?=

.DEFAULT_GOAL := help
.PHONY: help setup generate fmt fmt-apple fmt-android fmt-check fmt-check-apple fmt-check-android md-format md-check test test-apple test-android lint-android check check-apple check-android build-ios ipa dev build-android ui-test ui-test-android validate validate-apple validate-android check-bridge bench-bridge-client resource-bridge-client

CARGO ?= cargo
BRIDGE_MANIFEST := services/bridge/Cargo.toml
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
	$(SWIFT) test --package-path apps/ios -Xswiftc -warnings-as-errors

test-android: ## Run pure Kotlin logic tests
	$(ANDROID) :app:testDebugUnitTest

lint-android: ## Run Android lint with warnings as errors
	$(ANDROID) :app:lintDebug

check-bridge: ## Bridge Rust gate: fmt, all hermetic tests, release example (soak is resource-bridge-client)
	$(CARGO) fmt --manifest-path $(BRIDGE_MANIFEST) -- --check
	$(CARGO) test --locked --manifest-path $(BRIDGE_MANIFEST)
	$(CARGO) build --locked --release --manifest-path $(BRIDGE_MANIFEST) --example test-client

# Stream scenarios require an existing readable SESSION and nonzero entries.
# Unknown sessions fail closed; they are not representative benchmark success.
# STREAM_CYCLES must be > 0.
SESSION ?= s-probe
STREAM_CYCLES ?= 20

bench-bridge-client: ## Reproducible bridge benchmark (BASE_URL, SESSION, STREAM_CYCLES; consumes bodies + stream entries)
	$(CARGO) run --locked --release --manifest-path $(BRIDGE_MANIFEST) --example test-client -- bench --base-url $(or $(BASE_URL),http://127.0.0.1:7392) --session $(SESSION) --stream-cycles $(STREAM_CYCLES) --iterations 300

resource-bridge-client: ## Leak/lifecycle soak (~6 min, opt-in before a version release); asserts FD/RSS/child/temp recovery
	$(CARGO) test --locked --manifest-path $(BRIDGE_MANIFEST) --test resource_soak -- --ignored --nocapture

check: check-apple check-android ## Check all platforms; requires both toolchains

check-apple: fmt-check-apple md-check test-apple ## Focused Apple gate

check-android: ## Focused Android gate: format, unit tests and lint in one Gradle run
	$(ANDROID) :ktfmtCheck :app:ktfmtCheck :app:testDebugUnitTest :app:lintDebug

build-ios: generate ## Build an unsigned iOS Simulator app
	$(XCODEBUILD) -scheme Cappuccino -destination 'generic/platform=iOS Simulator' build

ipa: ## Build an unsigned release iPhone .ipa with xtool (apps/ios/xtool/Cappuccino.ipa)
	cd apps/ios && $(XTOOL) dev build --configuration release --ipa && scripts/add-app-icon.sh xtool/Cappuccino.ipa

dev: ## Build, sign and install on a connected iPhone with xtool (run xtool setup once first)
	cd apps/ios && $(XTOOL) dev

build-android: ## Build debug app and instrumentation APKs
	$(ANDROID) :app:assembleDebug :app:assembleDebugAndroidTest

ui-test: generate ## Run iPhone shell smoke on an explicitly selected simulator
	@test -n "$(DESTINATION)" || { echo 'Set DESTINATION="platform=iOS Simulator,id=<simulator-id>"' >&2; exit 2; }
	$(XCODEBUILD) -scheme Cappuccino -destination '$(DESTINATION)' -parallel-testing-enabled NO test

ui-test-android: ## Run Android shell smoke on one task-owned emulator and export screenshots
	@test -n "$(ANDROID_SERIAL)" || { echo 'Set ANDROID_SERIAL=<task-owned emulator serial>' >&2; exit 2; }
	python3 services/bridge/tests/android_journey.py --serial "$(ANDROID_SERIAL)"
	mkdir -p .build/android/screens
	@set -e; for screen in Machines AddMachineIme Settings; do \
	  image=$$(find "$(ANDROID_TEST_OUTPUT)" -type f -name "$$screen.png"); \
	  test -n "$$image" && test -f "$$image" || { echo "Missing or ambiguous $$screen screenshot" >&2; exit 1; }; \
	  test "$$(file --brief --mime-type "$$image")" = image/png || { echo "Invalid $$screen screenshot" >&2; exit 1; }; \
	  cp "$$image" ".build/android/screens/$$screen.png"; \
	done

validate: validate-android check-bridge md-check ## Default Android/bridge/docs gate; Apple validation is explicit

validate-apple: check-apple build-ios ## Apple source/test/build acceptance

validate-android: ## Android source/test/build acceptance in one Gradle run
	$(ANDROID) :ktfmtCheck :app:ktfmtCheck :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
