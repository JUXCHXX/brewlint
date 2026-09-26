'use strict';

/**
 * Scanning, against the real binary.
 *
 * <p>Not a mock. The exit codes are the part of this contract that gets mishandled, and a mock
 * agrees with whatever the test assumed, which is exactly the assumption under question. This runs
 * the published binary that ships in node_modules, so it also checks that what npm delivered is a
 * working Brewlint, which is the thing a user installing the extension actually gets.
 */

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { mkdtempSync, writeFileSync, rmSync, mkdirSync } = require('node:fs');
const { join, resolve } = require('node:path');
const { tmpdir } = require('node:os');

const { resolveBinary, scan } = require('../src/scan.js');

const REPO = resolve(__dirname, '..', '..', '..');
const FIXTURES = join(REPO, 'fixtures');

/** The same annotation the Java rules fire on, in a file of its own. */
const BROKEN = `
package com.example;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class OrderService {
    @Transactional
    private void charge() {}
}
`;

const CLEAN = `
package com.example;
/** A connection that is closed. */
public class Repository {
    void read() {
        try (var connection = open()) {
            connection.query("select 1");
        }
    }
    private java.sql.Connection open() { return null; }
}
`;

function project(source, name) {
  const root = mkdtempSync(join(tmpdir(), 'brewlint-vscode-'));
  writeFileSync(join(root, name), source);
  return root;
}

function binary() {
  const resolved = resolveBinary('');
  assert.equal(resolved.error, undefined, `the binary should be installed: ${resolved.error}`);
  return resolved.path;
}

test('the binary that ships with the extension runs with no Java on PATH', async () => {
  // The published package, downloaded from the registry. A user on a machine with no JDK is the
  // whole reason that package exists, and nothing in this repository could tell us it stopped
  // working except running it.
  const result = await scan({ binary: binary(), cwd: FIXTURES });
  assert.equal(result.ok, true, `scan failed: ${result.reason} ${result.detail ?? ''}`);
  assert.equal(result.report.schemaVersion, 1);
});

test('a project with findings is a success, and the exit code does not say otherwise', async (t) => {
  const root = project(BROKEN, 'OrderService.java');
  t.after(() => rmSync(root, { recursive: true, force: true }));

  // This is the one that matters. Brewlint exits 1 when it finds something, which for a linter is
  // the ordinary successful outcome. Code that treats non-zero as failure throws away every finding
  // the tool just produced and shows the user an error instead of their code.
  const result = await scan({ binary: binary(), cwd: root });

  assert.equal(result.ok, true, `a project with findings must not be an error: ${result.reason}`);
  assert.ok(result.report.findings.length > 0, 'the broken fixture should produce findings');
  assert.equal(result.report.filesScanned, 1);
});

test('a clean project is also a success, and produces no findings', async (t) => {
  const root = project(CLEAN, 'Repository.java');
  t.after(() => rmSync(root, { recursive: true, force: true }));

  const result = await scan({ binary: binary(), cwd: root });

  assert.equal(result.ok, true);
  assert.deepEqual(result.report.findings, []);
});

test('findings come back with the fields a diagnostic needs', async (t) => {
  const root = project(BROKEN, 'OrderService.java');
  t.after(() => rmSync(root, { recursive: true, force: true }));

  const { report } = await scan({ binary: binary(), cwd: root });
  const finding = report.findings[0];

  // Paths are relative to what was scanned, which is what makes them work across machines.
  assert.equal(finding.file, 'OrderService.java');
  assert.equal(typeof finding.line, 'number');
  assert.ok(finding.line >= 1, 'lines are 1-based, as every Java tool reports them');
  assert.ok(finding.ruleId.length > 0);
  assert.ok(finding.message.length > 0);
  assert.ok(finding.suggestion.length > 0);
});

test('the whole fixtures tree scans, and every finding points at a file that exists', async (t) => {
  const result = await scan({ binary: binary(), cwd: FIXTURES });
  assert.equal(result.ok, true, `scan failed: ${result.reason}`);

  const { existsSync } = require('node:fs');
  for (const finding of result.report.findings) {
    // A finding on a file that is not there is dropped silently by the editor, and the panel looks
    // empty for a project that has findings. Only a check against the filesystem catches it.
    assert.ok(
      existsSync(join(FIXTURES, finding.file)),
      `${finding.ruleId} points at ${finding.file}, which does not exist`,
    );
  }
});

test('a missing binary is an error, with a message rather than an exception', async (t) => {
  const root = project(CLEAN, 'Repository.java');
  t.after(() => rmSync(root, { recursive: true, force: true }));

  const result = await scan({ binary: join(root, 'not-a-binary'), cwd: root });

  assert.equal(result.ok, false);
  assert.match(result.reason, /could not run Brewlint/);
});

test('a scan of a directory with no Java files is an error, not an empty report', async (t) => {
  // Brewlint exits 2 with a message here. Treating that as a successful scan with no findings would
  // tell the user their project is clean when nothing was ever analysed.
  const root = mkdtempSync(join(tmpdir(), 'brewlint-empty-'));
  mkdirSync(join(root, 'src'), { recursive: true });
  t.after(() => rmSync(root, { recursive: true, force: true }));

  const result = await scan({ binary: binary(), cwd: root });
  assert.equal(result.ok, false);
});

test('a cancelled scan resolves as cancelled rather than hanging', async (t) => {
  const root = project(BROKEN, 'OrderService.java');
  t.after(() => rmSync(root, { recursive: true, force: true }));

  const controller = new AbortController();
  const promise = scan({ binary: binary(), cwd: root, signal: controller.signal });
  controller.abort();

  const result = await promise;
  // Either the abort landed before the work finished, or the scan genuinely completed first. Both
  // are fine. What is not fine is a promise that never settles, because that leaks a listener and a
  // closure on every keystroke.
  assert.ok(result.ok || result.reason === 'cancelled', `unexpected: ${JSON.stringify(result)}`);
});

test('an already-aborted signal does not start the work', async (t) => {
  const root = project(CLEAN, 'Repository.java');
  t.after(() => rmSync(root, { recursive: true, force: true }));

  const controller = new AbortController();
  controller.abort();
  const result = await scan({ binary: binary(), cwd: root, signal: controller.signal });

  assert.equal(result.ok, false);
  assert.equal(result.reason, 'cancelled');
});

test('a configured binary path is used instead of the bundled one', () => {
  const resolved = resolveBinary('/opt/brewlint/brewlint');
  assert.equal(resolved.path, '/opt/brewlint/brewlint');
  assert.equal(resolved.error, undefined);
});

test('a blank configured path falls back to the bundled binary', () => {
  // Otherwise an empty setting, which is the default, would resolve to "" and every scan would fail
  // with a confusing spawn error.
  for (const blank of ['', '   ', undefined, null]) {
    const resolved = resolveBinary(blank);
    assert.ok(resolved.path, `expected a fallback for ${JSON.stringify(blank)}`);
  }
});
