#!/usr/bin/env node
'use strict';

/**
 * The `brewlint` command.
 *
 * <p>A launcher, and nothing else. All of the analysis lives in the native binary, which carries
 * its own Java runtime, so installing this package is enough to run Brewlint on a machine that has
 * never seen a JDK.
 *
 * <p>Two things it has to get right:
 *
 * <ol>
 *   <li><strong>Forward the exit code exactly.</strong> Brewlint's exit codes are a contract with
 *       CI: 0 clean, 1 findings over the threshold, 2 could not run. A launcher that collapses them
 *       to 0 or 1 silently turns a failing build green, and nobody finds out until a real bug ships.
 *       There is a test for exactly this.</li>
 *   <li><strong>Keep stdio inherited.</strong> The report detects whether stdout is a terminal in
 *       order to decide on colour, and a pipe between here and the binary would both lose that and
 *       swallow the child's exit status.</li>
 * </ol>
 */

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const { binaryFor, binaryPackageName, platformKey, supportedSummary } = require('../lib/platforms.js');

function fail(message, detail) {
  process.stderr.write(`brewlint: ${message}\n`);
  if (detail) {
    process.stderr.write(`${detail}\n`);
  }
  process.exit(2);
}

/** Where the binary is, or a message explaining why it is not there. */
function resolveBinary() {
  const key = platformKey();
  const entry = binaryFor();

  if (!entry) {
    fail(
      `unsupported platform: ${key}`,
      [
        'Brewlint ships binaries for:',
        supportedSummary(),
        '',
        `Building one for ${key} means running scripts/build-runtime.sh on that platform.`,
      ].join('\n'),
    );
  }

  const packageName = binaryPackageName();
  let packageRoot;
  try {
    packageRoot = path.dirname(require.resolve(`${packageName}/package.json`));
  } catch {
    fail(
      `the ${packageName} package is not installed`,
      [
        `That package holds the Brewlint binary for ${key} (${entry.description}) and should have`,
        'been installed automatically. It is an optionalDependency, so npm skips it when the',
        'platform does not match, when the install runs with --no-optional, or when the download',
        'failed.',
        '',
        'Try:',
        '  npm install -g brewlint --force',
        `  npm install -g ${packageName}`,
      ].join('\n'),
    );
  }

  const binary = path.join(packageRoot, entry.executable);
  if (!fs.existsSync(binary)) {
    fail(
      `the Brewlint binary is missing from ${packageName}`,
      `Expected it at:\n  ${binary}\n\nThe package looks damaged. Reinstalling it should fix this.`,
    );
  }
  return binary;
}

function main() {
  const binary = resolveBinary();

  const result = spawnSync(binary, process.argv.slice(2), {
    // Inherited, not piped: the report needs a real terminal to decide on colour, and a pipe
    // between here and the binary would break that and swallow the exit code.
    stdio: 'inherit',
    windowsHide: true,
  });

  if (result.error) {
    if (result.error.code === 'EACCES' || result.error.code === 'EPERM') {
      fail(`cannot execute ${binary}`, 'The file exists but is not executable.');
    }
    fail(`failed to run ${binary}: ${result.error.message}`);
  }

  if (result.signal) {
    // The child was killed by a signal. Do not report that as a successful run.
    process.exit(1);
  }

  // The whole contract: whatever the tool decided, the shell must see the same thing.
  process.exit(result.status === null ? 2 : result.status);
}

main();
