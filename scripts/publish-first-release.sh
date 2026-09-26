#!/usr/bin/env bash
#
# Publishes the very first npm release by hand, from a machine where a human can answer the
# two-factor prompt. After this runs once, the GitHub workflow takes over via OIDC and this
# script is never needed again.
#
# WHY THIS EXISTS, AND WHY IT CANNOT BE IN CI
# The account has 2FA enabled, and npm no longer allows direct publishing with a token that
# bypasses it:
#
#   npm warn  npm tokens that bypass 2FA are being restricted for account changes and direct
#              publishing.
#   npm error code EOTP
#   npm error This operation requires a one-time password.
#
# A one-time password is not a solution. It rotates every 30 seconds, and publishing four
# packages takes minutes, so a token containing one would be expired before the second package
# and there would be no way to refresh it mid-run. This is the exact problem npm's trusted
# publishing exists to solve, and it cannot solve the bootstrap: npm requires the package to
# already exist on the registry before a trusted publisher can be configured for it. Creating it
# therefore has to happen once, here, with a human present.
#
# The tarballs are the ones CI built on their own platforms, downloaded from the release run.
# Nothing is rebuilt here, so the binaries are the same bytes that were verified on Linux, macOS
# and Windows.
#
# Usage:
#   bash scripts/publish-first-release.sh
#
# Order matters and is not cosmetic. The main package's optionalDependencies name the three
# platform packages at exact versions. Publishing it first would give every user an install that
# silently downloads nothing and fails at the first command.

set -euo pipefail

TARBALL_DIR="${1:-/tmp/brewlint-release/tarballs}"
VERSION="$(node -p "require('$PWD/npm/brewlint/package.json').version")"

echo "==> Releasing brewlint $VERSION from $TARBALL_DIR"

if [ ! -d "$TARBALL_DIR" ]; then
  echo "No such directory: $TARBALL_DIR" >&2
  echo "Download the platform tarballs from the release run first." >&2
  exit 1
fi

cd "$TARBALL_DIR"

# Proved before publishing, not after. `npm publish` takes a package specifier rather than a
# filename, and a relative path containing a slash is GitHub shorthand: the first attempt at this
# release tried to `git clone ssh://git@github.com/tarballs/brewlint-linux-x64-0.1.0.tgz`. Every
# path here is absolute so it cannot be read as anything but a file.
expected=("brewlint-linux-x64-$VERSION.tgz"
          "brewlint-macos-arm64-$VERSION.tgz"
          "brewlint-win-x64-$VERSION.tgz"
          "brewlint-$VERSION.tgz")

echo
echo "==> Checking that all four tarballs are present and publishable"
for name in "${expected[@]}"; do
  if [ ! -f "$PWD/$name" ]; then
    echo "  MISSING: $name" >&2
    exit 1
  fi
  # --dry-run resolves the specifier and prints the manifest without contacting the registry.
  identity=$(npm publish "$PWD/$name" --dry-run --access public 2>&1 |
             grep -E "^npm notice (name|version):" | tr '\n' ' ' | tr -s ' ')
  echo "  $name ->$identity"
done

echo
echo "==> Signing in. npm will open a browser for the two-factor prompt."
npm login

echo
echo "==> Publishing the three platform packages first"
for name in "${expected[@]:0:3}"; do
  echo "--- $name"
  # No --otp. Interactively, so the code stays out of the shell history and out of this script.
  npm publish "$PWD/$name" --access public
done

echo
echo "==> Publishing the main package last"
echo "--- brewlint-$VERSION.tgz"
npm publish "$PWD/brewlint-$VERSION.tgz" --access public

# Verify from the registry in a directory that has never seen this repository. Anything less
# proves nothing about what a user actually gets.
echo
echo "==> Installing from the registry exactly as a user would"
WORKDIR="$(mktemp -d)"
npm install --prefix "$WORKDIR" --no-audit --no-fund "brewlint@$VERSION"
"$WORKDIR/node_modules/.bin/brewlint" --version

echo
echo "==> The binary runs on this platform"
"$WORKDIR/node_modules/.bin/brewlint" scan --path "$PWD/fixtures" --no-color --format pdf \
  --output "$WORKDIR/report.pdf" --fail-on none
head -c 5 "$WORKDIR/report.pdf" | grep -q '%PDF-' && echo "  PDF report: ok"

echo
echo "Published. Two things left:"
echo "  1. On npmjs.com, for each of the four packages, add a Trusted Publisher:"
echo "       Provider: GitHub Actions"
echo "       Organization or user: JUXCHXX"
echo "       Repository:           brewlint"
echo "       Workflow filename:    release.yml"
echo "       Environment name:     npm"
echo "       Allowed actions:      npm publish"
echo "  2. Then the NPM_TOKEN secret can be deleted, and the NODE_AUTH_TOKEN line removed"
echo "     from the publish job. From v0.2.0 the release needs no credential at all."
