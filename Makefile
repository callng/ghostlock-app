API ?= 35

# GnuWin32 make ships no rm.exe; route clean through cmd's del on Windows.
ifeq ($(OS),Windows_NT)
  RM := cmd /c del /f /q
else
  RM := rm -f
endif

# Auto-detect NDK
ifeq ($(OS),Windows_NT)
  PREBUILT := windows-x86_64
  NDK_CANDIDATES := \
    $(subst \,/,$(wildcard $(subst \,/,$(LOCALAPPDATA))/Android/Sdk/ndk/*)) \
    $(subst \,/,$(wildcard $(subst \,/,$(ANDROID_HOME))/ndk/*)) \
    $(subst \,/,$(wildcard D:/AndroidSDK/ndk/*))
  # Only NDKs that ship the aarch64-linux-android$(API) clang wrapper qualify;
  # pick the newest such NDK (NDK directory names sort by version).
  NDK_WITH_API := $(foreach d,$(NDK_CANDIDATES),$(if $(wildcard $(d)/toolchains/llvm/prebuilt/$(PREBUILT)/bin/aarch64-linux-android$(API)-clang.cmd),$(d)))
  NDK_ROOT ?= $(if $(NDK_WITH_API),$(lastword $(sort $(NDK_WITH_API))),$(error No NDK supporting aarch64-linux-android$(API) found under LOCALAPPDATA/ANDROID_HOME. Install NDK r28+ or set NDK_ROOT=...))
  CLANG_BASE := aarch64-linux-android$(API)-clang
  NDK_CC := $(NDK_ROOT)/toolchains/llvm/prebuilt/$(PREBUILT)/bin/$(CLANG_BASE).cmd
else
  NDK_ROOT ?= $(or $(ANDROID_NDK_HOME),$(ANDROID_NDK_ROOT))
  PREBUILT := linux-x86_64
  NDK_CC := $(NDK_ROOT)/toolchains/llvm/prebuilt/$(PREBUILT)/bin/aarch64-linux-android$(API)-clang
endif

SRCS := \
  src/core/main.c \
  src/core/util.c \
  src/core/slide.c \
  src/core/fops.c \
  src/core/pipe.c \
  src/core/root.c

# Headers are inputs too: changing offsets.h/target.h must rebuild.
HDRS := $(wildcard src/core/*.h) $(wildcard src/devices/*.h) \
        $(wildcard src/devices/*/offsets.h)

# Device offsets are selected at runtime from uname -r.
TARGET_CONFIG ?= target.h

CFLAGS = -O2 -Wall -Wno-unused-parameter -Wno-sign-compare -Wno-unused-function \
  -Isrc/core -Isrc/devices -DTARGET_CONFIG_H=\"$(TARGET_CONFIG)\"
LDFLAGS := -fPIE -pie -pthread

.PHONY: all clean product

all: ghostlock

ghostlock: $(SRCS) $(HDRS)
	@echo "Using NDK compiler: $(NDK_CC)"
	@echo "Target config: $(TARGET_CONFIG)"
	$(NDK_CC) $(CFLAGS) $(LDFLAGS) $(SRCS) -o ghostlock

product: ghostlock
	@echo "=== ghostlock binary ready: ./ghostlock ==="
	@echo "构建 APK: .\gradlew.bat :app:assembleDebug"

clean:
	-$(RM) ghostlock 2>nul
