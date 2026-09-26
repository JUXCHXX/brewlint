'use strict';

/**
 * Findings to diagnostics.
 *
 * <p>The line-number conversion is the thing being pinned down. A wrong conversion does not throw
 * and does not look wrong: the panel fills up, the squiggles are in plausible places, and every
 * squiggle is one line below the code it belongs to. Nothing in the UI says that is wrong, which is
 * why it is worth a test that reads the real file and checks the highlighted text.
 */

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join, resolve } = require('node:path');

const { MAX_HIGHLIGHTED_LINES, toDiagnostic, byFile } = require('../src/diagnostics.js');

const REPO = resolve(__dirname, '..', '..', '..');

function finding(overrides = {}) {
  return {
    ruleId: 'AOP001',
    category: 'spring-aop',
    source: 'RULE',
    severity: 'ERROR',
    file: 'src/main/java/com/example/broken/OrderService.java',
    line: 17,
    column: 5,
    endLine: 19,
    message: '@Transactional on a private method.',
    suggestion: 'Make the method public.',
    ...overrides,
  };
}

test('a 1-based line and column become 0-based, which is what VS Code wants', () => {
  const diagnostic = toDiagnostic(finding(), '/project');

  assert.equal(diagnostic.line, 16);
  assert.equal(diagnostic.column, 4);
});

test('the squiggle lands on the code the finding is about, checked against the real file', () => {
  // The end-to-end version of the test above. Reading the file and asserting on its text is the
  // only way to catch an off-by-one that is internally consistent, which is the dangerous kind.
  const root = join(REPO, 'fixtures');
  const relative = 'src/main/java/com/example/broken/OrderService.java';
  const lines = readFileSync(join(root, relative), 'utf8').split('\n');

  const diagnostic = toDiagnostic(finding({ file: relative }), root);
  const highlighted = lines[diagnostic.line];

  assert.equal(highlighted.trim(), '@Transactional');
});

test('a relative file path resolves against the scan root, not the editor working directory', () => {
  // The engine reports paths relative to what it was asked to scan. Resolving against process.cwd()
  // would put every diagnostic on a file that does not exist, which VS Code drops silently, and the
  // panel would be empty for a project that has findings.
  const diagnostic = toDiagnostic(finding(), '/some/project');
  assert.equal(diagnostic.file, '/some/project/src/main/java/com/example/broken/OrderService.java');
});

test('a multi-segment path is not flattened by a single path join', () => {
  const diagnostic = toDiagnostic(finding({ file: 'a/b/c/d/E.java' }), '/p');
  assert.equal(diagnostic.file, '/p/a/b/c/d/E.java');
});

test('severity maps to the diagnostic severity VS Code uses', () => {
  assert.equal(toDiagnostic(finding({ severity: 'ERROR' }), '/p').severity, 0);
  assert.equal(toDiagnostic(finding({ severity: 'WARNING' }), '/p').severity, 1);
  assert.equal(toDiagnostic(finding({ severity: 'INFO' }), '/p').severity, 2);
});

test('the message carries what is wrong and what to do about it', () => {
  // A diagnostic that says what is wrong and stops sends the user to the documentation. Both halves
  // belong in the text, because a hover is not a place people look for the next step.
  const diagnostic = toDiagnostic(finding(), '/p');
  assert.match(diagnostic.message, /@Transactional on a private method/);
  assert.match(diagnostic.message, /Make the method public/);
});

test('an AI finding says so in the message, not only in a field nobody reads', () => {
  const diagnostic = toDiagnostic(
    finding({ source: 'AI', ruleId: 'AI001', message: 'The cache is never invalidated.' }),
    '/p',
  );
  // A squiggle identical to a rule's presents a suggestion as a fact. The panel is the one place
  // where that distinction cannot be made afterwards, so it has to be in the text.
  assert.match(diagnostic.message, /suggested by a model, unverified/);
  assert.match(diagnostic.message, /unverified/);
});

test('a rule finding is not labelled as a suggestion', () => {
  const diagnostic = toDiagnostic(finding(), '/p');
  assert.doesNotMatch(diagnostic.message, /unverified/);
});

test('the rule id becomes the diagnostic code, so it can be filtered and suppressed', () => {
  const diagnostic = toDiagnostic(finding(), '/p');
  assert.equal(diagnostic.ruleId, 'AOP001');
});

test('a range spanning a whole method is clamped instead of underlining the file', () => {
  const diagnostic = toDiagnostic(finding({ line: 10, endLine: 400 }), '/p');

  assert.ok(
    diagnostic.endLine - diagnostic.line <= MAX_HIGHLIGHTED_LINES,
    'a finding that matched a whole method should not underline all of it',
  );
  // Still starts where it really does, so the user is pointed at the annotation and not at the top.
  assert.equal(diagnostic.line, 9);
});

test('a single-line finding does not run to the end of the line', () => {
  // endColumn is only "to end of line" when the range spans lines. On one line it has to be a real
  // column, or the highlight ends up covering text the rule said nothing about.
  const diagnostic = toDiagnostic(finding({ line: 17, column: 5, endLine: 17 }), '/p');

  assert.equal(diagnostic.endLine, 16);
  assert.equal(diagnostic.endColumn, 5);
});

test('a finding on line 1 does not produce a negative offset', () => {
  const diagnostic = toDiagnostic(finding({ line: 1, column: 1, endLine: 1 }), '/p');
  assert.equal(diagnostic.line, 0);
  assert.equal(diagnostic.column, 0);
});

test('findings are grouped by absolute file, which is the shape a collection wants', () => {
  const findings = [
    finding(),
    finding({ ruleId: 'BEAN003' }),
    finding({ file: 'src/main/java/com/example/broken/InventoryDao.java' }),
  ];

  const grouped = byFile(findings, '/project');

  assert.equal(grouped.size, 2);
  assert.equal(grouped.get('/project/src/main/java/com/example/broken/OrderService.java').length, 2);
  assert.equal(
    grouped.get('/project/src/main/java/com/example/broken/InventoryDao.java').length,
    1,
  );
});

test('grouping a single file keeps every finding for it', () => {
  // The failure this catches is deduping by file instead of appending, which would show the first
  // finding in a file and silently drop the rest.
  const findings = [
    finding({ ruleId: 'AOP001' }),
    finding({ ruleId: 'BEAN001' }),
    finding({ ruleId: 'BEAN003' }),
  ];
  const grouped = byFile(findings, '/project');
  const only = [...grouped.values()][0];
  assert.equal(only.length, 3);
  assert.deepEqual(only.map((d) => d.ruleId), ['AOP001', 'BEAN001', 'BEAN003']);
});
