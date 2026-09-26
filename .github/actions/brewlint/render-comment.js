'use strict';

/**
 * The pull request comment.
 *
 * <p>Extracted and tested for the same reason as the annotations: the only way to run it for real is
 * to open a pull request, and the first person to do that should not be the one debugging it.
 *
 * <p>Usage: node render-comment.js <report.json> [maxFindingsPerFile], writing markdown to stdout.
 *
 * <p>Two decisions in the output shape, both about honesty:
 *
 * <ul>
 *   <li><strong>Grouped by file.</strong> Forty findings sorted by severity is not something anyone
 *       can review. The same forty sorted by file is a checklist.</li>
 *   <li><strong>Truncation is announced.</strong> When a file has more findings than fit, the
 *       comment says how many were left out. A summary that quietly stops at five reads as "that is
 *       all of them", and the reader concludes the file is nearly clean.</li>
 * </ul>
 *
 * <p>The severity icons carry a word as well as a colour, for the same reason the PDF report writes
 * its severities out: a red dot is a red dot to nobody who cannot see red, and a status message is
 * exactly where that is most likely to be read in monochrome.
 */

const fs = require('node:fs');

/** The marker that finds this comment again on the next run, so it is updated rather than added to. */
const MARKER = '<!-- brewlint-report -->';

const ICON = { ERROR: '🔴', WARNING: '🟠', INFO: '🔵' };
const WORD = { ERROR: 'error', WARNING: 'warning', INFO: 'info' };

function count(report, severity) {
  return (report.counts && report.counts[severity.toLowerCase()]) ?? 0;
}

/**
 * Groups findings by file, alphabetically.
 *
 * <p>Sorted rather than in the order the report had them. Insertion order looks stable until the
 * engine changes how it sorts, at which point every file in every comment jumps around for no reason
 * a reviewer can see, and a diff on the comment becomes unreadable. Alphabetical is stable because
 * nothing else can change it, and it is also the order somebody works through a list in.
 *
 * <p>Within a file, the report's own order is kept: the engine has already decided which finding
 * matters most, and re-sorting that is a second opinion nobody asked for.
 */
function groupByFile(findings) {
  const grouped = new Map();
  for (const finding of findings) {
    if (!grouped.has(finding.file)) {
      grouped.set(finding.file, []);
    }
    grouped.get(finding.file).push(finding);
  }
  return new Map([...grouped.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
}

/** One line per severity: an icon, the word, and the number. Never the icon alone. */
function tally(report) {
  return ['ERROR', 'WARNING', 'INFO']
    .map((severity) => `${ICON[severity]} ${WORD[severity]} ${count(report, severity)}`)
    .join(' · ');
}

function renderComment(report, maxPerFile = 5) {
  const lines = [MARKER, '## Brewlint', ''];
  lines.push(tally(report));
  lines.push('');
  lines.push(
    `Brewlint ${report.toolVersion} · ${report.filesScanned} file(s) in ${report.durationMillis}ms`,
  );

  if (report.findings.length === 0) {
    lines.push('', 'No findings.');
    return lines.join('\n');
  }

  let shown = 0;
  for (const [file, findings] of groupByFile(report.findings)) {
    lines.push('', `**\`${file}\`**`);
    for (const finding of findings.slice(0, maxPerFile)) {
      shown += 1;
      const icon = ICON[finding.severity] ?? '';
      lines.push(
        `- ${icon} \`${finding.line}:${finding.column}\` **${finding.ruleId}** ${finding.message}`,
      );
      lines.push(`  <sub>${finding.suggestion}</sub>`);
    }
    if (findings.length > maxPerFile) {
      lines.push(`- <sub>…and ${findings.length - maxPerFile} more in this file</sub>`);
    }
  }

  if (shown < report.findings.length) {
    lines.push('');
    lines.push(
      `<sub>Showing ${shown} of ${report.findings.length}. `
      + 'The rest are in the Checks tab.</sub>',
    );
  }

  // Said plainly, because absent findings in a file that did not parse are not evidence of absence.
  if (report.filesWithParseErrors > 0) {
    lines.push('');
    lines.push(
      `> ${report.filesWithParseErrors} file(s) could not be parsed, so findings in them may be `
      + 'incomplete.',
    );
  }

  return lines.join('\n');
}

function main(argv = process.argv.slice(2)) {
  const report = JSON.parse(fs.readFileSync(argv[0], 'utf8'));
  const maxPerFile = Number(argv[1]) || 5;
  process.stdout.write(renderComment(report, maxPerFile) + '\n');
}

if (require.main === module) {
  main();
}

module.exports = { MARKER, ICON, tally, groupByFile, renderComment };
