# AuraLite-Render host build: Java 11, no Gradle, no IDE.
# The only network access is `make deps`, which fetches the LWJGL jars used to
# compile-verify the GL and Vulkan backends. Everything else works offline.
.RECIPEPREFIX = >

JAVAC ?= javac
JAVA ?= java

LWJGL_VERSION = 3.4.3
MAVEN = https://repo1.maven.org/maven2/org/lwjgl
THIRD_PARTY = third_party
LWJGL_JARS = $(THIRD_PARTY)/lwjgl.jar $(THIRD_PARTY)/lwjgl-opengl.jar $(THIRD_PARTY)/lwjgl-vulkan.jar

BUILD = build
CORE_CLASSES = $(BUILD)/core
NULL_CLASSES = $(BUILD)/null
GL_CLASSES = $(BUILD)/gl
VK_CLASSES = $(BUILD)/vk
TEST_CLASSES = $(BUILD)/tests

.PHONY: all deps build test bench clean help

all: build

help:
> @echo "targets: deps build test bench clean"
> @echo "  deps  - fetch LWJGL $(LWJGL_VERSION) jars into third_party/ (gitignored, never committed)"
> @echo "  build - compile core, backend-null, backend-gl, backend-vulkan, tests"
> @echo "  test  - build, then run all six host gates (must end RESULT: PASS)"
> @echo "  bench - build, then run the gates 5x and show the timing lines"

deps: $(LWJGL_JARS)
> @echo "GL46 present: $$(unzip -l $(THIRD_PARTY)/lwjgl-opengl.jar | grep -c 'GL46.class') class(es)"
> @echo "VK13 present: $$(unzip -l $(THIRD_PARTY)/lwjgl-vulkan.jar | grep -c 'VK13.class') class(es)"

$(THIRD_PARTY)/lwjgl.jar:
> @mkdir -p $(THIRD_PARTY)
> curl -sSL -o $@ $(MAVEN)/lwjgl/$(LWJGL_VERSION)/lwjgl-$(LWJGL_VERSION).jar

$(THIRD_PARTY)/lwjgl-opengl.jar:
> @mkdir -p $(THIRD_PARTY)
> curl -sSL -o $@ $(MAVEN)/lwjgl-opengl/$(LWJGL_VERSION)/lwjgl-opengl-$(LWJGL_VERSION).jar

$(THIRD_PARTY)/lwjgl-vulkan.jar:
> @mkdir -p $(THIRD_PARTY)
> curl -sSL -o $@ $(MAVEN)/lwjgl-vulkan/$(LWJGL_VERSION)/lwjgl-vulkan-$(LWJGL_VERSION).jar

build: deps
> @mkdir -p $(CORE_CLASSES) $(NULL_CLASSES) $(GL_CLASSES) $(VK_CLASSES) $(TEST_CLASSES)
> @echo "--- core (java 11, no dependencies) ---"
> find core/src -name '*.java' | sort > $(BUILD)/core-sources.txt
> $(JAVAC) -Xlint:-options -d $(CORE_CLASSES) @$(BUILD)/core-sources.txt
> @echo "--- backend-null ---"
> find backend-null/src -name '*.java' | sort > $(BUILD)/null-sources.txt
> $(JAVAC) -cp $(CORE_CLASSES) -d $(NULL_CLASSES) @$(BUILD)/null-sources.txt
> @echo "--- backend-gl (against third_party jars) ---"
> @if [ -n "$$(find backend-gl/src -name '*.java' 2>/dev/null)" ]; then \
      find backend-gl/src -name '*.java' | sort > $(BUILD)/gl-sources.txt; \
      $(JAVAC) -cp $(CORE_CLASSES):$(THIRD_PARTY)/lwjgl.jar:$(THIRD_PARTY)/lwjgl-opengl.jar \
        -d $(GL_CLASSES) @$(BUILD)/gl-sources.txt; \
    else echo "backend-gl: no sources yet, SKIP"; fi
> @echo "--- backend-vulkan (against third_party jars) ---"
> @if [ -n "$$(find backend-vulkan/src -name '*.java' 2>/dev/null)" ]; then \
      find backend-vulkan/src -name '*.java' | sort > $(BUILD)/vk-sources.txt; \
      $(JAVAC) -cp $(CORE_CLASSES):$(THIRD_PARTY)/lwjgl.jar:$(THIRD_PARTY)/lwjgl-vulkan.jar \
        -d $(VK_CLASSES) @$(BUILD)/vk-sources.txt; \
    else echo "backend-vulkan: no sources yet, SKIP"; fi
> @echo "--- tests ---"
> find tests/src -name '*.java' | sort > $(BUILD)/tests-sources.txt
> $(JAVAC) -cp $(CORE_CLASSES):$(NULL_CLASSES):$(GL_CLASSES):$(VK_CLASSES) \
      -d $(TEST_CLASSES) @$(BUILD)/tests-sources.txt
> @echo "build: OK"

# The runtime classpath deliberately omits third_party: the mapping tables are
# pure data and must load with no LWJGL present. A NoClassDefFoundError in the
# tables suite means a real GL/VK call leaked into a mapping function.
test: build
> $(JAVA) -cp $(CORE_CLASSES):$(NULL_CLASSES):$(GL_CLASSES):$(VK_CLASSES):$(TEST_CLASSES) \
      dev.auralite.render.tests.RunAll

bench: build
> @for i in 1 2 3 4 5; do \
      $(JAVA) -cp $(CORE_CLASSES):$(NULL_CLASSES):$(GL_CLASSES):$(VK_CLASSES):$(TEST_CLASSES) \
        dev.auralite.render.tests.RunAll --receipts 2>&1 | grep -E '^(TOTAL|RESULT)'; \
    done

clean:
> rm -rf $(BUILD)
