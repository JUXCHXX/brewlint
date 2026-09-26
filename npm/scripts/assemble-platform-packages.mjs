#!/usr/bin/env node
'use strict';

/**
 * Assembles the per-platform npm packages from the app images in dist/.
 *
 * <p>Why one package per platform instead of one package with every binary: the total is about
 * 150 MB, and a developer on a laptop should not download the Windows and Linux runtimes to lint a
 * Spring Boot app on macOS. Each platform package declares {@code os} and {@code cpu}, so npm skips
 * the ones that cannot run. The main package lists all of them as {@code optionalDependencies}
 * rather than {@code dependencies}, so a platform we do not ship never fails the install.
 *
 * <p>Usage:
 *   node npm/scripts/assemble-platform-packages.mjs [--pack]
 *
 *   --pack   also run `npm pack`, producing a tarball next to the assembled package
 */

import { existsSync } from 'node:fs';
import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDir, '..', '..');
const distDir = join(projectRoot, 'dist');
const platformDir = join(projectRoot, 'npm', 'platform');

/**
 * The shipped targets. {@code jpackageTarget} is the triple scripts/build-runtime.sh expects, and
 * {@code npmName} is the directory name under dist/.
 */
const TARGETS = [
  { npmName: 'macos-arm64', jpackageTarget: 'macos-aarch64', os: ['darwin'], cpu: ['arm64'] },
  { npmName: 'linux-x64', jpackageTarget: 'linux-x64', os: ['linux'], cpu: ['x64'] },
  { npmName: 'win-x64', jpackageTarget: 'windows-x64', os: ['win32'], cpu: ['x64'] },
];

const pack = process.argv.includes('--pack');

const mainPackage = JSON.parse(
  await readFile(join(projectRoot, 'npm', 'brewlint', 'package.json'), 'utf8'),
);
const version = mainPackage.version;

function source(target) {
  return join(distDir, target.npmName);
}

async function assemble(target, launcherPath) {
  if (!existsSync(source(target))) {
    return { target, status: 'not built' };
  }

  const destination = join(platformDir, target.npmName);
  await rm(destination, { recursive: true, force: true });

  // The app image goes under bin/ so that the paths in lib/platforms.js read as
  // "bin/<executable>", and so a platform package contains exactly one thing.
  const binDirectory = join(destination, 'bin');
  await mkdir(binDirectory, { recursive: true });
  await cp(source(target), binDirectory, { recursive: true });

  if (launcherPath) {
    // Recorded in the platform package so the shim's table can be checked against reality rather
    // than against a comment that says what the layout ought to be.
    await writeFile(
      join(destination, 'launcher-path.txt'),
      `${launcherPath}\n`,
    );
  }

  const packageJson = {
    name: `@brewlint/${target.npmName}`,
    version,
    description:
      `The Brewlint binary for ${target.os[0]} ${target.cpu[0]}. ` +
      'Installed automatically as an optional dependency of the brewlint package; ' +
      'there is no reason to install it directly.',
    homepage: mainPackage.homepage,
    bugs: mainPackage.bugs,
    repository: mainPackage.repository,
    license: mainPackage.license,
    author: mainPackage.author,
    // These two fields are the whole mechanism. npm reads them and skips the package on a machine
    // where it cannot run, which is why the main package can list all three without any of them
    // being a hard requirement.
    os: target.os,
    cpu: target.cpu,
    files: ['bin/'],
    publishConfig: { access: 'public' },
  };
  await writeFile(join(destination, 'package.json'), `${JSON.stringify(packageJson, null, 2)}\n`);

  let tarball = null;
  if (pack) {
    tarball = packPackage(destination);
  }

  return { target, status: 'assembled', destination, tarball, launcherPath };
}

/**
 * Runs `npm pack` and returns the file it produced.
 *
 * <p>With `shell: true` on Windows, because npm is a .cmd there and spawnSync cannot execute a
 * .cmd without a shell. The `{windowsHide: true}` keeps a console window from flashing on every
 * call. Everywhere else the binary is invoked directly, so a path with a space in it stays a single
 * argument instead of being word-split by a shell.
 */
function packPackage(cwd) {
  const useShell = process.platform === 'win32';
  const output = execFileSync(
    'npm',
    ['pack', '--pack-destination', join(projectRoot, 'build')],
    { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'], shell: useShell, windowsHide: true },
  );
  return output.trim().split('\n').pop();
}

/**
 * The launcher's path inside the app image, as recorded by scripts/build-runtime.sh.
 *
 * <p>Read rather than hardcoded. The jpackage layout differs per platform (a .app bundle on macOS, a
 * directory on Linux) and has changed between versions, so guessing it here is how a build ends up
 * green while producing a package with no binary in it. The build script finds it and writes it down,
 * and this is the only place that reads it.
 */
async function readLauncherPath(target) {
  const manifest = join(distDir, `launcher-${target.npmName}.txt`);
  if (!existsSync(manifest)) {
    return null;
  }
  const recorded = (await readFile(manifest, 'utf8')).trim();
  return recorded.length > 0 ? recorded : null;
}

const results = [];
for (const target of TARGETS) {
  const launcherPath = await readLauncherPath(target);
  if (launcherPath && !existsSync(join(source(target), launcherPath))) {
    console.error(
      `  ${target.npmName.padEnd(12)} error: the build recorded the launcher at "${launcherPath}" ` +
        `but that file is not in dist/${target.npmName}. Rebuild before assembling.`,
    );
    process.exit(1);
  }
  results.push(await assemble(target, launcherPath));
}

console.log('');
for (const result of results) {
  const { target } = result;
  if (result.status === 'not built') {
    console.log(
      `  ${target.npmName.padEnd(12)} not built   ` +
        `run scripts/build-runtime.sh ${target.jpackageTarget} on that platform`,
    );
  } else {
    console.log(`  ${target.npmName.padEnd(12)} ${result.status}  ${result.destination}`);
    if (result.tarball) {
      console.log(`  ${''.padEnd(12)} tarball   ${result.tarball}`);
    }
  }
}
console.log('');

const built = results.filter((result) => result.status === 'assembled').length;
if (built === 0) {
  console.error('No platform packages were assembled. Run scripts/build-runtime.sh first.');
  process.exit(1);
}

// The shim looks the binary up at the path in lib/platforms.js. If the app image layout is not what
// that file claims, the install succeeds and then fails at the first run, which is the worst moment
// to discover it. Comparing the two here turns that into a build failure.
if (built > 0) {
  const { BINARIES } = await import(
    new URL('../brewlint/lib/platforms.js', import.meta.url).href
  );
  const key = { 'macos-arm64': 'darwin-arm64', 'linux-x64': 'linux-x64', 'win-x64': 'win32-x64' };
  const mismatches = [];
  for (const result of results) {
    if (result.status !== 'assembled' || !result.launcherPath) continue;
    const expected = BINARIES[key[result.target.npmName]]?.executable;
    if (expected && expected !== `bin/${result.launcherPath}`) {
      mismatches.push(
        `${result.target.npmName}: lib/platforms.js says "${expected}" but the build produced ` +
          `"bin/${result.launcherPath}". Update the table in lib/platforms.js.`,
      );
    }
  }
  if (mismatches.length > 0) {
    console.error('\nThe launcher path in lib/platforms.js is out of date:');
    for (const line of mismatches) {
      console.error(`  ${line}`);
    }
    process.exit(1);
  }
  console.log('  launcher paths in lib/platforms.js match the build\n');
}
if (built < TARGETS.length) {
  console.log(
    'Some targets are missing. That is expected on a single machine: jpackage cannot cross-compile, ' +
      '\nso each target is built on its own platform by the release workflow.',
  );
}
