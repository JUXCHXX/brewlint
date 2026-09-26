'use strict';

/**
 * The JSON contract, and a validator for it.
 *
 * <p>Every key is always present in the output, even when its value is null or empty, so a client
 * never has to tell "absent" from "empty". This module is where that promise is turned into
 * something the client can rely on: a report that does not match is rejected with a reason, rather
 * than rendered as an empty Problems panel that looks exactly like a clean project.
 *
 * <p>That distinction is the whole point. An extension that cannot tell "no findings" from "the
 * tool failed to produce a report" will tell every user their code is fine.
 *
 * <p>No `require('vscode')` anywhere in this file, and none in anything it imports. That is what
 * lets the logic be tested with plain `node --test`, in the same harness as the rest of the
 * repository, instead of inside an editor.
 */

/** The schema this client understands. Bumped when a change breaks existing readers. */
const SUPPORTED_SCHEMA_VERSION = 1;

/** Severities the engine can report, in the order they escalate. */
const SEVERITIES = ['ERROR', 'WARNING', 'INFO'];

/** Who produced a finding. AI findings are suggestions, never facts. */
const SOURCES = ['RULE', 'AI'];

/**
 * Top-level keys, with the type each one must have.
 *
 * <p>Types are checked by predicate rather than by comparing against typeof, because typeof reports
 * 'object' for an array and 'object' for null. A validator written that way accepts a null counts
 * and then throws when a client reads it, and rejects every findings array the engine ever emits.
 */
const SHAPE = {
  schemaVersion: { check: (v) => typeof v === 'number', name: 'a number' },
  tool: { check: (v) => typeof v === 'string', name: 'a string' },
  toolVersion: { check: (v) => typeof v === 'string', name: 'a string' },
  filesScanned: { check: (v) => Number.isInteger(v) && v >= 0, name: 'a non-negative integer' },
  filesWithParseErrors: { check: (v) => Number.isInteger(v) && v >= 0, name: 'a non-negative integer' },
  durationMillis: { check: (v) => typeof v === 'number' && v >= 0, name: 'a number' },
  counts: { check: (v) => v !== null && typeof v === 'object' && !Array.isArray(v), name: 'an object' },
  findings: { check: Array.isArray, name: 'an array' },
};

/** Describes what a value actually is, for the error message. */
function describeType(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'an array';
  const type = typeof value;
  // This string is shown to a user diagnosing a version mismatch, so "got a object" is the kind of
  // detail that makes them doubt the rest of the message.
  return `${'aeiou'.includes(type[0]) ? 'an' : 'a'} ${type}`;
}

/**
 * Checks a parsed report against the contract.
 *
 * @param {unknown} report the value from JSON.parse
 * @returns {{ok: true, report: object} | {ok: false, reason: string, detail?: string}}
 */
function parseReport(report) {
  if (report === null || typeof report !== 'object' || Array.isArray(report)) {
    return { ok: false, reason: 'the report is not a JSON object' };
  }

  for (const [key, { check, name }] of Object.entries(SHAPE)) {
    if (!(key in report)) {
      return { ok: false, reason: `the report has no "${key}" key` };
    }
    if (!check(report[key])) {
      return {
        ok: false,
        reason: `"${key}" should be ${name}`,
        detail: `got ${describeType(report[key])}`,
      };
    }
  }

  if (report.schemaVersion !== SUPPORTED_SCHEMA_VERSION) {
    return {
      ok: false,
      reason: `unsupported schema version ${report.schemaVersion}`,
      detail:
        `this extension understands version ${SUPPORTED_SCHEMA_VERSION}. ` +
        'A newer Brewlint may have added fields, or changed existing ones.',
    };
  }

  if (report.filesScanned === 0 && report.findings.length > 0) {
    // Impossible from a correct engine, and the kind of inconsistency that would otherwise turn
    // into findings attached to files that were never scanned.
    return {
      ok: false,
      reason: 'the report claims zero files scanned but has findings',
    };
  }

  const bad = report.findings.find((finding) => describeBadFinding(finding) !== null);
  if (bad) {
    const problem = describeBadFinding(bad);
    return { ok: false, reason: `a finding is malformed: ${problem}` };
  }

  return { ok: true, report };
}

/** Returns a description of what is wrong with a finding, or null when it is fine. */
function describeBadFinding(finding) {
  if (finding === null || typeof finding !== 'object' || Array.isArray(finding)) {
    return 'it is not an object';
  }
  for (const key of ['ruleId', 'file', 'message', 'suggestion']) {
    if (typeof finding[key] !== 'string' || finding[key].length === 0) {
      return `"${key}" must be a non-empty string`;
    }
  }
  for (const key of ['line', 'column', 'endLine']) {
    if (!Number.isInteger(finding[key]) || finding[key] < 1) {
      return `"${key}" must be a 1-based positive integer`;
    }
  }
  if (finding.endLine < finding.line) {
    return `"endLine" (${finding.endLine}) is before "line" (${finding.line})`;
  }
  if (!SEVERITIES.includes(finding.severity)) {
    return `unknown severity "${finding.severity}"`;
  }
  if (!SOURCES.includes(finding.source)) {
    return `unknown source "${finding.source}"`;
  }
  return null;
}

/** Parses report text, distinguishing a parse failure from a contract failure. */
function parseReportText(text) {
  if (typeof text !== 'string' || text.trim().length === 0) {
    return { ok: false, reason: 'Brewlint produced no output' };
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    return {
      ok: false,
      reason: 'Brewlint did not produce valid JSON',
      // The message is shown to the user, so it must not be a raw parse error with a source excerpt.
      detail: error.message,
    };
  }
  return parseReport(value);
}

module.exports = { SUPPORTED_SCHEMA_VERSION, SEVERITIES, SOURCES, parseReport, parseReportText };
