'use strict';

/**
 * Single source of truth for where the Brewlint executable lives on each platform.
 *
 * <p>This is the one piece of platform knowledge the shim has, and it exists as a data file rather
 * than as branching logic so that adding a target in Hito 3 is a change in one place.
 *
 * <p><strong>Two different naming schemes, and conflating them is a bug.</strong> The key is Node's
 * {@code process.platform}-{@code process.arch}, which is what npm uses to decide whether to
 * install an optional dependency. The package name is ours, and npm's own platform names are
 * "darwin", not "macos". Getting these mixed up produces a shim that looks for
 * {@code @brewlint/darwin-arm64} in an install that contains {@code @brewlint/macos-arm64}, which
 * is a confusing failure to debug because the install genuinely succeeded.
 */
const SCOPE = '@brewlint';

/**
 * Node platform-arch to the binary's location.
 *
 * @param packageName npm package holding the binary for this platform
 * @param executable   path to the binary, relative to that package's root
 * @param description  human-readable name for error messages
 */
const BINARIES = {
  'darwin-arm64': {
    packageName: 'macos-arm64',
    executable: 'bin/brewlint.app/Contents/MacOS/brewlint',
    description: 'macOS on Apple Silicon',
  },
  'linux-x64': {
    packageName: 'linux-x64',
    executable: 'bin/brewlint',
    description: 'Linux on x86-64',
  },
  'win32-x64': {
    packageName: 'win-x64',
    executable: 'bin/brewlint.exe',
    description: 'Windows on x86-64',
  },
};

/** The Node platform-arch key for a given platform and architecture. */
function platformKey(platform = process.platform, arch = process.arch) {
  return `${platform}-${arch}`;
}

/** Everything about this machine's binary, or null if we do not ship one. */
function binaryFor(platform = process.platform, arch = process.arch) {
  const key = platformKey(platform, arch);
  const entry = BINARIES[key];
  return entry ? { key, ...entry } : null;
}

/** The npm package that holds the binary for this machine, or null if we do not ship one. */
function binaryPackageName(platform = process.platform, arch = process.arch) {
  const entry = binaryFor(platform, arch);
  return entry ? `${SCOPE}/${entry.packageName}` : null;
}

/** A one-line-per-platform summary, for error messages that have to say what is supported. */
function supportedSummary() {
  return Object.entries(BINARIES)
    .map(([key, entry]) => `  ${key.padEnd(14)} ${entry.description}`)
    .join('\n');
}

module.exports = { SCOPE, BINARIES, platformKey, binaryFor, binaryPackageName, supportedSummary };
