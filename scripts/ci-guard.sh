#!/usr/bin/env bash
#
# Runs first in every CI job, and turns "a step silently did nothing" into a red build.
#
# WHY THIS EXISTS
# On the Windows runner, `Run ./scripts/build-runtime.sh` under PowerShell resolved the relative
# path against the working directory instead of $PATH, found no script, and the step exited 0 with
# no output at all. Nothing failed. Three steps later the job failed with "no platform packages were
# assembled", pointing at a completely different file than the one that had actually failed.
#
# The general lesson is worse than the specific bug: a CI step that produces no output and succeeds
# is indistinguishable from a step that did its job quietly. `require_output` is the fix, and it has
# to be applied to the steps where silence would be a lie.
#
# Usage:
#   source scripts/ci-guard.sh
#   require_output "maven-wrapper.properties" "the Maven wrapper was not committed"
#   require_success_marker "==> Built" dist/launcher-*.txt "the runtime image was not built"

set -euo pipefail

# Prints to stderr, because a guard that writes to stdout can corrupt --format json output.
guard_error() {
  echo "::error::$1" >&2
  if [ -n "${2:-}" ]; then
    echo "  $2" >&2
  fi
}

# Fails unless at least one of the given paths exists.
#
# @param description what was being produced, for the error message
require_output() {
  local description="$1"
  shift
  local found=0 candidate
  for candidate in "$@"; do
    if compgen -G "$candidate" >/dev/null 2>&1 || [ -e "$candidate" ]; then
      found=1
      break
    fi
  done
  if [ "$found" -eq 0 ]; then
    guard_error "${description}: no output was produced" "expected one of: $*"
    return 1
  fi
}

# Fails unless one of the given files contains a marker string.
#
# Used to assert a long step actually ran, rather than trusting its exit code: a script that fails
# to find its own arguments can still exit 0.
require_success_marker() {
  local marker="$1"
  local description="$2"
  shift 2
  local file
  for file in "$@"; do
    if [ -f "$file" ] && grep -qF "$marker" "$file" 2>/dev/null; then
      return 0
    fi
  done
  guard_error "${description}" "no file contained the marker \"${marker}\": $*"
  return 1
}

# Fails when a path exists and is not empty, for steps whose success is the absence of something.
require_absent() {
  local description="$1"
  shift
  local candidate
  for candidate in "$@"; do
    if compgen -G "$candidate" >/dev/null 2>&1 || [ -e "$candidate" ]; then
      guard_error "${description}" "unexpectedly found: $candidate"
      return 1
    fi
  done
}
