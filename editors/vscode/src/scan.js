'use strict';

/**
 * Running the binary and reading its answer.
 *
 * <p>No `require('vscode')` here either. The spawn is the part with a contract worth pinning down
 * independently of the editor, and keeping it free of the API means it is tested against the real
 * binary rather than against a mock that agrees with whatever the test assumed.
 *
 * <p>The exit codes are a contract, and the one that gets mishandled is 1. Brewlint exits 1 when it
 * found something over the threshold, which for a linter is the ordinary successful outcome. Code
 * that treats a non-zero exit as a failure throws away every finding the tool just produced and
 * shows the user an error instead of their code. Only 2 means Brewlint could not run.
 */

const { spawn } = require('node:child_process');

const { parseReportText } = require('./report.js');

/** Brewlint's exit codes. 0 clean, 1 findings over the threshold, 2 could not run. */
const EXIT_CLEAN = 0;
const EXIT_FINDINGS = 1;
const EXIT_ERROR = 2;

/** Resolves the binary, preferring a path the user configured over the bundled one. */
function resolveBinary(configuredPath) {
  if (typeof configuredPath === 'string' && configuredPath.trim().length > 0) {
    return { path: configuredPath.trim() };
  }
  // Required lazily and defensively. The platform package is an optionalDependency, so on a
  // machine where npm skipped it this throws, and the right behaviour is a message in the Problems
  // panel rather than an extension that fails to activate and takes the rest of the panel with it.
  try {
    return { path: require('brewlint').binaryPath() };
  } catch (error) {
    return { error: error.message };
  }
}

/**
 * Scans a directory once.
 *
 * @param {object} options
 * @param {string} options.binary      absolute path to the brewlint executable
 * @param {string} options.cwd         the project directory to scan
 * @param {AbortSignal} [options.signal] cancels the scan and kills the process
 * @param {number} [options.timeoutMillis]
 * @returns {Promise<{ok: true, report: object} | {ok: false, reason: string, detail?: string}>}
 */
function scan({ binary, cwd, signal, timeoutMillis = 120000 }) {
  return new Promise((resolve) => {
    // --fail-on NONE, and deliberately no equivalent of --max-findings. The threshold is a CI
    // concept: in an editor the user wants to see every finding, whatever its severity. Setting it
    // to NONE also makes the exit code stop carrying information the JSON already has, and a second
    // channel that can disagree with the first is a channel that eventually will.
    const args = ['scan', '--path', cwd, '--format', 'json', '--fail-on', 'NONE', '--no-color'];

    let settled = false;
    let stdout = '';
    let stderr = '';
    let timedOut = false;

    const child = spawn(binary, args, {
      cwd,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill('SIGKILL');
    }, timeoutMillis);

    const onAbort = () => child.kill('SIGKILL');
    if (signal) {
      if (signal.aborted) {
        clearTimeout(timer);
        resolve({ ok: false, reason: 'cancelled' });
        return;
      }
      signal.addEventListener('abort', onAbort, { once: true });
    }

    // Bounded buffers. The JSON is small, but a child writing to a pipe nobody reads blocks
    // forever, and a linter that hangs the editor is worse than one that fails loudly.
    const CAP = 64 * 1024 * 1024;
    child.stdout.on('data', (chunk) => {
      if (stdout.length < CAP) stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      if (stderr.length < 8192) stderr += chunk;
    });

    const finish = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (signal) signal.removeEventListener('abort', onAbort);
      resolve(result);
    };

    child.on('error', (error) => {
      finish({
        ok: false,
        reason: `could not run Brewlint: ${error.message}`,
        detail: binary,
      });
    });

    child.on('close', (code, signalName) => {
      if (timedOut) {
        finish({
          ok: false,
          reason: `Brewlint did not finish within ${Math.round(timeoutMillis / 1000)}s`,
          detail: 'Raise brewlint.scanTimeout if your project is genuinely that large.',
        });
        return;
      }
      if (signalName) {
        finish({ ok: false, reason: 'cancelled' });
        return;
      }
      if (code !== EXIT_CLEAN && code !== EXIT_FINDINGS) {
        // Exit 2 is the documented "could not run". Anything else is something neither of us
        // predicted, so the exit code is reported rather than guessed at.
        finish({
          ok: false,
          reason: `Brewlint could not analyse this project (exit ${code})`,
          detail: (stderr || '').trim() || undefined,
        });
        return;
      }
      // Both 0 and 1 mean the tool ran. Whether anything was found is in the JSON, not the code.
      const parsed = parseReportText(stdout);
      finish(parsed.ok ? { ok: true, report: parsed.report } : parsed);
    });
  });
}

module.exports = { EXIT_CLEAN, EXIT_FINDINGS, EXIT_ERROR, resolveBinary, scan };
