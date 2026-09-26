'use strict';

/**
 * Turns a Brewlint report into GitHub Actions annotations and step outputs.
 *
 * <p>Extracted from the workflow rather than inlined in it, so it can be tested with node --test.
 * The rule this project has learned nine times now: a path that only runs on a real pull request is
 * a path nobody has ever run, and the first thing to break is always the one nobody could reach.
 *
 * <p>Usage: node render-annotations.js <report.json>, with GITHUB_OUTPUT set.
 *
 * <p>Also the module. main() only runs when the file is executed directly, so the tests import the
 * functions rather than spawning a process and scraping stdout.
 */

const fs = require('node:fs');

/**
 * Escapes a value for a workflow command.
 *
 * <p>GitHub's `::error::` protocol is newline-delimited, so an unescaped newline in a message ends
 * the command early and the rest becomes a second, unrelated log line. A finding message that
 * happened to wrap would print a line of the report as if someone had logged it.
 */
function escapeCommandValue(value) {
  return String(value).replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A');
}

/**
 * Escapes a property value: the same, plus the comma and colon that separate properties.
 *
 * <p>Without this, a file path containing a comma silently loses everything after it, and the
 * annotation lands on the wrong file. GitHub has this second form precisely because property values
 * are not free text.
 */
function escapeProperty(value) {
  return escapeCommandValue(value).replace(/:/g, '%3A').replace(/,/g, '%2C');
}

/** GitHub's workflow command name for a severity. */
function commandFor(severity) {
  switch (severity) {
    case 'ERROR':
      return 'error';
    case 'WARNING':
      return 'warning';
    default:
      return 'notice';
  }
}

/** One annotation per finding, so each one lands on its own line in the diff. */
function annotations(report) {
  return report.findings.map((finding) => {
    const properties = [
      `file=${escapeProperty(finding.file)}`,
      `line=${escapeProperty(finding.line)}`,
      `col=${escapeProperty(finding.column)}`,
      `title=${escapeProperty(finding.ruleId)}`,
    ].join(',');
    return `::${commandFor(finding.severity)} ${properties}::`
      + `${escapeCommandValue(finding.message)} ${escapeCommandValue(finding.suggestion)}`;
  });
}

/** Two summary lines, so the log states the result even when the log is all there is. */
function summary(report) {
  const counts = report.counts || {};
  return [
    `::error title=Brewlint::${report.findings.length} finding(s): `
      + `${counts.error ?? 0} error, ${counts.warning ?? 0} warning, ${counts.info ?? 0} info `
      + `in ${report.filesScanned} file(s)`,
    `::notice title=Brewlint::Brewlint ${report.toolVersion} scanned `
      + `${report.filesScanned} file(s) in ${report.durationMillis}ms`,
  ];
}

/**
 * Step outputs, written before anything can exit so a failing run still reports what it found.
 *
 * <p>The exit code is required, not defaulted. It reaches this process through an environment
 * variable, and a shell variable that nobody exported arrives as the string "undefined", which then
 * lands in a step output where a downstream job compares it against a number and quietly takes the
 * wrong branch. Refusing to render is loud; writing "undefined" is neither.
 */
function outputs(report, exitCode) {
  if (exitCode === undefined || exitCode === null || exitCode === 'undefined' || exitCode === '') {
    throw new Error(
      'the exit code is required. It reaches this script through BREWLINT_EXIT, and a shell '
      + 'variable that was never exported arrives here as undefined.',
    );
  }
  const counts = report.counts || {};
  return {
    findings: report.findings.length,
    errors: counts.error ?? 0,
    warnings: counts.warning ?? 0,
    infos: counts.info ?? 0,
    'tool-version': report.toolVersion,
    'exit-code': exitCode,
  };
}

/** The whole stdout of the step, in the order it should be written. */
function renderAnnotations(report, exitCode) {
  return [...annotations(report), ...summary(report)].join('\n');
}

/** GitHub's `key=value` output format, one per line. */
function renderOutputs(report, exitCode) {
  return Object.entries(outputs(report, exitCode))
    .map(([key, value]) => `${key}=${value}`)
    .join('\n');
}

function main(argv = process.argv.slice(2), env = process.env) {
  const report = JSON.parse(fs.readFileSync(argv[0], 'utf8'));
  process.stdout.write(renderAnnotations(report, env.BREWLINT_EXIT) + '\n');
  if (env.GITHUB_OUTPUT) {
    fs.appendFileSync(
      env.GITHUB_OUTPUT,
      renderOutputs(report, env.BREWLINT_EXIT) + '\n',
    );
  }
}

if (require.main === module) {
  main();
}

module.exports = {
  escapeCommandValue,
  escapeProperty,
  commandFor,
  annotations,
  summary,
  outputs,
  renderAnnotations,
  renderOutputs,
};
