#!/usr/bin/env node
// Builds a standalone package (no repository context needed): build/llminator-codex/ + build/llminator-codex.zip.
// Usage (repo root or cli/): node cli/pack.mjs
import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const cli = dirname(fileURLToPath(import.meta.url));
const repo = join(cli, '..');
const out = join(repo, 'build');
const name = 'llminator-codex';
const target = join(out, name);
const zip = join(out, `${name}.zip`);

rmSync(target, { recursive: true, force: true });
rmSync(zip, { force: true });
mkdirSync(join(target, 'demo'), { recursive: true });

cpSync(join(cli, 'control-layer.mjs'), join(target, 'control-layer.mjs'));
cpSync(join(cli, 'package', 'start-demo.mjs'), join(target, 'start-demo.mjs'));
cpSync(join(cli, 'package', 'README.md'), join(target, 'README.md'));
// Fictional data only: generated PESEL numbers (valid checksum) and example.com emails.
cpSync(join(cli, 'package', 'demo', 'customers.csv'), join(target, 'demo', 'customers.csv'));
writeFileSync(join(target, 'package.json'), JSON.stringify({
  name: 'llminator-codex', version: '0.1.0', private: true, type: 'module', engines: { node: '>=20' },
}, null, 2) + '\n');

// Windows 10+ ships bsdtar (System32\tar.exe), which writes zip with -a. Called by full path: a GNU tar
// from Git Bash earlier on PATH cannot write zip and reads "C:" as a remote host. Elsewhere use zip.
const result = process.platform === 'win32'
  ? spawnSync(join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'tar.exe'),
      ['-a', '-c', '-f', `${name}.zip`, name], { cwd: out, stdio: 'inherit' })
  : spawnSync('zip', ['-r', '-q', `${name}.zip`, name], { cwd: out, stdio: 'inherit' });
if (result.status !== 0 || !existsSync(zip)) {
  console.error(`Folder ready in ${target}, but creating the zip failed. Zip that folder manually.`);
  process.exit(1);
}
console.log(`Package: ${zip}`);
