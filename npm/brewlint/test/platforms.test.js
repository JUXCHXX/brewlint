'use strict';

/**
 * Tests for the launcher.
 *
 * <p>These run under {@code node --test}, so they need no test framework and no dependency, which
 * matters for a package whose entire job is to have as few moving parts as possible. The heavier
 * end-to-end test, which installs from tarballs and runs the real binary, is
 * {@code npm/scripts/test-install.mjs}.
 */

const assert = require('node:assert');
const { existsSync, readFileSync } = require('node:fs');
const { join, resolve } = require('node:path');
const { test } = require('node:test');

const {
  BINARIES,
  PREFIX,
  binaryFor,
  binaryPackageName,
  platformKey,
  supportedSummary,
} = require('../lib/platforms.js');

test('the platform key is node platform-arch', () => {
  assert.strictEqual(platformKey('darwin', 'arm64'), 'darwin-arm64');
  assert.strictEqual(platformKey('linux', 'x64'), 'linux-x64');
  assert.strictEqual(platformKey('win32', 'x64'), 'win32-x64');
});

test('npm package names are ours, not node platform names', () => {
  // The bug this guards against: deriving the package name from the node platform gives
  // brewlint-darwin-arm64, which is not a package that exists. The install succeeds and the
  // shim then fails, which is a miserable thing to debug.
  assert.strictEqual(binaryPackageName('darwin', 'arm64'), 'brewlint-macos-arm64');
  assert.strictEqual(binaryPackageName('linux', 'x64'), 'brewlint-linux-x64');
  assert.strictEqual(binaryPackageName('win32', 'x64'), 'brewlint-win-x64');
});

test('the package names are unscoped, because a user account cannot own one', () => {
  // An npm user account owns its own @username scope and nothing else. A scope like @brewlint
  // has to be created as an organisation, which requires a verified email address on the
  // account. This is not a style preference: the first release attempt failed on exactly this.
  for (const [key, entry] of Object.entries(BINARIES)) {
    assert.ok(
      !binaryPackageName(...key.split('-')).includes('@'),
      `${key} resolves to a scoped name, which cannot be published from a user account`,
    );
  }
  assert.ok(!PREFIX.startsWith('@'), 'the prefix must not be a scope');
});

test('the main package depends on exactly the packages the shim looks for', () => {
  // This is the drift that actually bites. The shim resolves one name, npm installs the names in
  // optionalDependencies, and if the two disagree the install succeeds, nothing is missing by
  // npm's reckoning, and the first command a user runs fails. It cannot be caught by testing the
  // shim or the manifest separately, only by putting them next to each other.
  const manifest = require(resolve(__dirname, '..', 'package.json'));
  const declared = Object.keys(manifest.optionalDependencies ?? {}).sort();
  const lookedFor = Object.keys(BINARIES)
    .map((key) => binaryPackageName(...key.split('-')))
    .sort();

  assert.deepStrictEqual(
    declared,
    lookedFor,
    'optionalDependencies and the shim platform table have drifted apart',
  );
  // The exact versions are load-bearing for the other half of this: the platform packages must
  // exist on the registry before the main one is published, and the release workflow relies on
  // every version agreeing.
  for (const [name, range] of Object.entries(manifest.optionalDependencies ?? {})) {
    assert.match(range, /^\d+\.\d+\.\d+$/, `${name} must be pinned to an exact version`);
  }
});

test('an unsupported platform resolves to null rather than throwing', () => {
  assert.strictEqual(binaryFor('linux', 'arm64'), null);
  assert.strictEqual(binaryFor('sunos', 'sparc'), null);
  assert.strictEqual(binaryPackageName('aix', 'ppc64'), null);
});

test('every shipped platform has both a package name and an executable', () => {
  for (const [key, entry] of Object.entries(BINARIES)) {
    assert.ok(entry.packageName, `${key} needs a packageName`);
    assert.ok(entry.executable, `${key} needs an executable`);
    assert.ok(entry.description, `${key} needs a description`);
    assert.ok(entry.executable.startsWith('bin/'), `${key} executable must live under bin/`);
  }
});

test('executables are named after the tool, so the layout is predictable', () => {
  // The macOS path carries a .app bundle. Linux and Windows are one level deeper because jpackage
  // wraps the launcher in a directory named after the app on those platforms. The authoritative
  // check is the one against a real build below; this pins the shape.
  assert.strictEqual(BINARIES['darwin-arm64'].executable, 'bin/brewlint.app/Contents/MacOS/brewlint');
  assert.strictEqual(BINARIES['linux-x64'].executable, 'bin/brewlint/bin/brewlint');
  assert.strictEqual(BINARIES['win32-x64'].executable, 'bin/brewlint/brewlint.exe');
});

test('the supported summary lists every platform', () => {
  const summary = supportedSummary();
  for (const key of Object.keys(BINARIES)) {
    assert.ok(summary.includes(key), `summary should mention ${key}`);
  }
});

test('the package name matches what the assembler writes', () => {
  // The assembler names packages brewlint-<npmName>; this table must agree or npm installs a
  // package the shim never looks for.
  const expected = { 'darwin-arm64': 'macos-arm64', 'linux-x64': 'linux-x64', 'win32-x64': 'win-x64' };
  for (const [key, packageName] of Object.entries(expected)) {
    assert.strictEqual(BINARIES[key].packageName, packageName);
  }
});

// The jpackage app image layout is not stable across platforms: macOS produces a .app bundle, Linux
// produces a directory named after the app, Windows produces a directory with an .exe at its root.
// Hardcoding that layout is how a build goes green while producing a package with no binary in it,
// and the failure then lands on a user's machine at the first run.
const distDir = resolve(__dirname, '..', '..', '..', 'dist');

function recordedLauncherFor(platform) {
  const manifest = join(distDir, `launcher-${platform}.txt`);
  return existsSync(manifest) ? readFileSync(manifest, 'utf8').trim() : null;
}

test('the launcher paths match what the last build actually produced', (t) => {
  const platformKeys = {
    'darwin-arm64': 'macos-arm64',
    'linux-x64': 'linux-x64',
    'win32-x64': 'win-x64',
  };
  const checked = [];

  for (const [key, platform] of Object.entries(platformKeys)) {
    const recorded = recordedLauncherFor(platform);
    if (recorded === null) {
      continue;
    }
    checked.push(platform);
    assert.strictEqual(
      BINARIES[key].executable,
      `bin/${recorded}`,
      `lib/platforms.js says "${BINARIES[key].executable}" but the ${platform} build produced ` +
        `"bin/${recorded}". Update the table in lib/platforms.js.`,
    );
  }

  if (checked.length === 0) {
    t.skip('no app image has been built yet; run scripts/build-runtime.sh');
  } else {
    t.diagnostic(`checked against a real build of: ${checked.join(', ')}`);
  }
});

test('every executable path names a file under bin/', () => {
  for (const [key, entry] of Object.entries(BINARIES)) {
    assert.ok(
      entry.executable.startsWith('bin/'),
      `${key}: the executable must live under bin/ so the package contains one thing`,
    );
    assert.ok(
      entry.executable.length > 'bin/'.length,
      `${key}: the executable path must name a file`,
    );
  }
});
