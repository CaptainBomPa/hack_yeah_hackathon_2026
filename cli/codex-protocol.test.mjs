import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { childSpec, requireChatgptLogin } from './control-layer.mjs';

const entry = process.env.CL_CODEX_TEST_ENTRY;

// An actual Codex binary, fake subscription credentials and loopback only. Never uses the user's login.
test('native Codex sends ChatGPT OAuth through the configured gateway', { skip: !entry, timeout: 60000 }, async () => {
  const root = mkdtempSync(join(tmpdir(), 'control-layer-test-'));
  const home = join(root, 'codex-home');
  mkdirSync(home);
  const token = payload => ['eyJhbGciOiJub25lIn0', Buffer.from(JSON.stringify(payload)).toString('base64url'), 'fixture'].join('.');
  const auth = JSON.stringify({ OPENAI_API_KEY: null, tokens: {
    id_token: token({ exp: 4102444800, email: 'fixture@example.invalid',
      'https://api.openai.com/auth': { chatgpt_account_id: 'fixture-account', chatgpt_plan_type: 'plus', user_id: 'fixture-user' } }),
    access_token: token({ exp: 4102444800 }), refresh_token: 'fixture-refresh', account_id: 'fixture-account',
  }, last_refresh: new Date().toISOString() });
  const config = 'cli_auth_credentials_store = "file"\ncheck_for_update_on_startup = false\n[features]\napps = false\n';
  writeFileSync(join(home, 'auth.json'), auth);
  writeFileSync(join(home, 'config.toml'), config);
  const received = [];
  const message = { type: 'message', id: 'msg_fixture', role: 'assistant', status: 'completed',
    content: [{ type: 'output_text', text: 'SUBSCRIPTION_PROXY_OK', annotations: [] }] };
  const events = [
    { type: 'response.created', response: { id: 'resp_fixture', status: 'in_progress' } },
    { type: 'response.output_item.added', output_index: 0, item: { ...message, status: 'in_progress', content: [] } },
    { type: 'response.output_text.delta', output_index: 0, content_index: 0, item_id: message.id, delta: 'SUBSCRIPTION_PROXY_OK' },
    { type: 'response.output_item.done', output_index: 0, item: message },
    { type: 'response.completed', response: { id: 'resp_fixture', object: 'response', status: 'completed',
      output: [message], usage: { input_tokens: 10, output_tokens: 2, total_tokens: 12 } } },
  ];
  const server = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    received.push({ path: request.url, headers: request.headers, body: Buffer.concat(chunks).toString('utf8') });
    if (request.url.startsWith('/v1/models')) {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end('{"models":[]}');
      return;
    }
    if (request.url !== '/v1/responses') { response.writeHead(404).end(); return; }
    response.writeHead(200, { 'Content-Type': 'text/event-stream' });
    response.end(events.map(event => `data: ${JSON.stringify(event)}\n\n`).join(''));
  });
  await new Promise(accept => server.listen(0, '127.0.0.1', accept));
  let child;
  try {
    const profile = { owner: 'ai-control-layer/codex/v1', enabled: true,
      gateway: `http://127.0.0.1:${server.address().port}/v1`, user: 'fixture-agent', model: 'gpt-6.1-sol' };
    const spec = childSpec(profile, ['exec', '--skip-git-repo-check', '--sandbox', 'read-only', 'Reply with a short greeting.'],
      { ...process.env, CODEX_HOME: home, OPENAI_API_KEY: 'must-not-be-used', CODEX_API_KEY: 'must-not-be-used' }, 'fixture-password');
    requireChatgptLogin({ binary: process.execPath, prefix: [entry] }, spec.env);
    child = spawn(process.execPath, [entry, ...spec.args], { cwd: root, env: spec.env, shell: false, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    const timeout = setTimeout(() => child.kill(), 45000);
    const status = await new Promise((accept, reject) => {
      child.once('error', reject);
      child.once('exit', accept);
    }).finally(() => clearTimeout(timeout));
    assert.equal(status, 0, stderr.replaceAll('subscription-fixture-access', '[fixture]'));
    assert.ok(stdout.includes('SUBSCRIPTION_PROXY_OK'));
    const inference = received.filter(request => request.path === '/v1/responses');
    assert.equal(inference.length, 1);
    assert.equal(inference[0].headers.authorization, 'Bearer ' + JSON.parse(auth).tokens.access_token);
    assert.equal(inference[0].headers['chatgpt-account-id'], 'fixture-account');
    assert.equal(inference[0].headers['x-control-layer-authorization'],
      'Basic ' + Buffer.from('fixture-agent:fixture-password').toString('base64'));
    assert.equal(JSON.parse(inference[0].body).stream, true);
    assert.equal(JSON.parse(inference[0].body).store, false);
    assert.equal(JSON.parse(inference[0].body).max_output_tokens, undefined);
    const request = JSON.parse(inference[0].body);
    const tools = [...(request.tools || []),
      ...request.input.filter(item => item.type === 'additional_tools').flatMap(item => item.tools)];
    assert.ok(tools.length > 0, 'Native tool definitions must be inspected in either wire location.');
    const supported = tool => ['function', 'custom'].includes(tool.type)
      || (tool.type === 'namespace' && tool.tools.every(supported));
    assert.ok(tools.every(supported), 'All native tool shapes must be supported by the gateway validator.');
    const discovery = received.filter(request => request.path.startsWith('/v1/models'));
    assert.ok(discovery.length > 0);
    assert.equal(discovery[0].headers.authorization, inference[0].headers.authorization);
    assert.equal(discovery[0].headers['x-control-layer-authorization'], inference[0].headers['x-control-layer-authorization']);
    assert.equal(readFileSync(join(home, 'auth.json'), 'utf8'), auth);
    assert.equal(readFileSync(join(home, 'config.toml'), 'utf8'), config);
  } finally {
    if (child && child.exitCode === null) child.kill();
    await new Promise(accept => server.close(accept));
    const absolute = resolve(root);
    assert.equal(dirname(absolute), resolve(tmpdir()));
    assert.ok(basename(absolute).startsWith('control-layer-test-'));
    rmSync(absolute, { recursive: true, force: true });
  }
});
