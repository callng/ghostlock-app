# Root-level convenience wrapper.
#
# The native build itself lives in src/Makefile (the upstream layout) and is
# expected to be invoked from src/: `make -C src ghostlock`. This wrapper only
# forwards the historical root-level entry points (`make ghostlock`, `make`,
# `make clean`, ...) to it, so existing scripts and the Windows quick-start
# instructions keep working.
#
# Outputs are unchanged: the binary lands in build/native/ghostlock.

SRC_DIR := src

# Every goal is delegated verbatim, so `make ghostlock`, `make product`,
# `make clean`, `make native-host-tests`, ... behave exactly as
# `make -C src <goal>`; variables such as NDK_ROOT / API / TARGET_CONFIG are
# passed through automatically by the recursive $(MAKE).
%:
	@$(MAKE) -C $(SRC_DIR) $@

.DEFAULT_GOAL := ghostlock

.PHONY: all ghostlock product clean native-host-tests
