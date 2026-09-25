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

async function assemble(target) {
  const source = join(distDir, target.npmName);
  if (!existsSync(source)) {
    return { target, status: 'not built' };
  }

  const destination = join(platformDir, target.npmName);
  await rm(destination, { recursive: true, force: true });

  // The app image goes under bin/ so that the paths in lib/platforms.js read as
  // "bin/<executable>", and so a platform package contains exactly one thing.
  const binDirectory = join(destination, 'bin');
  await mkdir(binDirectory, { recursive: true });
  await cp(source, binDirectory, { recursive: true });

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
    const output = execFileSync('npm', ['pack', '--pack-destination', join(projectRoot, 'build')], {
      cwd: destination,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'inherit'],
    });
    tarball = output.trim().split('\n').pop();
  }

  return { target, status: 'assembled', destination, tarball };
}

const results = [];
for (const target of TARGETS) {
  results.push(await assemble(target));
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
if (built < TARGETS.length) {
  console.log(
    'Some targets are missing. That is expected on a single machine: jpackage cannot cross-compile, ' +
      '\nso each target is built on its own platform by the release workflow.',
  );
}
