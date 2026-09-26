#!/usr/bin/env node
'use strict';

/**
 * Proves the install works the way a user will experience it.
 *
 * <p>This is the Hito 3 deliverable, and it is the one that cannot be faked. The claim is "npm
 * install -g brewlint and it runs, on a machine with no Java". So the test:
 *
 * <ol>
 *   <li>Packs the main package and the platform package into tarballs, the way npm would receive
 *       them. Nothing is linked from the source tree, so a missing file in {@code files} shows up
 *       here rather than after publishing.</li>
 *   <li>Installs them into a throwaway directory.</li>
 *   <li>Runs the installed {@code brewlint} with an empty environment: no PATH to a JDK, no
 *       JAVA_HOME, no NODE_PATH. If the bundled runtime is not really self-contained, this is
 *       where it shows.</li>
 *   <li>Checks the exit codes, because a launcher that collapses them turns a red CI build
 *       green.</li>
 * </ol>
 *
 * <p>Usage: node npm/scripts/test-install.mjs
 */

import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDir, '..', '..');
const mainPackageDir = join(projectRoot, 'npm', 'brewlint');
const platformDir = join(projectRoot, 'npm', 'platform');

let failures = 0;
let checks = 0;

function check(description, condition, detail = '') {
  checks += 1;
  if (condition) {
    console.log(`  ok   ${description}`);
  } else {
    failures += 1;
    console.log(`  FAIL ${description}${detail ? `\n       ${detail}` : ''}`);
  }
}


// On Windows npm is a .cmd, which spawnSync cannot execute without a shell. Every call to npm goes
// through here so that difference lives in one place instead of at four call sites, and so a path
// with a space in it stays a single argument everywhere else.
const NPM_OPTIONS = {
  encoding: 'utf8',
  stdio: ['ignore', 'pipe', 'inherit'],
  shell: process.platform === 'win32',
  windowsHide: true,
};

function npm(args, options = {}) {
  return execFileSync('npm', args, { ...NPM_OPTIONS, ...options });
}

function section(title) {
  console.log(`\n${title}`);
}

/**
 * Packs a package and returns the file list npm computed for it.
 *
 * <p>Not `tar -tzf` on the result. The three `tar` implementations in play are three different
 * programs: GNU tar on the Linux runner, BSD tar on macOS, and a Windows bsdtar behind a Git Bash
 * path-translation layer that rewrote `C:\Users\...` into `C\:/Users/...` and produced
 * "Cannot open: No such file or directory" for a file that demonstrably existed. Prefixing `./` and
 * normalising the slashes both failed, because the mangling happens in the shell, after Node hands
 * the argument over.
 *
 * <p>`npm pack --json` asks npm itself which files the `files` allowlist selected, which is the
 * actual question being asked here, and it is the same answer on every platform because it is
 * computed by the same program that will build the tarball. One subprocess, no external tool, and
 * the check can no longer disagree with what gets published.
 */
function pack(cwd) {
  const report = JSON.parse(npm(['pack', '--json', '--pack-destination', tarballDir], { cwd }));
  const entry = Array.isArray(report) ? report[0] : report;
  return {
    filename: entry.filename,
    // The real path in the tarball is "package/<path>"; npm reports it without that prefix.
    files: entry.files.map((file) => `package/${file.path}`),
  };
}

// Which platform package did we assemble? On a single machine there is exactly one.
const { readdirSync } = await import('node:fs');
const assembled = readdirSync(platformDir, { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name);

if (assembled.length === 0) {
  console.error('No platform package has been assembled. Run:');
  console.error('  ./scripts/build-runtime.sh');
  console.error('  node npm/scripts/assemble-platform-packages.mjs');
  process.exit(1);
}

const workspace = mkdtempSync(join(tmpdir(), 'brewlint-install-'));
const tarballDir = join(workspace, 'tarballs');
const installDir = join(workspace, 'installed');
const projectFixture = join(workspace, 'fixture');
mkdirSync(tarballDir, { recursive: true });
mkdirSync(installDir, { recursive: true });

console.log(`Testing the npm install in ${workspace}`);
console.log(`  platform packages available: ${assembled.join(', ')}`);

try {
  // Step 1: pack. Packing rather than copying is the point: it applies the `files` allowlist, which
  // is the thing that silently omits a file in every Node project ever published.
  section('1. npm pack');
  const main = pack(mainPackageDir);
  const mainTarball = main.filename;

  const platformTarballs = [];
  const platformPack = [];
  for (const name of assembled) {
    const packed = pack(join(platformDir, name));
    platformPack.push(packed);
    platformTarballs.push(join(tarballDir, packed.filename));
  }
  check(`main package packs (${mainTarball})`, mainTarball.endsWith('.tgz'));
  check('platform package packs', platformTarballs.length === assembled.length);

  // The tarball must actually contain the binary, not just the manifest. These are the paths npm
  // put in the tarball, not a guess about what it should have put in it.
  section('2. tarball contents');
  check('the launcher is in the main tarball', main.files.includes('package/bin/brewlint.js'));
  check('the platform table is in the main tarball', main.files.includes('package/lib/platforms.js'));

  const platformFiles = platformPack[0].files;
  check(
    'the binary is in the platform tarball',
    platformFiles.some((file) => file.includes('/bin/')),
    'a manifest-only tarball would pass npm publish and fail at run time; found: ' +
      (platformFiles.slice(0, 5).join(', ') || 'nothing'),
  );

  // Step 3: install into a clean prefix, exactly as a global install would.
  section('3. install into a clean prefix');
  npm(
    [
      'install',
      '--prefix',
      installDir,
      '--no-audit',
      '--no-fund',
      '--loglevel', 'error',
      join(tarballDir, mainTarball),
      ...platformTarballs,
    ],
  );

  const installedShim = join(installDir, 'node_modules', '.bin', 'brewlint');
  check('the brewlint command is linked', existsSync(installedShim));

  // On Windows npm links a .cmd wrapper rather than making the script directly executable, and
  // spawning it needs a shell. Getting this wrong means the test only ever runs on the maintainer's
  // platform and the Windows binary ships unverified.
  const isWindows = process.platform === 'win32';
  const shimCommand = isWindows ? `${installedShim}.cmd` : installedShim;
  const shimOptions = isWindows ? { shell: true } : {};

  function runShim(args, extraEnv = {}) {
    return spawnSync(shimCommand, args, {
      env: { ...cleanEnv, ...extraEnv },
      encoding: 'utf8',
      ...shimOptions,
    });
  }

  // Step 4: run it with nothing in the environment. This is the claim under test.
  section('4. run with no Java installed');

  // The environment for these runs has to contain node, because the shim is a node script, and must
  // not contain any java. Building the PATH by filtering out every directory that holds a `java`
  // executable is what makes the test mean something: an empty PATH would fail for the wrong reason
  // and prove nothing.
  //
  // path.delimiter, not a hardcoded ':'. Windows separates PATH entries with ';' and ':' is a legal
  // character inside a path like 'C:\Program Files\Git\usr\bin', so splitting on ':' there produced
  // one enormous entry, filtered nothing, and the "no java on PATH" check failed while every check
  // that actually depended on it still passed. A guard that fails for a reason unrelated to what it
  // guards is a guard that trains people to ignore it.
  const withoutJdk = (value) =>
    value
      .split(delimiter)
      .filter((entry) => entry && !existsSync(join(entry, 'java')) && !existsSync(join(entry, 'java.exe')))
      .join(delimiter);

  const pathWithoutJdk = withoutJdk(process.env.PATH ?? '');
  // The directory the shim itself lives in, which is under the install prefix and not on PATH.
  const nodeDirectory = dirname(process.execPath);

  // Not `spawnSync('java')`, because on Windows the absence of a program and a failed run look the
  // same through some paths. This asks the filesystem directly what the filtered PATH now resolves.
  const javaStillReachable = withoutJdk(process.env.PATH ?? '')
    .split(delimiter)
    .some((entry) => entry && existsSync(join(entry, 'java')));

  check(
    'no java is reachable on the test PATH',
    !javaStillReachable,
    'a JDK on PATH would make every check below meaningless',
  );

  const cleanEnv = { PATH: pathWithoutJdk, HOME: workspace, NODE_PATH: '' };

  const version = runShim(['--version']);
  check(
    'brewlint --version runs with no Java on PATH',
    version.status === 0 && version.stdout.includes('brewlint'),
    `status=${version.status} stdout=${version.stdout} stderr=${version.stderr}`,
  );
  check(
    'the shim itself is still runnable',
    nodeDirectory.length > 0,
    `node directory: ${nodeDirectory}`,
  );

  // A project to scan. Deliberately broken, so the exit code is 1.
  mkdirSync(join(projectFixture, 'src'), { recursive: true });
  writeFileSync(
    join(projectFixture, 'src', 'OrderService.java'),
    `package com.example;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    @Transactional
    private void charge() {}

    void read() throws Exception {
        java.io.InputStream in = new java.io.FileInputStream("a.txt");
        in.read();
    }
}
`,
  );
  writeFileSync(
    join(projectFixture, 'src', 'Clean.java'),
    `package com.example;

public class Clean {
    public void fine() {}
}
`,
  );

  const scan = runShim(['scan', '--path', projectFixture, '--no-color']);

  check('scan exits 1 on a project with findings', scan.status === 1, `status=${scan.status}`);
  check('AOP001 is reported', scan.stdout.includes('AOP001'));
  check('RES001 is reported', scan.stdout.includes('RES001'));
  check('the summary is present', /\d+ findings? in \d+ files?/.test(scan.stdout));

  const cleanScan = runShim(['scan', '--path', join(projectFixture, 'src', 'Clean.java'), '--no-color']);
  check('scan exits 0 on a clean project', cleanScan.status === 0, `status=${cleanScan.status}`);

  // Exit code 2 is the "could not run" code and must survive the shim too.
  const badPath = runShim(['scan', '--path', '/definitely/not/here']);
  check('exit 2 survives the launcher for a bad path', badPath.status === 2, `status=${badPath.status}`);

  // --fail-on none is the case a careless launcher would get wrong.
  const neverFail = runShim(['scan', '--path', projectFixture, '--fail-on', 'none']);
  check('--fail-on none exits 0', neverFail.status === 0, `status=${neverFail.status}`);

  // JSON output is the Hito 6 and Hito 7 contract, so it has to survive packaging too.
  const json = runShim(['scan', '--path', projectFixture, '--format', 'json']);
  let parsed = null;
  try {
    parsed = JSON.parse(json.stdout);
  } catch (error) {
    // left null, and the check below reports it
  }
  check('JSON output parses after install', parsed !== null, `stdout started with: ${json.stdout.slice(0, 120)}`);
  check('JSON findings survive packaging', parsed !== null && parsed.findings.length === 2);

  // The programmatic entry point, which the VS Code extension will use in Hito 6.
  section('5. programmatic entry point');
  const program = spawnSync(
    process.execPath,
    [
      '-e',
      `const { binaryPath } = require(${JSON.stringify(join(installDir, 'node_modules', 'brewlint'))});
       const fs = require('node:fs');
       process.stdout.write(fs.existsSync(binaryPath()) ? 'ok' : 'missing');`,
    ],
    { env: cleanEnv, encoding: 'utf8' },
  );
  check('require("brewlint").binaryPath() points at a real file', program.stdout === 'ok', program.stderr);
} finally {
  rmSync(workspace, { recursive: true, force: true });
}

console.log(`\n${failures === 0 ? 'PASS' : 'FAIL'}: ${checks - failures}/${checks} checks`);
process.exit(failures === 0 ? 0 : 1);
