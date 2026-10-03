#!/usr/bin/env node
// Odpala listę promptów przez gateway i zapisuje raport HTML: prompt -> przeszedł / odrzucony.
// Bez zależności, Node >= 18.
//
//   node tests/run.mjs                          # tests/prompts.json -> tests/reports/report.html
//   node tests/run.mjs moje-prompty.json        # inny plik wejściowy
//   node tests/run.mjs --mock                   # bez Ollamy: atrapa modelu na :11434 (tests/mock-model.mjs)
//
// Wejście: tablica JSON stringów (promptów).
// Zmienne: GATEWAY_URL (http://localhost:8000), CL_USER / CL_PASSWORD (chat1 / chat1),
//          CL_MODEL (qwen2.5:0.5b), REPORT_DIR (tests/reports).

import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { startMockModel } from './mock-model.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const useMock = args.includes('--mock')
const inputFile = resolve(args.find((a) => !a.startsWith('--')) ?? join(here, 'prompts.json'))
const gateway = (process.env.GATEWAY_URL ?? 'http://localhost:8000').replace(/\/$/, '')
const user = process.env.CL_USER ?? 'chat1'
const password = process.env.CL_PASSWORD ?? 'chat1'
const model = process.env.CL_MODEL ?? 'qwen2.5:0.5b'
const outDir = resolve(process.env.REPORT_DIR ?? join(here, 'reports'))

const prompts = JSON.parse(await readFile(inputFile, 'utf8'))
if (!Array.isArray(prompts) || prompts.some((p) => typeof p !== 'string')) {
  throw new Error(`${inputFile}: oczekiwano tablicy stringów`)
}

// Przeszedł = gateway wpuścił (allow/redact). Odrzucony = zablokowała go kontrola.
// Błąd = brak decyzji kontroli (gateway albo model niedostępny) — nie udajemy, że to blokada.
async function verdict(prompt) {
  try {
    const res = await fetch(`${gateway}/v1/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Basic ' + Buffer.from(`${user}:${password}`).toString('base64'),
      },
      signal: AbortSignal.timeout(90_000),
      body: JSON.stringify({ model, messages: [{ role: 'user', content: prompt }] }),
    })
    const body = await res.json().catch(() => null)
    if (res.ok) return 'przeszedł'
    if (res.status === 401 || res.status === 403) return 'odrzucony'
    return body?.blockedBy === 'upstream-error' ? 'błąd: model niedostępny' : `błąd: HTTP ${res.status}`
  } catch (err) {
    return `błąd: ${err.cause?.code ?? err.message}`
  }
}

const mock = useMock ? await startMockModel() : null
const results = []
try {
  for (const prompt of prompts) {
    const result = await verdict(prompt)
    results.push({ prompt, result })
    console.log(`${result.padEnd(24)} ${prompt}`)
  }
} finally {
  mock?.close()
}

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
const kind = (r) => (r === 'przeszedł' ? 'ok' : r === 'odrzucony' ? 'no' : 'err')
const icon = { ok: '✓', no: '✕', err: '!' }
const count = (k) => results.filter((r) => kind(r.result) === k).length
const [ok, no, err] = ['ok', 'no', 'err'].map(count)
const pct = (n) => (results.length ? (100 * n) / results.length : 0)

const html = `<!doctype html>
<html lang="pl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Raport Control Layer</title>
<style>
  :root { --bg:#f6f7f9; --card:#fff; --fg:#1a1d21; --muted:#6b7280; --line:#e5e7eb;
          --ok:#16a34a; --ok-bg:#dcfce7; --no:#dc2626; --no-bg:#fee2e2; --err:#6b7280; --err-bg:#f3f4f6; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#0b0f14; --card:#131a22; --fg:#e6edf3; --muted:#8b949e; --line:#232c36;
            --ok:#4ade80; --ok-bg:#12301e; --no:#f87171; --no-bg:#3a1416; --err:#9ca3af; --err-bg:#1f2630; }
  }
  * { box-sizing:border-box; }
  body { background:var(--bg); color:var(--fg); font:15px/1.5 system-ui, -apple-system, sans-serif; margin:0; padding:32px 16px; }
  main { max-width:860px; margin:0 auto; }
  h1 { font-size:22px; margin:0 0 4px; letter-spacing:-.01em; }
  .sub { color:var(--muted); font-size:13px; margin-bottom:20px; }
  .stats { display:flex; gap:12px; flex-wrap:wrap; margin-bottom:12px; }
  .stat { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:12px 16px; min-width:120px; }
  .stat b { display:block; font-size:26px; line-height:1.1; }
  .stat span { color:var(--muted); font-size:12px; text-transform:uppercase; letter-spacing:.04em; }
  .stat.ok b { color:var(--ok); } .stat.no b { color:var(--no); } .stat.err b { color:var(--err); }
  .bar { display:flex; height:8px; border-radius:4px; overflow:hidden; background:var(--line); margin-bottom:24px; }
  .bar i { display:block; } .bar .ok { background:var(--ok); } .bar .no { background:var(--no); } .bar .err { background:var(--err); }
  ol { list-style:none; margin:0; padding:0; display:grid; gap:8px; }
  li { display:flex; align-items:flex-start; gap:12px; background:var(--card); border:1px solid var(--line);
       border-left:4px solid var(--c); border-radius:10px; padding:12px 14px; }
  li.ok { --c:var(--ok); --cb:var(--ok-bg); } li.no { --c:var(--no); --cb:var(--no-bg); } li.err { --c:var(--err); --cb:var(--err-bg); }
  .n { color:var(--muted); font-size:12px; min-width:20px; padding-top:3px; font-variant-numeric:tabular-nums; }
  .p { flex:1; white-space:pre-wrap; word-break:break-word; }
  .badge { flex-shrink:0; display:inline-flex; align-items:center; gap:6px; background:var(--cb); color:var(--c);
           font-weight:700; font-size:13px; padding:3px 10px; border-radius:999px; white-space:nowrap; }
</style>
</head>
<body>
<main>
<h1>Raport Control Layer</h1>
<div class="sub">${esc(new Date().toLocaleString('pl-PL'))} · ${results.length} promptów · model <code>${esc(model)}</code>${useMock ? ' (mock)' : ''}</div>
<div class="stats">
  <div class="stat ok"><b>${ok}</b><span>przeszło</span></div>
  <div class="stat no"><b>${no}</b><span>odrzucone</span></div>
  ${err ? `<div class="stat err"><b>${err}</b><span>błędy</span></div>` : ''}
</div>
<div class="bar"><i class="ok" style="width:${pct(ok)}%"></i><i class="no" style="width:${pct(no)}%"></i><i class="err" style="width:${pct(err)}%"></i></div>
<ol>
${results.map((r, i) => {
  const k = kind(r.result)
  return `<li class="${k}"><span class="n">${i + 1}</span><span class="p">${esc(r.prompt)}</span><span class="badge">${icon[k]} ${esc(r.result)}</span></li>`
}).join('\n')}
</ol>
</main>
</body>
</html>
`

await mkdir(outDir, { recursive: true })
const out = join(outDir, 'report.html')
await writeFile(out, html, 'utf8')
console.log(`\nprzeszło ${ok} · odrzucone ${no}${err ? ` · błędy ${err}` : ''}`)
console.log(`Raport: ${out}`)
