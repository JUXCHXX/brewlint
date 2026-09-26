'use strict';

/**
 * The GitHub Action's two rendering scripts.
 *
 * <p>These exist as files, rather than as inline JavaScript in the workflow, because the only way to
 * run a composite action for real is to open a pull request. Anything inlined is a path that has
 * never executed, and the first person to open a PR should not be the person debugging it.
 */

const { test } = require('node:test');
const assert = require('node:assert/strict');

const {
  escapeCommandValue,
  escapeProperty,
  commandFor,
  renderAnnotations,
  renderOutputs,
  outputs,
} = require('../render-annotations.js');

const { MARKER, tally, groupByFile, renderComment } = require('../render-comment.js');

function finding(overrides = {}) {
  return {
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
    ...overrides,
  };
}

function report(overrides = {}) {
  return {
    schemaVersion: 1,
    tool: 'brewlint',
    toolVersion: '0.1.0',
    filesScanned: 2,
    filesWithParseErrors: 0,
    durationMillis: 130,
    counts: { error: 1, warning: 0, info: 0 },
    findings: [finding()],
    ...overrides,
  };
}

// ---- annotations -------------------------------------------------------------------------------

test('a finding becomes an annotation on its own line, with the file, line and rule id', () => {
  const [line] = renderAnnotations(report(), 1).split('\n');

  assert.match(line, /^::error /);
  assert.match(line, /file=src\/main\/java\/com\/example\/OrderService\.java/);
  assert.match(line, /line=17/);
  assert.match(line, /col=5/);
  assert.match(line, /title=AOP001/);
  assert.match(line, /@Transactional on a private method\./);
  // The suggestion travels with the message. An annotation that says what is wrong and stops sends
  // the reader to the documentation to find out what to do.
  assert.match(line, /Make the method public\./);
});

test('a newline in a message cannot break the annotation into two log lines', () => {
  // The ::error:: protocol is newline-delimited. Unescaped, the second half of the message is
  // logged as if it were something the action decided to say, which is a lie about provenance.
  const nasty = finding({ message: 'line one\n::warning::something the action never said' });
  const [line] = renderAnnotations(report({ findings: [nasty] }), 1).split('\n');

  assert.match(line, /%0A/);
  assert.doesNotMatch(line, /\n/);
  assert.equal(renderAnnotations(report({ findings: [nasty] }), 1).split('\n').length > 2, true);
});

test('a comma in a file path does not truncate the annotation', () => {
  // Properties are comma-separated, so an unescaped comma ends the property list early and the
  // annotation lands on whatever the next field was parsed as. A path with a comma is unusual and a
  // silent failure is worse than a rare one.
  const [line] = renderAnnotations(report({ findings: [finding({ file: 'a,b/C.java' })] }), 1)
    .split('\n');

  assert.match(line, /file=a%2Cb\/C\.java/);
  assert.match(line, /title=AOP001/);
});

test('a colon in a path is escaped too', () => {
  assert.equal(escapeProperty('C:/a/B.java'), 'C%3A/a/B.java');
});

test('percent signs are escaped first, so an escape is not double-counted', () => {
  // Order matters: escaping % last would turn the % in %0A back into a literal escape sequence and
  // the newline would come back.
  assert.equal(escapeCommandValue('100% done\nnext'), '100%25 done%0Anext');
});

test('severity maps onto the workflow command GitHub understands', () => {
  assert.equal(commandFor('ERROR'), 'error');
  assert.equal(commandFor('WARNING'), 'warning');
  assert.equal(commandFor('INFO'), 'notice');
  // Anything unexpected is a notice, which is the quiet end. A new severity should not turn every
  // finding in a future version into a red X.
  assert.equal(commandFor('SOMETHING_NEW'), 'notice');
});

test('the counts come from the JSON counts, not from counting the array', () => {
  // The report already has them. Recounting is a second answer to the same question, and the two
  // disagreeing would be visible only as a wrong number in a PR comment.
  const r = report({ findings: [finding()], counts: { error: 7, warning: 0, info: 0 } });
  const out = renderAnnotations(r, 1);

  assert.match(out, /1 finding\(s\): 7 error/);
});

test('outputs carry the counts, the version and the exit code', () => {
  const text = renderOutputs(report(), 1);

  assert.match(text, /^findings=1$/m);
  assert.match(text, /^errors=1$/m);
  assert.match(text, /^warnings=0$/m);
  assert.match(text, /^infos=0$/m);
  assert.match(text, /^tool-version=0\.1\.0$/m);
  assert.match(text, /^exit-code=1$/m);
});

test('missing counts are zero, not undefined', () => {
  // A clean project reports an empty counts object. An output that reads "undefined" in a
  // downstream job fails a comparison nobody can explain.
  const out = outputs(report({ counts: {}, findings: [] }), 0);
  assert.equal(out.errors, 0);
  assert.equal(out.warnings, 0);
  assert.equal(out.findings, 0);
});

test('a missing exit code is refused rather than written as the string undefined', () => {
  // Found by running the script rather than only its functions: BREWLINT_EXIT reaches it through
  // the environment, and a shell variable nobody exported arrives as undefined. Written to
  // GITHUB_OUTPUT it becomes exit-code=undefined, which a downstream job compares against a number
  // and quietly gets wrong.
  for (const missing of [undefined, null, '', 'undefined']) {
    assert.throws(
      () => outputs(report(), missing),
      /the exit code is required/,
      `expected ${JSON.stringify(missing)} to be refused`,
    );
  }
});

test('exit code zero is a real answer and is not refused', () => {
  // The guard above must not catch the clean case, which is the most common one.
  assert.equal(outputs(report(), 0)['exit-code'], 0);
});

test('a clean project still produces a summary', () => {
  // The log has to say what happened even when nothing happened, or a silent step looks like a step
  // that did not run.
  const out = renderAnnotations(report({ findings: [], counts: {} }), 0);
  assert.match(out, /0 finding\(s\): 0 error, 0 warning, 0 info/);
  assert.match(out, /scanned 2 file\(s\)/);
});

// ---- the pull request comment -------------------------------------------------------------------

test('the comment carries a marker, so the next run can find and update it', () => {
  // A new comment on every push buries the review and trains people to ignore the bot, which is
  // worse than not commenting at all.
  assert.ok(renderComment(report()).includes(MARKER));
});

test('the severity tally names each severity in words, not only in colour', () => {
  const text = tally(report({ counts: { error: 3, warning: 2, info: 1 } }));

  assert.match(text, /error 3/);
  assert.match(text, /warning 2/);
  assert.match(text, /info 1/);
});

test('a clean project says so, and says it plainly', () => {
  const text = renderComment(report({ findings: [], counts: {} }));
  assert.match(text, /No findings\./);
  assert.match(text, new RegExp(MARKER.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
});

test('findings are grouped by file, alphabetically, because a severity-sorted list is not reviewable', () => {
  const text = renderComment(report({
    findings: [
      finding({ ruleId: 'A', file: 'B.java' }),
      finding({ ruleId: 'C', file: 'A.java' }),
      finding({ ruleId: 'D', file: 'B.java' }),
    ],
  }));

  // Alphabetical, not order of appearance. Insertion order depends on how the engine sorted, so it
  // looks stable until the engine changes its sort and every file in every comment jumps.
  const aAt = text.indexOf('`A.java`');
  const bAt = text.indexOf('`B.java`');
  assert.ok(aAt > 0 && aAt < bAt, 'A.java should be listed before B.java');
  // Both findings for B.java are present, not just the first.
  assert.match(text, /\*\*A\*\*/);
  assert.match(text, /\*\*D\*\*/);
  assert.equal(text.match(/`B\.java`/g).length, 1, 'the file heading appears once');
});

test('the grouping does not depend on the order the engine happened to use', () => {
  // The property the alphabetical sort exists for. Two reports with the same findings in a different
  // order must produce the same comment, or every push shows a diff for no reason.
  const a = renderComment(report({
    findings: [finding({ ruleId: 'A', file: 'B.java' }), finding({ ruleId: 'C', file: 'A.java' })],
  }));
  const b = renderComment(report({
    findings: [finding({ ruleId: 'C', file: 'A.java' }), finding({ ruleId: 'A', file: 'B.java' })],
  }));
  assert.equal(a, b);
});

test('truncation is announced, so a short list is not read as a complete one', () => {
  const many = Array.from({ length: 12 }, (unused, i) => finding({ ruleId: `R${i}` }));
  const text = renderComment(report({ findings: many }), 5);

  assert.match(text, /Showing 5 of 12/);
  assert.match(text, /…and 7 more in this file/);
});

test('nothing is claimed to be hidden when nothing was hidden', () => {
  const text = renderComment(report({ findings: [finding()] }), 5);
  assert.doesNotMatch(text, /Showing 1 of 1/);
});

test('parse problems are stated, because missing findings are not findings that do not exist', () => {
  const text = renderComment(report({ filesWithParseErrors: 2 }));
  assert.match(text, /2 file\(s\) could not be parsed/);
  assert.match(text, /may be incomplete/);
});

test('a file with no parse problems does not get the warning', () => {
  assert.doesNotMatch(renderComment(report()), /could not be parsed/);
});

test('grouping sorts the files and keeps the findings within a file in report order', () => {
  const grouped = groupByFile([
    finding({ file: 'B.java', ruleId: 'B1' }),
    finding({ file: 'A.java' }),
    finding({ file: 'B.java', ruleId: 'B2' }),
  ]);
  assert.deepEqual([...grouped.keys()], ['A.java', 'B.java']);
  assert.deepEqual(grouped.get('B.java').map((f) => f.ruleId), ['B1', 'B2']);
});
