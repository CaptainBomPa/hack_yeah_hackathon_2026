#!/usr/bin/env node
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync, renameSync, unlinkSync, openSync, closeSync } from 'node:fs';
import { homedir } from 'node:os';
import { delimiter, dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';

const OWNER = 'ai-control-layer/codex/v1';
const PROVIDER = 'ai_control_layer_cli_v1';
// Demo account from backend/config/users.yaml (public, hackathon only). Used when nothing else is given,
// so `run codex` works without install, --user or a password. Override with --user / CL_GATEWAY_PASSWORD.
export const DEMO_USER = 'codex-agent';
export const DEMO_PASSWORD = 'codex-agent-123';
const DEFAULT_GATEWAY = 'http://localhost:8000/v1';

export function defaultConfig() {
  return { owner: OWNER, enabled: true, gateway: DEFAULT_GATEWAY, user: DEMO_USER, billing: 'chatgpt-subscription' };
}

export function configPath(env = process.env, platform = process.platform, home = homedir()) {
  const root = platform === 'win32' ? (env.APPDATA || join(home, 'AppData', 'Roaming'))
    : platform === 'darwin' ? join(home, 'Library', 'Application Support')
    : (env.XDG_CONFIG_HOME || join(home, '.config'));
  return join(root, 'ai-control-layer', 'codex.json');
}

export function validate(config) {
  if (config.owner !== OWNER) throw new Error('This file is not owned by Control Layer; refusing to modify it.');
  const url = new URL(config.gateway);
  if (url.username || url.password || url.search || url.hash) throw new Error('Gateway URL must not contain credentials, query or fragment.');
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))) {
    throw new Error('Remote gateways require HTTPS.');
  }
  // Model is optional: without it Codex uses its own default and /model switches it as usual.
  if (config.model !== undefined && !/^[a-zA-Z0-9._:-]+$/.test(config.model)) throw new Error('Invalid model tag.');
  if (!config.user || /[:\r\n]/.test(config.user)) throw new Error('Invalid gateway username.');
  if (typeof config.enabled !== 'boolean') throw new Error('Invalid enabled flag.');
  if (config.credentialCommand && (!Array.isArray(config.credentialCommand)
      || !config.credentialCommand.length || config.credentialCommand.some(arg => typeof arg !== 'string'))) {
    throw new Error('Credential command must be a JSON array of executable and arguments.');
  }
  return config;
}

export function childSpec(config, args, env, password) {
  const childEnv = { ...env };
  // Keep API billing overrides and the raw gateway password out of the enabled child process.
  delete childEnv.OPENAI_API_KEY;
  delete childEnv.CODEX_API_KEY;
  delete childEnv.OPENAI_BASE_URL;
  delete childEnv.CL_GATEWAY_PASSWORD;
  delete childEnv.CL_CODEX_AUTH;
  if (!config.enabled) return { args: [...args], env: { ...env, CL_GATEWAY_PASSWORD: undefined, CL_CODEX_AUTH: undefined } };
  ensureGatewayRouting(args);
  if (!password) throw new Error('Provide a credential helper or CL_GATEWAY_PASSWORD for this process.');
  childEnv.CL_CODEX_AUTH = `Basic ${Buffer.from(`${config.user}:${password}`, 'utf8').toString('base64')}`;
  return { env: childEnv, args: [
    '-c', `model_provider=${JSON.stringify(PROVIDER)}`,
    '-c', `model_providers.${PROVIDER}.name="AI Control Layer"`,
    '-c', `model_providers.${PROVIDER}.base_url=${JSON.stringify(config.gateway)}`,
    '-c', `model_providers.${PROVIDER}.wire_api="responses"`,
    '-c', `model_providers.${PROVIDER}.requires_openai_auth=true`,
    '-c', `model_providers.${PROVIDER}.supports_websockets=false`,
    '-c', `model_providers.${PROVIDER}.env_http_headers.X-Control-Layer-Authorization="CL_CODEX_AUTH"`,
    '-c', 'forced_login_method="chatgpt"',
    '-c', 'web_search="disabled"',
    ...(config.model ? ['--model', config.model] : []), ...args,
  ] };
}

function ensureGatewayRouting(args) {
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (arg === '--oss' || arg === '--local-provider' || arg.startsWith('--local-provider=')) {
      throw new Error('Provider overrides are not allowed while gateway routing is enabled.');
    }
    const assignment = arg === '-c' || arg === '--config' ? args[++index]
      : arg.startsWith('--config=') ? arg.slice(9) : arg.startsWith('-c') ? arg.slice(2) : undefined;
    const key = assignment?.split('=')[0].trim();
    if (key && /^(model_provider(s)?(\.|$)|forced_login_method$|openai_base_url$|chatgpt_base_url$)/.test(key)) {
      throw new Error('Routing and authentication overrides are not allowed while gateway routing is enabled.');
    }
  }
}

export function requireChatgptLogin(command, env) {
  const result = spawnSync(command.binary, [...command.prefix, '-c', 'forced_login_method="chatgpt"', 'login', 'status'],
    { env, encoding: 'utf8', timeout: 10000, maxBuffer: 16384, shell: false });
  if (result.status !== 0 || !/logged in using chatgpt\b/i.test(`${result.stdout || ''}\n${result.stderr || ''}`)) {
    throw new Error('A ChatGPT login is required. Run codex login, then retry. API-key billing is not supported by this adapter.');
  }
}

function readConfig(path) { return validate(JSON.parse(readFileSync(path, 'utf8'))); }

function writeConfig(path, config) {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  const temp = `${path}.${randomUUID()}.tmp`;
  try {
    writeFileSync(temp, JSON.stringify(config, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
    renameSync(temp, path);
  } finally { if (existsSync(temp)) unlinkSync(temp); }
}

function locked(path, action) {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  const lock = `${path}.lock`;
  let fd;
  try { fd = openSync(lock, 'wx', 0o600); }
  catch { throw new Error('Another configuration operation is active; no files were modified.'); }
  try { return action(); }
  finally { closeSync(fd); unlinkSync(lock); }
}

/** No shell expansion: resolve the executable or the standard Windows npm shim's JS entry. */
function codexCommand(env) {
  const directories = (env.PATH || env.Path || '').split(delimiter);
  for (const directory of directories) {
    if (!directory) continue;
    if (process.platform !== 'win32') {
      const binary = join(directory, 'codex');
      if (existsSync(binary)) return { binary, prefix: [] };
    } else {
      const binary = join(directory, 'codex.exe');
      if (existsSync(binary)) return { binary, prefix: [] };
      const npmEntry = join(directory, 'node_modules', '@openai', 'codex', 'bin', 'codex.js');
      if (existsSync(npmEntry)) return { binary: process.execPath, prefix: [npmEntry] };
    }
  }
  throw new Error('Codex CLI was not found on PATH. Install the native or npm distribution.');
}

async function run(config, args) {
  validate(config);
  let password;
  if (config.enabled) {
    if (config.credentialCommand) {
      const [executable, ...helperArgs] = config.credentialCommand;
      try {
        password = execFileSync(executable, helperArgs, { encoding: 'utf8', timeout: 10000,
          maxBuffer: 16384, stdio: ['ignore', 'pipe', 'pipe'] }).trimEnd();
      } catch { throw new Error('Credential helper failed; gateway credentials were not printed.'); }
    } else password = process.env.CL_GATEWAY_PASSWORD || (config.user === DEMO_USER ? DEMO_PASSWORD : undefined);
  }
  const spec = childSpec(config, args, process.env, password);
  const command = codexCommand(process.env);
  // Let Codex use its native file/keyring store and refresh OAuth itself. Never read or copy auth.json.
  if (config.enabled) requireChatgptLogin(command, spec.env);
  const child = spawn(command.binary, [...command.prefix, ...spec.args], { env: spec.env, stdio: 'inherit', shell: false });
  const signals = ['SIGINT', 'SIGTERM', ...(process.platform === 'win32' ? [] : ['SIGHUP'])];
  const handlers = signals.map(signal => [signal, () => { if (!child.killed) child.kill(signal); }]);
  handlers.forEach(([signal, handler]) => process.on(signal, handler));
  return await new Promise((accept, reject) => {
    child.once('error', () => reject(new Error('Cannot launch Codex CLI.')));
    child.once('exit', (code, signal) => accept(code ?? (signal === 'SIGINT' ? 130 : 1)));
  }).finally(() => handlers.forEach(([signal, handler]) => process.removeListener(signal, handler)));
}

export async function main(argv, path = configPath()) {
  const [command, agent, ...args] = argv;
  if (!command || command === '--help' || command === 'help') {
    console.log('Control Layer: install|enable|disable|status|uninstall codex; run codex [-- agent arguments]\n'
      + `Install (optional; run works without it): [--user LOGIN, default ${DEMO_USER}] [--gateway URL] [--model TAG] [--credential-command JSON_ARRAY]\n`
      + 'Uses your existing ChatGPT subscription login. No OpenAI API key.\n'
      + 'Only our own profile is stored. Agent config and shell environment are never modified.');
    return 0;
  }
  if (agent !== 'codex') throw new Error('Only the Codex CLI adapter is currently supported.');
  if (command === 'run') {
    return run(existsSync(path) ? readConfig(path) : defaultConfig(), args[0] === '--' ? args.slice(1) : args);
  }
  if (command === 'status') {
    console.log(existsSync(path) ? JSON.stringify(readConfig(path), null, 2) : 'Not installed. Original Codex configuration is active.');
    return 0;
  }
  if (!['install', 'enable', 'disable', 'uninstall'].includes(command)) throw new Error('Unknown command. Use --help.');
  return locked(path, () => {
    if (command === 'install') {
      if (existsSync(path)) throw new Error('Already installed; uninstall first to change the integration profile.');
      const options = {};
      for (let index = 0; index < args.length; index += 2) {
        if (!['--gateway', '--model', '--user', '--credential-command'].includes(args[index]) || !args[index + 1]) {
          throw new Error('Invalid install options. Use --help.');
        }
        options[args[index]] = args[index + 1];
      }
      const config = validate({ owner: OWNER, enabled: true, gateway: options['--gateway'] || DEFAULT_GATEWAY,
        ...(options['--model'] ? { model: options['--model'] } : {}), user: options['--user'] || DEMO_USER, billing: 'chatgpt-subscription',
        ...(options['--credential-command'] ? { credentialCommand: JSON.parse(options['--credential-command']) } : {}) });
      writeConfig(path, config);
      console.log('Installed our Codex launcher profile. Run: control-layer run codex');
    } else if (!existsSync(path)) {
      if (command === 'uninstall') { console.log('Already uninstalled.'); return 0; }
      throw new Error('No integration installed.');
    } else {
      const config = readConfig(path); // Refuse to delete foreign/invalid files.
      if (command === 'uninstall') {
        unlinkSync(path);
        console.log('Integration removed. Original Codex configuration was never modified.');
      } else {
        writeConfig(path, { ...config, enabled: command === 'enable' });
        console.log(command === 'enable' ? 'Gateway routing enabled for the launcher.' : 'Launcher now runs Codex with its original configuration.');
      }
    }
    return 0;
  });
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }).catch(error => {
    // Parse errors can contain file fragments; never print arbitrary exceptions.
    const message = error instanceof SyntaxError ? 'Invalid configuration JSON.' : error.message;
    console.error(message);
    process.exitCode = 1;
  });
}
