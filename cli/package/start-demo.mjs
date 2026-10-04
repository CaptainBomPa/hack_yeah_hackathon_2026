#!/usr/bin/env node
// Starts Codex CLI through LLMinator in a fresh demo folder (default: ~/llminator-demo) that contains
// only the fictional customers.csv. The folder is recreated on every start, so each recording is clean.
// Usage: node start-demo.mjs [--dir PATH] [-- extra Codex arguments]
import { spawn } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const launcher = join(here, 'control-layer.mjs');
const data = join(here, 'demo', 'customers.csv');

const args = process.argv.slice(2);
const dirIndex = args.indexOf('--dir');
const workdir = resolve(dirIndex >= 0 ? args[dirIndex + 1] : join(homedir(), 'llminator-demo'));
const separator = args.indexOf('--');
const codexArgs = separator >= 0 ? args.slice(separator + 1) : [];

// Never wipe or work inside this package: the agent should see only the demo data.
if (workdir === here || workdir.startsWith(here + sep) || here.startsWith(workdir + sep)) {
  console.error('Pick a demo folder outside this package: ' + workdir);
  process.exit(1);
}
if (existsSync(workdir)) rmSync(workdir, { recursive: true, force: true });
mkdirSync(workdir, { recursive: true });
cpSync(data, join(workdir, 'customers.csv'));
console.log(`Demo folder: ${workdir} (customers.csv)`);

const child = spawn(process.execPath, [launcher, 'run', 'codex', ...(codexArgs.length ? ['--', ...codexArgs] : [])],
  { cwd: workdir, stdio: 'inherit' });
child.on('exit', (code) => process.exit(code ?? 1));
