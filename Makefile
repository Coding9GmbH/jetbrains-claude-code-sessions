JAVA_HOME := /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

GRADLEW := ./gradlew
PLUGIN_VERSION := $(shell grep '^pluginVersion=' gradle.properties | cut -d= -f2)
PLUGIN_ZIP := build/distributions/claude-code-sessions-$(PLUGIN_VERSION).zip

# ──────────────────────────────────────────────────────────────────────────────
# Targets
# ──────────────────────────────────────────────────────────────────────────────

.DEFAULT_GOAL := build

## build: Compile and package the plugin ZIP
.PHONY: build
build:
	$(GRADLEW) buildPlugin

## compile: Only compile Kotlin sources
.PHONY: compile
compile:
	$(GRADLEW) compileKotlin

## run: Launch a sandboxed IntelliJ instance with the plugin loaded
.PHONY: run
run:
	$(GRADLEW) runIde

## install: Build and install into PhpStorm (auto-detects via Toolbox)
.PHONY: install
install: build
	@PHPSTORM=$$(ls -d ~/Library/Application\ Support/JetBrains/PhpStorm*/plugins 2>/dev/null | sort -r | head -1); \
	if [ -n "$$PHPSTORM" ]; then \
		echo "Installing to $$PHPSTORM ..."; \
		rm -rf "$$PHPSTORM/claude-code-sessions"; \
		unzip -q "$(PLUGIN_ZIP)" -d "$$PHPSTORM"; \
		echo "Done – restart PhpStorm to activate the plugin."; \
	else \
		echo "PhpStorm plugins directory not found."; \
		echo "Install manually: Preferences -> Plugins -> gear icon -> Install Plugin from Disk -> $(PLUGIN_ZIP)"; \
	fi

## clean: Remove all build artefacts
.PHONY: clean
clean:
	$(GRADLEW) clean

## verify: Run IntelliJ plugin verifier
.PHONY: verify
verify:
	$(GRADLEW) verifyPlugin

## zip: Print the path to the built plugin ZIP
.PHONY: zip
zip: build
	@echo "Plugin ZIP: $(PLUGIN_ZIP)"
	@ls -lh "$(PLUGIN_ZIP)"

## help: Show this help
.PHONY: help
help:
	@echo "Usage: make [target]"
	@echo ""
	@grep -E '^## ' Makefile | sed 's/## /  /'
