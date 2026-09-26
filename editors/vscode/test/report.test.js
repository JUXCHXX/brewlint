'use strict';

/**
 * The JSON contract.
 *
 * <p>What is being tested here is not that the parser works, it is that it refuses. A linter
 * extension's worst failure is an empty Problems panel that reads as "your code is clean" when the
 * tool actually failed, so every one of these cases is a report that must be rejected with a reason
 * a user could act on.
 */

const { test } = require('node:test');
const assert = require('node:assert/strict');

const { SUPPORTED_SCHEMA_VERSION, parseReport, parseReportText } = require('../src/report.js');

/** A report as the engine produces it. */
function validReport(overrides = {}) {
  return {
    schemaVersion: 1,
    tool: 'brewlint',
    toolVersion: '0.1.0',
    filesScanned: 1,
    filesWithParseErrors: 0,
    durationMillis: 42,
    counts: { error: 1, warning: 0, info: 0 },
    findings: [
      {
        ruleId: 'AOP001',
        category: 'spring-aop',
        source: 'RULE',
        severity: 'ERROR',
        file: 'src/main/java/com/example/OrderService.java',
        line: 17,
        column: 5,
        endLine: 19,
        message: '@Transactional on a private method.',
        suggestion: 'Make the method public.',
      },
    ],
    ...overrides,
  };
}

function finding(overrides = {}) {
  return { ...validReport().findings[0], ...overrides };
}

test('a well-formed report is accepted', () => {
  const result = parseReport(validReport());
  assert.equal(result.ok, true);
  assert.equal(result.report.findings.length, 1);
});

test('a clean project is accepted, with no findings at all', () => {
  const result = parseReport(validReport({ findings: [], filesScanned: 12, counts: {} }));
  assert.equal(result.ok, true);
  assert.deepEqual(result.report.findings, []);
});

test('output that is not JSON is rejected with a reason, not parsed loosely', () => {
  const result = parseReportText('brewlint: no .java files found under /tmp');
  assert.equal(result.ok, false);
  assert.match(result.reason, /valid JSON/);
});

test('empty output is rejected, because it is not a clean project', () => {
  // The failure that matters most. A tool that printed nothing and exited quietly would otherwise
  // look exactly like a project with no findings.
  for (const empty of ['', '   \n ', null, undefined]) {
    const result = parseReportText(empty);
    assert.equal(result.ok, false, `expected ${JSON.stringify(empty)} to be rejected`);
    assert.match(result.reason, /no output/);
  }
});

test('a missing key is named, so the user knows which version to look at', () => {
  const report = validReport();
  delete report.toolVersion;
  const result = parseReport(report);
  assert.equal(result.ok, false);
  assert.match(result.reason, /toolVersion/);
});

test('a key of the wrong type is rejected rather than coerced', () => {
  const result = parseReport(validReport({ filesScanned: '9' }));
  assert.equal(result.ok, false);
  assert.match(result.reason, /"filesScanned" should be a non-negative integer/);
  assert.match(result.detail, /got a string/);
});

test('a null counts object is rejected, because it would throw on property access', () => {
  // typeof null === 'object', so a naive check accepts it and the crash lands somewhere unrelated.
  const result = parseReport(validReport({ counts: null }));
  assert.equal(result.ok, false);
  assert.match(result.reason, /"counts" should be an object/);
});

test('an array is accepted where an array belongs, which typeof cannot express', () => {
  // This is a bug that shipped into the first draft of the validator. `typeof [] === 'object'`, so
  // a validator that compares against the string 'array' rejects every findings array the engine
  // produces, and the extension shows nothing for a project that has findings. It passed every
  // other test because no other test had an array in a typed position.
  const withArray = validReport({ findings: [finding()] });
  assert.equal(parseReport(withArray).ok, true);

  // And the mirror case: an object where an array belongs is still refused.
  const swapped = validReport({ findings: { not: 'an array' } });
  const result = parseReport(swapped);
  assert.equal(result.ok, false);
  assert.match(result.reason, /"findings" should be an array/);
  assert.match(result.detail, /got an object/);
});

test('a negative count is rejected, since it cannot be true', () => {
  const result = parseReport(validReport({ filesScanned: -1 }));
  assert.equal(result.ok, false);
  assert.match(result.reason, /filesScanned/);
});

test('a fractional file count is rejected', () => {
  // A client doing arithmetic on it would produce a nonsense range or an empty panel.
  const result = parseReport(validReport({ filesScanned: 1.5 }));
  assert.equal(result.ok, false);
  assert.match(result.reason, /filesScanned/);
});

test('a future schema version is refused rather than half-understood', () => {
  const result = parseReport(validReport({ schemaVersion: SUPPORTED_SCHEMA_VERSION + 1 }));
  assert.equal(result.ok, false);
  assert.match(result.reason, /unsupported schema version/);
  assert.match(result.detail, /newer Brewlint/);
});

test('an older schema version is also refused, not assumed compatible', () => {
  const result = parseReport(validReport({ schemaVersion: 0 }));
  assert.equal(result.ok, false);
  assert.match(result.reason, /unsupported schema version/);
});

test('findings without files scanned are refused, they are self-contradictory', () => {
  const result = parseReport(validReport({ filesScanned: 0 }));
  assert.equal(result.ok, false);
  assert.match(result.reason, /zero files scanned/);
});

test('a malformed finding is refused and the specific problem is named', () => {
  const cases = [
    [finding({ line: 0 }), /"line" must be a 1-based positive integer/],
    [finding({ line: 1.5 }), /"line" must be a 1-based positive integer/],
    [finding({ endLine: 10, line: 17 }), /"endLine" \(10\) is before "line" \(17\)/],
    [finding({ severity: 'CRITICAL' }), /unknown severity/],
    [finding({ source: 'HUMAN' }), /unknown source/],
    [finding({ ruleId: '' }), /"ruleId" must be a non-empty string/],
    [finding({ file: undefined }), /"file" must be a non-empty string/],
    ['not an object', /it is not an object/],
  ];
  for (const [bad, expected] of cases) {
    const result = parseReport(validReport({ findings: [bad] }));
    assert.equal(result.ok, false, `expected ${JSON.stringify(bad)} to be rejected`);
    assert.match(result.reason, expected);
  }
});

test('a finding with source AI is accepted, and is not the same as a rule finding', () => {
  // Not a test of the label, which lives in diagnostics.js. A test that the value is legal at all,
  // because a client that rejected it would silently drop every suggestion the engine produced.
  const result = parseReport(validReport({ findings: [finding({ source: 'AI' })] }));
  assert.equal(result.ok, true);
});

test('every key is required even when its value is empty', () => {
  // The promise the engine makes to every client: absent and empty are distinguishable. If a key
  // can be missing, this client has to guess, and guessing here means showing the wrong thing.
  const minimal = validReport({ filesWithParseErrors: 0, durationMillis: 0, counts: {} });
  for (const key of Object.keys(minimal)) {
    const report = { ...minimal };
    delete report[key];
    assert.equal(parseReport(report).ok, false, `${key} should be required`);
  }
});
