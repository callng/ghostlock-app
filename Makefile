# Root-level convenience wrapper.
#
# The native build itself lives in src/Makefile (the upstream layout) and is
# expected to be invoked from src/: `make -C src ghostlock`. This wrapper only
# forwards the historical root-level entry points (`make ghostlock`, `make`,
# `make clean`, ...) to it, so existing scripts and the Windows quick-start
# instructions keep working.
#
# Outputs are unchanged: the binary lands in build/native/ghostlock.
#
# Variables such as NDK_ROOT / API / TARGET_CONFIG are passed through by the
# recursive $(MAKE) without being re-declared here.
#
# Requirements: src/Makefile is a POSIX makefile -- it uses `mkdir -p`, `rm -f`
# and `uname -s`. It therefore needs a POSIX shell and coreutils on PATH. On
# Windows that means running make from Git Bash (MSYS), which is the environment
# upstream itself builds in; GnuWin32's make defaults to a bare `sh.exe` that
# does not exist there.

SRC_DIR := src

# If the user named no goal, fall back to upstream's default target (`all`).
FORWARD_GOALS := $(if $(MAKECMDGOALS),$(MAKECMDGOALS),all)

# Every goal the user names is matched by this catch-all and forwarded in a
# single recursive make, so `make ghostlock`, `make clean` and
# `make native-host-tests NDK_ROOT=...` all behave like `make -C src <goals>`.
#
# $(MAKE) is quoted because on Windows it can expand to a path containing
# spaces and parentheses (e.g. "C:/Program Files (x86)/GnuWin32/bin/make"),
# which the POSIX shell would otherwise try to parse as subshell syntax.
#
# NOTE: the goal names must NOT also be declared with an explicit `.PHONY:`
# here. A phony target is an explicit target with no recipe, and such a target
# shadows this pattern rule, so make would report "Nothing to be done" and
# exit 0 without ever delegating.
%:
	@"$(MAKE)" --no-print-directory -C $(SRC_DIR) $(FORWARD_GOALS)
