// 本地/CI用OpenAI兼容受控模型。不转发外部请求，不记录凭证或报告正文。
import http from 'node:http'

http.createServer(async (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  if (!['/v1/chat/completions', '/chat/completions'].includes(req.url) || req.method !== 'POST') {
    res.writeHead(404).end('{}')
    return
  }
  try {
    let body = ''
    for await (const chunk of req) {
      body += chunk
      if (body.length > 100_000) throw new Error('request too large')
    }
    const request = JSON.parse(body)
    if (request.tool_choice !== 'none' || (request.tools?.length ?? 0) > 0) throw new Error('tools must be disabled')
    const facts = JSON.parse(request.messages.find(message => message.role === 'user').content)
    const ids = ['orders', 'guarantee', 'premium', ...facts.filter(fact => fact.kind === 'DISTRIBUTION').slice(0, 2).map(fact => fact.id)]
    res.end(JSON.stringify({ id: 'controlled-summary', object: 'chat.completion', created: Math.floor(Date.now() / 1000), model: 'controlled-analysis',
      choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify({ evidenceIds: ids }) }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 100, completion_tokens: 30, total_tokens: 130 } }))
  } catch {
    res.writeHead(400).end(JSON.stringify({ error: { message: 'invalid controlled analysis request' } }))
  }
}).listen(18094, '127.0.0.1', () => console.log('Controlled analysis model: 127.0.0.1:18094'))
