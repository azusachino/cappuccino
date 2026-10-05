SHELL := bash
MISE := mise exec --
SWIFT := xcrun swift
FORMAT := xcrun swift-format
XCODEBUILD := xcodebuild -project Cappuccino.xcodeproj -derivedDataPath .build/xcode CODE_SIGNING_ALLOWED=NO
DESTINATION ?=

.DEFAULT_GOAL := help
.PHONY: help setup generate fmt fmt-check md-format md-check test check build-ios build-macos ui-test validate
.NOTPARALLEL: check validate

help: ## List project commands
	@grep -hE '^[a-zA-Z0-9_-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*## "}{printf "  %-15s %s\n", $$1, $$2}'

setup: ## Install pinned non-Xcode tools
	mise install

generate: ## Generate the ignored Xcode project from project.yml
	$(MISE) xcodegen generate --spec project.yml

fmt: md-format ## Format Swift and Markdown
	$(FORMAT) format --in-place --recursive Package.swift Sources Tests App UITests

fmt-check: ## Check Swift style
	$(FORMAT) lint --strict --recursive Package.swift Sources Tests App UITests

md-format: ## Format Markdown
	$(MISE) rumdl fmt .

md-check: ## Check Markdown
	$(MISE) rumdl check .

test: ## Run hermetic core tests
	$(SWIFT) test

check: fmt-check md-check test ## Focused local and CI gate

build-ios: generate ## Build an unsigned iOS Simulator app
	$(XCODEBUILD) -scheme Cappuccino -destination 'generic/platform=iOS Simulator' build

build-macos: generate ## Build the shared macOS shell
	$(XCODEBUILD) -scheme CappuccinoMac -destination 'platform=macOS' build

ui-test: generate ## Run shell smoke on an explicitly selected simulator
	@test -n "$(DESTINATION)" || { echo 'Set DESTINATION="platform=iOS Simulator,id=<simulator-id>"' >&2; exit 2; }
	$(XCODEBUILD) -scheme Cappuccino -destination '$(DESTINATION)' -parallel-testing-enabled NO test

validate: check build-ios build-macos ## Acceptance gate; ui-test is a separate runtime check
