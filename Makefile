# AuraLite-Render host build: Java 11, no Gradle, no IDE.
# The only network access is `make deps`, which fetches the LWJGL jars used to
# compile-verify the GL and Vulkan backends. Everything else works offline.
.RECIPEPREFIX = >

JAVAC ?= javac
JAVA ?= java

LWJGL_VERSION = 3.4.3
MAVEN = https://repo1.maven.org/maven2/org/lwjgl
THIRD_PARTY = third_party
LWJGL_JARS = $(THIRD_PARTY)/lwjgl.jar $(THIRD_PARTY)/lwjgl-opengl.jar $(THIRD_PARTY)/lwjgl-vulkan.jar \
    $(THIRD_PARTY)/lwjgl-egl.jar $(THIRD_PARTY)/lwjgl-natives-linux.jar \
    $(THIRD_PARTY)/lwjgl-opengl-natives-linux.jar

BUILD = build
CORE_CLASSES = $(BUILD)/core
NULL_CLASSES = $(BUILD)/null
GL_CLASSES = $(BUILD)/gl
VK_CLASSES = $(BUILD)/vk
TEST_CLASSES = $(BUILD)/tests
TOOLS_CLASSES = $(BUILD)/tools
GPU_CLASSES = $(BUILD)/gpu

.PHONY: all deps build test bench packlint drift-check clean help

all: build

help:
> @echo "targets: deps build test bench gpu-test packlint drift-check clean"
> @echo "  deps        - fetch LWJGL $(LWJGL_VERSION) jars into third_party/ (gitignored, never committed)"
> @echo "  build       - compile core, backend-null, backend-gl, backend-vulkan, tests, tools"
> @echo "  test        - build, then run all eight host gates (must end RESULT: PASS)"
> @echo "  bench       - build, then run the gates 5x and show the timing lines"
> @echo "  gpu-test    - run OUR real GL/Vulkan backends on this host's GPU (software ok);"
> @echo "                one named receipt per lane: RENDERED / REFUSED-NAMED / SKIP-NAMED"
> @echo "  packlint    - validate PACK=path (dir or zip); STRICT=1 promotes warnings, NULL_DEVICE=1 checks capabilities"
> @echo "  drift-check - replay the suite receipts; they must match the doc lines or fail by name"

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

# EGL bindings (headless GL contexts) and the Linux natives for the gpu-test
# lane. Vulkan uses the system libvulkan, no extra jar needed on Linux.
$(THIRD_PARTY)/lwjgl-egl.jar:
> @mkdir -p $(THIRD_PARTY)
> curl -sSL -o $@ $(MAVEN)/lwjgl-egl/$(LWJGL_VERSION)/lwjgl-egl-$(LWJGL_VERSION).jar

$(THIRD_PARTY)/lwjgl-natives-linux.jar:
> @mkdir -p $(THIRD_PARTY)
> curl -sSL -o $@ $(MAVEN)/lwjgl/$(LWJGL_VERSION)/lwjgl-$(LWJGL_VERSION)-natives-linux.jar

$(THIRD_PARTY)/lwjgl-opengl-natives-linux.jar:
> @mkdir -p $(THIRD_PARTY)
> curl -sSL -o $@ $(MAVEN)/lwjgl-opengl/$(LWJGL_VERSION)/lwjgl-opengl-$(LWJGL_VERSION)-natives-linux.jar

build: deps
> @mkdir -p $(CORE_CLASSES) $(NULL_CLASSES) $(GL_CLASSES) $(VK_CLASSES) $(TEST_CLASSES) $(TOOLS_CLASSES) $(GPU_CLASSES)
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
> find tests/src -name '*.java' ! -name 'GpuSmoke.java' | sort > $(BUILD)/tests-sources.txt
> $(JAVAC) -cp $(CORE_CLASSES):$(NULL_CLASSES):$(GL_CLASSES):$(VK_CLASSES) \
      -d $(TEST_CLASSES) @$(BUILD)/tests-sources.txt
> @echo "--- gpu smoke (against third_party jars; Phase 5 lane) ---"
> find tests/src -name 'GpuSmoke.java' | sort > $(BUILD)/gpu-sources.txt
> $(JAVAC) -cp $(CORE_CLASSES):$(NULL_CLASSES):$(GL_CLASSES):$(VK_CLASSES):$(THIRD_PARTY)/lwjgl.jar:$(THIRD_PARTY)/lwjgl-opengl.jar:$(THIRD_PARTY)/lwjgl-egl.jar \
      -d $(GPU_CLASSES) @$(BUILD)/gpu-sources.txt
> @echo "--- tools (packlint, drift-check) ---"
> find tools -name '*.java' | sort > $(BUILD)/tools-sources.txt
> $(JAVAC) -cp $(CORE_CLASSES):$(NULL_CLASSES) -d $(TOOLS_CLASSES) @$(BUILD)/tools-sources.txt
> @echo "--- fabric (java 21 + gradle/loom; sources + json only in this lane) ---"
> @major=$$($(JAVAC) -version 2>&1 | sed -n 's/^javac[ ]*\([0-9]*\).*/\1/p'); \
    if [ -n "$$major" ] && [ "$$major" -ge 21 ]; then \
      echo "fabric: JDK $$major present - windowed lane lives in fabric/ (./gradlew build); not part of make"; \
    else \
      echo "fabric: SKIP - needs JDK 21 (this lane builds where 21 exists, by design); sources + json gated by the fabric suite"; \
    fi
> @echo "build: OK"

# The runtime classpath deliberately omits third_party: the mapping tables are
# pure data and must load with no LWJGL present. A NoClassDefFoundError in the
# tables suite means a real GL/VK call leaked into a mapping function.
test: build
> $(JAVA) -cp $(CORE_CLASSES):$(NULL_CLASSES):$(GL_CLASSES):$(VK_CLASSES):$(TEST_CLASSES) \
      dev.auralite.render.tests.RunAll

# Phase 5 groundwork: run OUR real backends on this host's GPU. Software
# rasterizers (lavapipe/llvmpipe) count as real drivers; no GPU at all answers
# with a named SKIP - never silence. Exit 0 = every asked lane answered BY NAME
# (RENDERED, REFUSED-NAMED or SKIP-NAMED); exit 1 = a lane died without a name.
# Usage: make gpu-test [LANE=vk|gl|all] [AURALITE_VK_VALIDATION=0]
# AURALITE_VK_VALIDATION defaults to 1 here: this is a diagnostic lane, and
# the Khronos layer must print the named finding before lavapipe ever gets to
# abort the JVM on invalid state. Set =0 only to measure uncontested timing.
# EGL_PLATFORM=surfaceless: headless hosts have neither /dev/dri (GBM) nor an
# X server; Mesa's surfaceless EGL platform is the honest display there.
gpu-test: build
> AURALITE_VK_VALIDATION=$${AURALITE_VK_VALIDATION:-1} EGL_PLATFORM=surfaceless $(JAVA) -cp $(CORE_CLASSES):$(NULL_CLASSES):$(GL_CLASSES):$(VK_CLASSES):$(TEST_CLASSES):$(GPU_CLASSES):$(THIRD_PARTY)/lwjgl.jar:$(THIRD_PARTY)/lwjgl-opengl.jar:$(THIRD_PARTY)/lwjgl-egl.jar:$(THIRD_PARTY)/lwjgl-vulkan.jar:$(THIRD_PARTY)/lwjgl-natives-linux.jar:$(THIRD_PARTY)/lwjgl-opengl-natives-linux.jar \
      dev.auralite.render.tests.GpuSmoke $(or $(LANE),all)

bench: build
> @for i in 1 2 3 4 5; do \
      $(JAVA) -cp $(CORE_CLASSES):$(NULL_CLASSES):$(GL_CLASSES):$(VK_CLASSES):$(TEST_CLASSES) \
        dev.auralite.render.tests.RunAll --receipts 2>&1 | grep -E '^(TOTAL|RESULT)'; \
    done

# Usage: make packlint PACK=path/to/pack [STRICT=1] [NULL_DEVICE=1]
packlint: build
> @test -n "$(PACK)" || { echo "usage: make packlint PACK=path/to/pack [STRICT=1] [NULL_DEVICE=1]"; exit 2; }
> $(JAVA) -cp $(CORE_CLASSES):$(NULL_CLASSES):$(TOOLS_CLASSES) \
>       dev.auralite.render.tools.packlint.PackLint $(PACK) \
>       $(if $(STRICT),--strict,) $(if $(NULL_DEVICE),--null-device,)

# Replays the host gate and compares its receipts with docs/STATUS.md and README.md.
drift-check: build
> $(JAVA) -cp $(CORE_CLASSES):$(NULL_CLASSES):$(GL_CLASSES):$(VK_CLASSES):$(TEST_CLASSES):$(TOOLS_CLASSES) \
>       dev.auralite.render.tools.drift.DriftCheck

clean:
> rm -rf $(BUILD)
