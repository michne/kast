# Makefile to produce a single distributable unit for the analysis-cli module.
# Targets:
#   make cli         -> build fat JAR, emit wrapper, package into dist/analysis-cli
#   make clean-dist  -> remove dist/
#   make run         -> run packaged CLI (help)

.PHONY: all cli clean-dist run
all: cli

CLI_NAME=analysis-cli
MODULE_DIR=analysis-cli
BUILD_DIR=$(MODULE_DIR)/build
SCRIPTS_DIR=$(BUILD_DIR)/scripts
LIBS_DIR=$(BUILD_DIR)/libs
DIST_DIR=dist/$(CLI_NAME)

cli:
	@echo "Building fat jar and wrapper for $(CLI_NAME)"
	./gradlew writeWrapperScript
	@echo "Packaging into $(DIST_DIR)"
	mkdir -p $(DIST_DIR)/libs
	cp $(SCRIPTS_DIR)/$(CLI_NAME) $(DIST_DIR)/$(CLI_NAME)
	chmod +x $(DIST_DIR)/$(CLI_NAME)
	cp $(LIBS_DIR)/*-all.jar $(DIST_DIR)/libs/ || true
	@echo "Packaged $(CLI_NAME) into $(DIST_DIR)"

clean-dist:
	rm -rf dist

run:
	@echo "Running packaged $(CLI_NAME) (if present)"
	$(DIST_DIR)/$(CLI_NAME) --help
