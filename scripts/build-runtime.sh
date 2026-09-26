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
# java.net.http   the HTTP client the Anthropic provider uses
# jdk.crypto.ec   the TLS cipher suites java.net.http needs for HTTPS
# java.xml        SAX and the XML parser, which the PDF renderer needs to parse its own HTML
# java.desktop   font metrics and font configuration, which PDFBox needs to measure and embed text
#
# The last two arrived with Hito 5 and were found the hard way. The packaged binary threw
# NoClassDefFoundError: org/xml/sax/SAXException on the first PDF render, while every test passed,
# because a Maven build has the whole JDK and a jlink image does not. A jlink image is closed at
# build time, so a missing module cannot be discovered at run time by anything that does not need it.
#
# That is why this list is verified here and not just documented: the last three lines of this file
# run every module check, and the build script also renders a real PDF from the packaged binary
# before declaring success. See verify_report_from_image below.
readonly RUNTIME_MODULES="java.base,java.logging,java.net.http,jdk.crypto.ec,java.xml,java.desktop"

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

# Locates the launcher inside the app image.
#
# The layout is NOT stable across platforms or even across jpackage versions, and hardcoding it is a
# bug that only shows up on a platform you do not own. macOS produces a .app bundle, Linux produces a
# directory named after the app, Windows produces a directory with an .exe at its root. So the
# candidates are tried in order and the first one that exists wins.
#
# The discovered path is what the npm assembler and the CI assertions read, via
# dist/launcher-<target>.txt, so there is exactly one source of truth and a layout change cannot make
# two files disagree.
find_launcher() {
  local root="$1" target="$2"
  local candidates=(
    "${APP_NAME}/bin/${APP_NAME}"                    # linux
    "${APP_NAME}/${APP_NAME}.exe"                     # windows
    "${APP_NAME}.app/Contents/MacOS/${APP_NAME}"      # macos
    "bin/${APP_NAME}"                                 # flat, older layouts
    "${APP_NAME}.exe"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [ -x "${root}/${candidate}" ]; then
      echo "${candidate}"
      return 0
    fi
  done
  # Nothing matched. Show what is actually there, because a bare "file not found" on a layout nobody
  # expected wastes the next twenty minutes.
  echo "build-runtime: could not find the launcher in ${root}" >&2
  echo "  looked for:" >&2
  printf '    %s\n' "${candidates[@]}" >&2
  echo "  what is actually there:" >&2
  find "${root}" -maxdepth 3 -type f -perm -u+x 2>/dev/null | head -20 | sed 's/^/    /' >&2
  return 1
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
LAUNCHER_RELATIVE="$(find_launcher "${APP_IMAGE_DIR}" "${TARGET}")"
LAUNCHER="${APP_IMAGE_DIR}/${LAUNCHER_RELATIVE}"

# Record where the launcher ended up. The npm assembler and CI read this, so the app image layout
# is defined in exactly one place.
echo "${LAUNCHER_RELATIVE}" > "${DIST_DIR}/launcher-${TARGET}.txt"
echo "    launcher: ${LAUNCHER_RELATIVE}"

MODULES_FILE="$(find "${APP_IMAGE_DIR}" -name modules -path '*/lib/*' -print -quit)"
ACTUAL_MODULES="$(jimage list "${MODULES_FILE}" 2>/dev/null \
  | grep '^Module: ' | sed 's/Module: //' | sort | tr '\n' ' ')"
echo "    modules present: ${ACTUAL_MODULES}"

for required in java.base java.logging java.net.http jdk.crypto.ec java.xml java.desktop; do
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

# Every optional feature has to be exercised from the packaged binary, because a jlink image is
# closed at build time and a missing module only shows up when the code path that needs it runs.
# The alternative is finding out from a user.
echo "==> Verifying the JSON report from the packaged binary"
JSON_PROBE="$(env -i PATH=/usr/bin:/bin HOME="${HOME:-/tmp}" "${LAUNCHER}" \
  scan --path "${SCRIPT_DIR}/../fixtures" --format json --fail-on none 2>/dev/null || true)"
case "${JSON_PROBE}" in
  *'"findings"'*)
    echo "    json report: ok"
    ;;
  *)
    echo "build-runtime: the packaged binary could not produce a JSON report." >&2
    echo "  A module it needs is probably missing from RUNTIME_MODULES." >&2
    exit 1
    ;;
esac

echo "==> Verifying the PDF report from the packaged binary"
PDF_PROBE="$(mktemp -d)/probe.pdf"
env -i PATH=/usr/bin:/bin HOME="${HOME:-/tmp}" "${LAUNCHER}" \
  scan --path "${SCRIPT_DIR}/../fixtures" --format pdf --output "${PDF_PROBE}" --fail-on none \
  >/dev/null 2>&1 || true
if [ -s "${PDF_PROBE}" ] && [ "$(head -c 5 "${PDF_PROBE}")" = "%PDF-" ]; then
  echo "    pdf report: ok ($(du -h "${PDF_PROBE}" | cut -f1))"
else
  echo "build-runtime: the packaged binary could not produce a PDF." >&2
  echo "  PDF rendering needs java.xml and java.desktop, which a jlink image does not carry by" >&2
  echo "  default. Check RUNTIME_MODULES at the top of this script." >&2
  rm -f "${PDF_PROBE}"
  exit 1
fi
rm -f "${PDF_PROBE}"

# The launcher must not need a system Java. Running it with an empty environment is the only honest
# way to check, because any check that still sees JAVA_HOME proves nothing.
echo "==> Verifying the launcher runs with no Java on PATH"
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
