'use strict';

/**
 * Findings to diagnostics.
 *
 * <p>Produces plain data, not `vscode.Diagnostic` objects. The conversion is the part with the
 * real decisions in it, and keeping it free of the editor API means it can be tested with
 * `node --test` and reused by the GitHub Action, which needs the same mapping in a different shape.
 *
 * <p>The decision that matters most is the one about line numbers. Brewlint reports 1-based
 * positions, the way Java tooling and every compiler does, because that is what a person reading
 * `InventoryDao.java:18` means. VS Code's `Range` is 0-based, and `Range` is exclusive at the end.
 * Getting this wrong does not throw: it puts every squiggle one line below the code it belongs to,
 * which is a bug nobody reports because the panel is populated and looks plausible.
 *
 * <p>No `require('vscode')`, and nothing here imports anything that does.
 */

const path = require('node:path');

/** How a severity maps onto a diagnostic. The values are the ones VS Code uses. */
const SEVERITY_TO_DIAGNOSTIC = {
  ERROR: 0, // vscode.DiagnosticSeverity.Error
  WARNING: 1, // vscode.DiagnosticSeverity.Warning
  INFO: 2, // vscode.DiagnosticSeverity.Info
};

/** How long a range may be. A finding that spans a file is noise, not a location. */
const MAX_HIGHLIGHTED_LINES = 5;

/**
 * One diagnostic, in plain data.
 *
 * @param {string} file      absolute path, so the editor can match it to a document
 * @param {number} line      0-based
 * @param {number} column    0-based
 * @param {number} endLine   0-based
 * @param {number} endColumn 0-based, exclusive
 * @param {number} severity  0, 1 or 2
 * @param {string} ruleId    becomes the diagnostic code, which is what the user searches on
 * @param {string} message
 */
function toDiagnostic(finding, rootPath) {
  const line = finding.line - 1;
  const column = finding.column - 1;

  // The range is the span the rule actually reported, but clamped: a rule that matched a whole
  // method would otherwise underline the file, which reads as "everything here is wrong" and
  // buries the finding the user came to look at.
  const endLine = Math.min(finding.endLine, finding.line + MAX_HIGHLIGHTED_LINES) - 1;
  const endColumn = endLine > line ? Number.MAX_SAFE_INTEGER : finding.column;

  return {
    file: path.resolve(rootPath, finding.file),
    line: Math.max(0, line),
    column: Math.max(0, column),
    endLine: Math.max(0, endLine),
    endColumn,
    severity: SEVERITY_TO_DIAGNOSTIC[finding.severity],
    ruleId: finding.ruleId,
    message: composeMessage(finding),
  };
}

/**
 * The message the user reads in the Problems panel.
 *
 * <p>The suggestion is part of the message rather than a hover, because a diagnostic whose text
 * says what is wrong and stops there sends the user to the documentation to find out what to do.
 *
 * <p>An AI finding says so, in words, in the message itself. A squiggle that looks identical to a
 * rule's is a suggestion presented as a fact, and the panel is the one place where that distinction
 * cannot be made afterwards.
 */
function composeMessage(finding) {
  if (finding.source === 'AI') {
    return `[suggested by a model, unverified] ${finding.message} ${finding.suggestion}`;
  }
  return `${finding.message} ${finding.suggestion}`;
}

/**
 * Groups diagnostics by file, which is the shape a DiagnosticCollection wants.
 *
 * <p>Grouping here rather than in the editor layer means the result can be asserted on directly:
 * the thing most likely to go wrong is a finding attributed to the wrong file, and that is only
 * observable after the grouping.
 */
function byFile(findings, rootPath) {
  const grouped = new Map();
  for (const finding of findings) {
    const diagnostic = toDiagnostic(finding, rootPath);
    const existing = grouped.get(diagnostic.file);
    if (existing) {
      existing.push(diagnostic);
    } else {
      grouped.set(diagnostic.file, [diagnostic]);
    }
  }
  return grouped;
}

module.exports = { SEVERITY_TO_DIAGNOSTIC, MAX_HIGHLIGHTED_LINES, toDiagnostic, composeMessage, byFile };
