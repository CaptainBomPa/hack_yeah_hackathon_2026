#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Local credential helper. Resolve against this file, not the caller's directory.
try {
  const file = process.argv[2] || fileURLToPath(new URL('../.env.local', import.meta.url));
  const values = readFileSync(file, 'utf8').split(/\r?\n/)
    .filter(line => line.startsWith('CL_GATEWAY_PASSWORD='))
    .map(line => line.slice('CL_GATEWAY_PASSWORD='.length));
  if (values.length !== 1 || !values[0].trim()) throw new Error();
  process.stdout.write(values[0]);
} catch {
  console.error('Cannot read gateway password: expected one nonempty CL_GATEWAY_PASSWORD in the credentials file.');
  process.exitCode = 1;
}
