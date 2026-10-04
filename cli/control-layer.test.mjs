import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, basename, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { configPath, childSpec, main, validate, requireChatgptLogin, defaultConfig, DEMO_USER, DEMO_PASSWORD } from './control-layer.mjs';

const profile = { owner: 'ai-control-layer/codex/v1', enabled: true,
  gateway: 'http://localhost:8000/v1', user: 'agent', model: 'test-model' };

function cleanup(root) {
  const absolute = resolve(root);
  assert.equal(dirname(absolute), resolve(tmpdir()));
  assert.ok(basename(absolute).startsWith('control-layer-test-'));
  rmSync(absolute, { recursive: true, force: true });
}

test('child-only credentials and routing leave parent environment unchanged', () => {
  const parent = { PATH: '/bin', CL_GATEWAY_PASSWORD: 'secret', OPENAI_API_KEY: 'provider-secret', OPENAI_BASE_URL: 'original' };
  const snapshot = { ...parent };
  const child = childSpec(profile, ['exec', 'say hello'], parent, 'secret');
  assert.deepEqual(parent, snapshot);
  assert.equal(child.env.CL_GATEWAY_PASSWORD, undefined);
  assert.equal(child.env.OPENAI_API_KEY, undefined);
  assert.equal(child.env.CL_CODEX_AUTH, 'Basic ' + Buffer.from('agent:secret').toString('base64'));
  assert.ok(!child.args.join(' ').includes('secret'));
  assert.ok(child.args.includes('exec'));
  assert.ok(child.args.includes('forced_login_method="chatgpt"'));
  assert.ok(child.args.some(arg => arg.endsWith('.requires_openai_auth=true')));
  assert.ok(child.args.some(arg => arg.includes('env_http_headers.X-Control-Layer-Authorization')));
  assert.ok(!child.args.some(arg => arg.includes('env_http_headers.Authorization')));
});

test('subscription launch keeps the native credential store and removes API billing overrides', () => {
  const parent = { CODEX_HOME: '/existing/home', CODEX_API_KEY: 'api-secret', OPENAI_BASE_URL: 'https://api.openai.com/v1' };
  const child = childSpec(profile, [], parent, 'secret');
  assert.equal(child.env.CODEX_HOME, '/existing/home');
  assert.equal(child.env.CODEX_API_KEY, undefined);
  assert.equal(child.env.OPENAI_BASE_URL, undefined);
  assert.equal(parent.CODEX_API_KEY, 'api-secret');
});

test('agent arguments cannot override the enabled routing or authentication', () => {
  for (const args of [['-c', 'model_provider="openai"'], ['--config=forced_login_method="api"'],
    ['-cmodel_providers.other.base_url="https://api.openai.com/v1"'], ['--oss']]) {
    assert.throws(() => childSpec(profile, args, {}, 'secret'), /overrides are not allowed/);
  }
  assert.ok(childSpec(profile, ['-c', 'model_reasoning_effort="low"'], {}, 'secret').args.includes('model_reasoning_effort="low"'));
});

test('preflight accepts ChatGPT login and rejects API login without printing credentials', () => {
  const root = mkdtempSync(join(tmpdir(), 'control-layer-test-'));
  try {
    const entry = join(root, 'fake-codex.cjs');
    writeFileSync(entry, 'process.stderr.write(process.env.TEST_AUTH_STATUS); process.exit(Number(process.env.TEST_AUTH_EXIT || 0));');
    const command = { binary: process.execPath, prefix: [entry] };
    requireChatgptLogin(command, { ...process.env, TEST_AUTH_STATUS: 'Logged in using ChatGPT' });
    assert.throws(() => requireChatgptLogin(command, { ...process.env, TEST_AUTH_STATUS: 'Logged in using an API key: secret' }),
      /ChatGPT login is required/);
    assert.throws(() => requireChatgptLogin(command, { ...process.env, TEST_AUTH_STATUS: 'ChatGPT login missing', TEST_AUTH_EXIT: '1' }),
      /ChatGPT login is required/);
  } finally { cleanup(root); }
});

test('install/disable/enable/uninstall never modify original agent files', async () => {
  const root = mkdtempSync(join(tmpdir(), 'control-layer-test-'));
  try {
    const path = join(root, 'owned', 'codex.json');
    const original = join(root, 'config.toml');
    writeFileSync(original, 'model_provider = "original"\n# my settings\n');
    await main(['install', 'codex', '--user', 'agent'], path);
    assert.equal(JSON.parse(readFileSync(path, 'utf8')).model, undefined);
    await assert.rejects(main(['install', 'codex', '--model', 'other', '--user', 'agent'], path));
    await main(['disable', 'codex'], path);
    const disabled = JSON.parse(readFileSync(path, 'utf8'));
    const native = childSpec(disabled, ['exec', 'hello'], { OPENAI_API_KEY: 'original-key' });
    assert.deepEqual(native.args, ['exec', 'hello']);
    assert.equal(native.env.OPENAI_API_KEY, 'original-key');
    await main(['enable', 'codex'], path);
    assert.equal(JSON.parse(readFileSync(path, 'utf8')).enabled, true);
    // User edits their original settings after installation; uninstall must retain those edits.
    writeFileSync(original, 'model_provider = "new-original"\n');
    await main(['uninstall', 'codex'], path);
    assert.equal(existsSync(path), false);
    assert.equal(readFileSync(original, 'utf8'), 'model_provider = "new-original"\n');
    await main(['uninstall', 'codex'], path);
  } finally { cleanup(root); }
});

test('uninstall refuses foreign files and remote plain HTTP', async () => {
  const root = mkdtempSync(join(tmpdir(), 'control-layer-test-'));
  try {
    const path = join(root, 'codex.json');
    writeFileSync(path, '{"owner":"another-app"}');
    await assert.rejects(main(['uninstall', 'codex'], path));
    assert.equal(existsSync(path), true);
    assert.throws(() => validate({ ...profile, gateway: 'http://remote.example/v1' }));
    assert.throws(() => validate({ ...profile, gateway: 'https://user:secret@example.com/v1' }));
  } finally { cleanup(root); }
});

test('configuration paths support Windows, macOS and Linux', () => {
  assert.equal(configPath({ APPDATA: '/win' }, 'win32', '/home'), join('/win', 'ai-control-layer', 'codex.json'));
  assert.ok(configPath({}, 'darwin', '/home').includes('Application Support'));
  assert.equal(configPath({ XDG_CONFIG_HOME: '/xdg' }, 'linux', '/home'), join('/xdg', 'ai-control-layer', 'codex.json'));
});

test('actual child process receives isolated auth without exposing raw secrets', () => {
  const parent = { ...process.env, CL_GATEWAY_PASSWORD: 'secret', OPENAI_API_KEY: 'provider-secret' };
  const child = childSpec(profile, [], parent, 'secret');
  const result = spawnSync(process.execPath, ['-e', 'process.stdout.write(JSON.stringify({'
    + 'rawPassword:!!process.env.CL_GATEWAY_PASSWORD,providerKey:!!process.env.OPENAI_API_KEY,'
    + 'gatewayAuth:process.env.CL_CODEX_AUTH?.startsWith("Basic ")}))'], { env: child.env, encoding: 'utf8', shell: false });
  assert.equal(result.status, 0);
  assert.deepEqual(JSON.parse(result.stdout), { rawPassword: false, providerKey: false, gatewayAuth: true });
  assert.equal(parent.CL_CODEX_AUTH, undefined);
  assert.equal(parent.CL_GATEWAY_PASSWORD, 'secret');
});

test('model is optional: without it Codex keeps its own default and /model', () => {
  const { model, ...noModel } = profile;
  assert.ok(!childSpec(noModel, ['exec', 'hi'], {}, 'secret').args.includes('--model'));
  const pinned = childSpec(profile, ['exec', 'hi'], {}, 'secret').args;
  assert.equal(pinned[pinned.indexOf('--model') + 1], 'test-model');
  assert.throws(() => validate({ ...profile, model: 'bad tag' }), /Invalid model tag/);
});

test('works out of the box with the demo account: no install, --user or password needed', async () => {
  const config = defaultConfig();
  assert.equal(config.user, DEMO_USER);
  assert.equal(config.gateway, 'https://apillminator.fmroz.me/v1');
  validate(config);
  const spec = childSpec(config, [], {}, DEMO_PASSWORD);
  assert.equal(spec.env.CL_CODEX_AUTH, `Basic ${Buffer.from(`${DEMO_USER}:${DEMO_PASSWORD}`).toString('base64')}`);
  const root = mkdtempSync(join(tmpdir(), 'control-layer-test-'));
  try {
    const path = join(root, 'owned', 'codex.json');
    await main(['install', 'codex'], path);
    assert.equal(JSON.parse(readFileSync(path, 'utf8')).user, DEMO_USER);
  } finally { cleanup(root); }
});
