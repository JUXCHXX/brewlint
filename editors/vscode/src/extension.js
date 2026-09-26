'use strict';

/**
 * The VS Code binding. The only file in this extension that requires the editor API.
 *
 * <p>Everything with a decision in it lives in report.js, diagnostics.js and scan.js, which do not
 * import 'vscode' and are tested with node --test against the real binary. This file wires those to
 * windows, documents and events, and it is deliberately thin.
 *
 * <p>Three things here are worth reading before changing them.
 *
 * <ul>
 *   <li><strong>Scans are debounced and cancelled.</strong> A save triggers a rescan, and a rescan
 *       of a real project is not instant. Without both, typing produces a queue of processes and the
 *       extension spends more CPU than the analysis it is asking for.</li>
 *   <li><strong>A failed scan does not clear the panel.</strong> If the tool cannot run, the
 *       previous findings stay. Replacing them with an empty Problems panel is the worst failure
 *       mode available: it tells the user their code is clean at the exact moment the tool stopped
 *       being able to tell.</li>
 *   <li><strong>Whole-project problems are not diagnostics.</strong> A missing binary, a scan that
 *       could not run, a file that did not parse: none of these belong to a source line, and
 *       attaching them to an invented URI puts a phantom file in the user's Problems panel. They go
 *       to the output channel and a status bar item instead, which is where a project-level
 *       message can honestly live.</li>
 * </ul>
 */

const vscode = require('vscode');

const { byFile } = require('./diagnostics.js');
const { resolveBinary, scan } = require('./scan.js');

const CONFIG_SECTION = 'brewlint';

let collection;
let status;
let log;

/** The in-flight scan, so a newer one can cancel it. */
let current = null;

/** The debounce timer, so a burst of saves collapses into one scan. */
let debounceTimer = null;

/** Folders already scanned. A folder is added once, and only when the scan produced a report. */
const scanned = new Set();

function configuration() {
  return vscode.workspace.getConfiguration(CONFIG_SECTION);
}

function folders() {
  return vscode.workspace.workspaceFolders ?? [];
}

/** Where a whole-project message is shown. Never a diagnostic, because it has no line. */
function projectProblem(reason, detail) {
  log.appendLine('');
  log.appendLine(`brewlint: ${reason}`);
  if (detail) {
    log.appendLine(detail);
  }
  log.show(true);
  status.text = '$(warning) Brewlint';
  status.tooltip = reason;
  status.show();
}

function projectOk(summary) {
  status.text = '$(check) Brewlint';
  status.tooltip = summary;
  status.show();
}

async function runScan(folder) {
  if (!configuration().get('enabled', true)) {
    return;
  }

  const { path: binary, error } = resolveBinary(configuration().get('binaryPath', ''));
  if (error) {
    projectProblem(
      'the Brewlint binary is not installed',
      [
        error,
        '',
        'It ships with this extension as a dependency of the "brewlint" package, which the',
        'extension declares. Installing from a source checkout needs:',
        '  npm install --prefix editors/vscode',
        '',
        'If you have Brewlint installed elsewhere, point at it with brewlint.binaryPath.',
      ].join('\n'),
    );
    return;
  }

  // Cancels whatever was running. A rescan of a project that just changed does not need the answer
  // to a question about how it looked before the change.
  current?.abort();
  const controller = new AbortController();
  current = controller;

  const result = await scan({
    binary,
    cwd: folder.uri.fsPath,
    signal: controller.signal,
    timeoutMillis: configuration().get('scanTimeout', 120000),
  });

  if (controller.signal.aborted) {
    return;
  }
  current = null;
  publish(folder, result);
}

function publish(folder, result) {
  if (!result.ok) {
    if (result.reason === 'cancelled') {
      return;
    }
    // The existing diagnostics are left alone on purpose. See the note at the top of the file.
    projectProblem(
      result.reason,
      result.detail
        ? `${result.detail}\n\nRun "Brewlint: Rescan" to try again, or see brewlint.scanTimeout if ` +
          'this project is genuinely large.'
        : 'Run "Brewlint: Rescan" to try again.',
    );
    return;
  }

  const { report } = result;
  collection.clear();

  let total = 0;
  for (const [file, descriptors] of byFile(report.findings, folder.uri.fsPath)) {
    collection.set(vscode.Uri.file(file), descriptors.map(toDiagnostic));
    total += descriptors.length;
  }

  const summary = `${total} finding${total === 1 ? '' : 's'} in ${report.filesScanned} file` +
    `${report.filesScanned === 1 ? '' : 's'}`;
  log.appendLine(`${summary} in ${report.durationMillis}ms (Brewlint ${report.toolVersion})`);
  projectOk(summary);

  if (report.filesWithParseErrors > 0) {
    // Said plainly rather than left to be inferred from absent findings. A file that failed to
    // parse was not analysed, so a rule that would have fired on it never got the chance.
    log.appendLine(
      `  ${report.filesWithParseErrors} file(s) could not be parsed, so findings in them may be ` +
        'incomplete.',
    );
  }

  scanned.add(folder.uri.toString());
}

function toDiagnostic(descriptor) {
  const diagnostic = new vscode.Diagnostic(
    new vscode.Range(descriptor.line, descriptor.column, descriptor.endLine, descriptor.endColumn),
    descriptor.message,
    descriptor.severity,
  );
  // The rule id is the code, so "brewlint.AOP001" filters in the Problems panel and
  // //@id:brewlint.AOP001 in a file suppresses it. That is what makes the id worth having.
  diagnostic.source = 'brewlint';
  diagnostic.code = descriptor.ruleId;
  return diagnostic;
}

/** Coalesces a burst of saves into one scan. */
function scheduleScan(folder, delay) {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    debounceTimer = null;
    void runScan(folder);
  }, delay);
}

function scanAll() {
  for (const folder of folders()) {
    void runScan(folder);
  }
}

function activate(context) {
  log = vscode.window.createOutputChannel('Brewlint');
  collection = vscode.languages.createDiagnosticCollection('brewlint');
  status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  context.subscriptions.push(log, collection, status);

  // On activate, so a user who installs this into an open project sees findings without touching
  // anything. Cheap when there is nothing to find and the only cost when there is.
  scanAll();

  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((event) => {
      if (!event.affectsConfiguration(CONFIG_SECTION)) {
        return;
      }
      if (configuration().get('enabled', true) === false) {
        // Clear rather than only stop scanning, or the findings stay on screen forever after the
        // user turns the extension off.
        collection.clear();
        status.hide();
        return;
      }
      scanAll();
    }),

    vscode.workspace.onDidSaveTextDocument((document) => {
      if (document.languageId !== 'java') {
        return;
      }
      const folder = vscode.workspace.getWorkspaceFolder(document.uri);
      if (folder) {
        scheduleScan(folder, configuration().get('debounce', 500));
      }
    }),

    vscode.workspace.onDidOpenTextDocument((document) => {
      if (document.languageId !== 'java') {
        return;
      }
      // Only for a folder never scanned. Otherwise opening a file rescans a project that has not
      // changed, which on a large one is a visible pause in exchange for no new information.
      const folder = vscode.workspace.getWorkspaceFolder(document.uri);
      if (folder && !scanned.has(folder.uri.toString())) {
        void runScan(folder);
      }
    }),

    vscode.commands.registerCommand('brewlint.rescan', scanAll),

    vscode.commands.registerCommand('brewlint.showOutput', () => {
      log.show(true);
    }),
  );
}

function deactivate() {
  clearTimeout(debounceTimer);
  current?.abort();
  collection?.dispose();
  status?.dispose();
}

module.exports = { activate, deactivate };
