// Atrapa modelu: OpenAI-compatible POST /v1/chat/completions, tak jak wystawia go Ollama.
// Pozwala odpalić runner bez Ollamy — decyzje dalej podejmuje prawdziwy gateway, podmieniony jest
// tylko model. Domyślnie port 11434, czyli tam, gdzie backend szuka Ollamy (OLLAMA_BASE_URL).

import { createServer } from 'node:http'

export function startMockModel(port = 11434) {
  const server = createServer((req, res) => {
    if (req.method !== 'POST' || !req.url.startsWith('/v1/chat/completions')) {
      res.writeHead(404).end()
      return
    }
    let raw = ''
    req.on('data', (chunk) => (raw += chunk))
    req.on('end', () => {
      const body = JSON.parse(raw || '{}')
      const last = body.messages?.at(-1)?.content ?? ''
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({
        choices: [{ message: { role: 'assistant', content: `[mock] otrzymałem: ${last}` } }],
        usage: { prompt_tokens: last.length, completion_tokens: 8 },
      }))
    })
  })
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, () => resolve(server))
  })
}
