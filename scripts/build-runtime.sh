#!/usr/bin/env bash
#
# Builds a self-contained Brewlint binary for the current platform.
#
# The result is a native launcher plus a jlink runtime image, with no dependency on any Java
# installation. That is the whole point of Hito 3: a user should not have to know Brewlint is
# written in Java, and should not have to install a JDK to run it.
#
# WHY A SCRIPT AND NOT A MAVEN PLUGIN
# jpackage is a JDK binary, not a lifecycle phase. Wrapping it in a plugin would add a dependency
# to review and would hide exactly the command that determines what ships. A script is the honest
# version: what you read is what runs, in CI and on a laptop alike.
#
# WHY YOU CANNOT CROSS-COMPILE
# jpackage links a launcher for the host OS and architecture. A macOS arm64 machine can produce a
# macOS arm64 binary and nothing else. Every other target has to be built on that platform, which
# is what the CI matrix in .github/workflows/release.yml is for. The script therefore refuses a
# target that does not match the host, instead of failing later in a confusing way.
#
# Usage:
#   scripts/build-runtime.sh [target]
#
# Examples:
#   scripts/build-runtime.sh              # auto-detect the host target
#   scripts/build-runtime.sh linux-x64    # fail loudly if you are not on Linux x64

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly DIST_DIR="${PROJECT_ROOT}/dist"

# The version of the npm package, and the version of the macOS bundle.
# These are deliberately different numbers. npm is happy with 0.x, which is what a pre-1.0 tool
# should say, but a macOS CFBundleShortVersionString must start at 1 or jpackage rejects it with
# "The first number in an app-version cannot be zero or negative". One variable, two vocabularies.
readonly PACKAGE_VERSION="${PACKAGE_VERSION:-0.1.0}"
readonly BUNDLE_VERSION="${BUNDLE_VERSION:-1.0.0}"

# The modules baked into the runtime.
#
# java.base       the language itself
# java.logging    java.util.logging, which the engine uses to report unparseable files
# java.net.http   the HTTP client Hito 4's Anthropic provider will need
# jdk.crypto.ec   the TLS cipher suites java.net.http needs for HTTPS
#
# java.net.http and jdk.crypto.ec are here for a feature that does not exist yet, and that is
# deliberate. A jlink image is closed at build time. If they were left out, the packaged binary
# would work perfectly until Hito 4 shipped, and then every npm user would get a
# NoClassDefFoundError that nobody running ./mvnw would ever reproduce. The list is verified at the
# end of this script, so removing a module by accident fails here rather than in a user's terminal.
readonly RUNTIME_MODULES="java.base,java.logging,java.net.http,jdk.crypto.ec"

readonly MAIN_CLASS="io.github.brewlint.cli.BrewlintCli"
readonly APP_NAME="brewlint"

# Maps a Node-style "platform-arch" identifier to the triple jpackage expects.
host_target() {
  local os arch
  case "$(uname -s)" in
    Darwin) os="macos" ;;
    Linux)  os="linux" ;;
    MINGW*|MSYS*|CYGWIN*) os="windows" ;;
    *) echo "build-runtime: unsupported OS $(uname -s)" >&2; exit 1 ;;
  esac
  case "$(uname -m)" in
    arm64|aarch64) arch="aarch64" ;;
    x86_64|amd64)  arch="x64" ;;
    *) echo "build-runtime: unsupported architecture $(uname -m)" >&2; exit 1 ;;
  esac
  echo "${os}-${arch}"
}

npm_target() {
  case "$1" in
    macos-aarch64)   echo "macos-arm64" ;;
    linux-x64)       echo "linux-x64" ;;
    windows-x64)     echo "win-x64" ;;
    *) echo "build-runtime: unsupported target '$1'. Supported: macos-aarch64, linux-x64, windows-x64" >&2; exit 1 ;;
  esac
}

# Where the executable sits inside the app image, which differs per platform.
executable_path() {
  case "$1" in
    macos-aarch64) echo "${APP_NAME}.app/Contents/MacOS/${APP_NAME}" ;;
    linux-x64)     echo "bin/${APP_NAME}" ;;
    windows-x64)   echo "${APP_NAME}.exe" ;;
  esac
}

readonly REQUESTED_TARGET="${1:-$(host_target)}"
readonly TARGET="$(npm_target "${REQUESTED_TARGET}")"

if [[ "${REQUESTED_TARGET}" != "$(host_target)" ]]; then
  echo "build-runtime: jpackage cannot cross-compile." >&2
  echo "  host:    $(host_target)" >&2
  echo "  request: ${REQUESTED_TARGET}" >&2
  echo "Build ${REQUESTED_TARGET} on a ${REQUESTED_TARGET} machine, or let the CI matrix do it." >&2
  exit 1
fi

command -v jpackage >/dev/null || {
  echo "build-runtime: jpackage not found. A JDK 17 or newer is required to build." >&2
  exit 1
}

# Step 1: the runnable jar.
echo "==> Building the jar"
(cd "${PROJECT_ROOT}" && ./mvnw --batch-mode -q -DskipTests package)

readonly JAR_NAME="brewlint-cli-${VERSION:-0.1.0-SNAPSHOT}.jar"
readonly JAR_PATH="${PROJECT_ROOT}/brewlint-cli/target/${JAR_NAME}"
[[ -f "${JAR_PATH}" ]] || {
  echo "build-runtime: expected ${JAR_PATH} to exist after packaging" >&2
  exit 1
}

# Step 2: stage only the jar.
#
# jpackage copies its whole --input directory into the image. Pointing it at the Maven target
# directory drags test-classes, surefire-reports and maven-status into the npm tarball, which is
# 4.6 MB of somebody else's build output in a package users download. A directory holding one file
# is the fix.
readonly STAGE_DIR="${PROJECT_ROOT}/build/staging/${TARGET}"
rm -rf "${STAGE_DIR}"
mkdir -p "${STAGE_DIR}"
cp "${JAR_PATH}" "${STAGE_DIR}/${JAR_NAME}"

# Step 3: the app image.
readonly APP_IMAGE_DIR="${DIST_DIR}/${TARGET}"
rm -rf "${APP_IMAGE_DIR}"
mkdir -p "${APP_IMAGE_DIR}"

echo "==> Packaging ${TARGET} (modules: ${RUNTIME_MODULES})"
jpackage \
  --type app-image \
  --name "${APP_NAME}" \
  --app-version "${BUNDLE_VERSION}" \
  --input "${STAGE_DIR}" \
  --main-jar "${JAR_NAME}" \
  --main-class "${MAIN_CLASS}" \
  --dest "${APP_IMAGE_DIR}" \
  --add-modules "${RUNTIME_MODULES}" \
  --vendor "Brewlint" \
  --description "Static analysis for Spring Boot anti-patterns" \
  >/dev/null

# Step 4: verify, because a silently broken image is worse than a failed build.
#
# A jlink image is closed at build time, so a missing module cannot be discovered at runtime by
# anything that does not need it. That is exactly the trap this check exists for: java.net.http is
# included for Hito 4, and if a future edit to RUNTIME_MODULES drops it, Hito 4 would fail for npm
# users only.
echo "==> Verifying the runtime image"
ACTUAL_MODULES="$(jimage list "$(find "${APP_IMAGE_DIR}" -name modules -path '*/lib/*' -print -quit)" 2>/dev/null \
  | grep '^Module: ' | sed 's/Module: //' | sort | tr '\n' ' ')"
echo "    modules present: ${ACTUAL_MODULES}"

for required in java.base java.logging java.net.http jdk.crypto.ec; do
  case " ${ACTUAL_MODULES} " in
    *" ${required} "*) ;;
    *)
      echo "build-runtime: module ${required} is missing from the image." >&2
      echo "  It was requested in RUNTIME_MODULES, so this means the runtime was built differently" >&2
      echo "  than this script expects. Do not ship this image." >&2
      exit 1
      ;;
  esac
done

# The launcher must not need a system Java. Running it with an empty environment is the only honest
# way to check, because any check that still sees JAVA_HOME proves nothing.
echo "==> Verifying the launcher runs with no Java on PATH"
LAUNCHER="${APP_IMAGE_DIR}/$(executable_path "${REQUESTED_TARGET}")"
[[ -x "${LAUNCHER}" ]] || {
  echo "build-runtime: expected an executable at ${LAUNCHER}" >&2
  exit 1
}

VERSION_OUTPUT="$(env -i PATH=/usr/bin:/bin HOME="${HOME:-/tmp}" "${LAUNCHER}" --version 2>&1)" || {
  echo "build-runtime: the launcher failed to run in a clean environment:" >&2
  echo "${VERSION_OUTPUT}" >&2
  exit 1
}
echo "    ${VERSION_OUTPUT}"

echo
echo "==> Built ${TARGET}"
echo "    ${APP_IMAGE_DIR}"
du -sh "${APP_IMAGE_DIR}" | sed 's/^/    size: /'
