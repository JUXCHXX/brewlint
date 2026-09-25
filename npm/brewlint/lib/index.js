'use strict';

/**
 * Programmatic entry point.
 *
 * <p>Exists for the VS Code extension in Hito 6, which needs the path to the binary to spawn it as
 * a subprocess and read its JSON output. Making that path a supported export means the extension does
 * not have to hardcode the same platform table the shim uses, and the two cannot drift.
 */

const path = require('node:path');

const { binaryFor, platformKey, supportedSummary } = require('./platforms.js');

/** Absolute path to the Brewlint binary for this machine. Throws if it is not installed. */
function binaryPath() {
  const entry = binaryFor();
  if (!entry) {
    throw new Error(
      `Brewlint has no binary for ${platformKey()}.\nSupported:\n${supportedSummary()}`,
    );
  }
  const packageRoot = path.dirname(require.resolve(`@brewlint/${entry.packageName}/package.json`));
  return path.join(packageRoot, entry.executable);
}

module.exports = { binaryPath };
