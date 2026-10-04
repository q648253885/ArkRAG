#!/usr/bin/env node
/**
 * ArkRAG 测试辅助：OpenAI 兼容 mock embeddings 服务（离线冒烟用）。
 * 语义规则（可测的"伪语义"）：文本向量由少量关键词位叠加决定——
 *   含 embedding/模型/维度 → 主成分 1；含 检索/搜索/查询 → 主成分 2；否则随机扰动。
 * 同类问题余弦相近，便于验证"相关排序"与"维度护栏"（改变 ?dims= 即可触发 mismatch）。
 * 用法：node scripts/mock-embedding-server.mjs [port] [dims]
 */
import http from 'node:http'

const port = Number(process.argv[2] || 8965)
const dims = Number(process.argv[3] || 1024)

function embed(text) {
  const vec = new Array(dims).fill(0)
  let h = 2166136261
  for (const ch of text) {
    h ^= ch.codePointAt(0)
    h = Math.imul(h, 16777619) >>> 0
    vec[h % dims] += 0.05
  }
  const t = String(text)
  if (/embedding|模型|维度|bge|ollama/i.test(t)) vec[0] += 1
  if (/检索|搜索|查询|search/i.test(t)) vec[1] += 1
  if (/配置|config|yml|启动/i.test(t)) vec[2] += 1
  const norm = Math.sqrt(vec.reduce((s, v) => s + v * v, 0)) || 1
  return vec.map((v) => v / norm)
}

http.createServer((req, res) => {
  if (req.method === 'POST' && req.url.endsWith('/embeddings')) {
    let body = ''
    req.on('data', (c) => (body += c))
    req.on('end', () => {
      try {
        const j = JSON.parse(body)
        const inputs = Array.isArray(j.input) ? j.input : [String(j.input ?? '')]
        const data = inputs.map((t, i) => ({ object: 'embedding', index: i, embedding: embed(String(t)) }))
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ object: 'list', data, model: j.model ?? 'mock', usage: { prompt_tokens: 0, total_tokens: 0 } }))
      } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ error: { message: String(e) } }))
      }
    })
    return
  }
  if (req.method === 'POST' && req.url.endsWith('/chat/completions')) {
    // canned 伪问答：把上下文里出现的 [n] 与来源行拼进回答，供 rag_ask 链路测试
    let body = ''
    req.on('data', (c) => (body += c))
    req.on('end', () => {
      try {
        const j = JSON.parse(body)
        const user = [...(j.messages ?? [])].reverse().find((m) => m.role === 'user')?.content ?? ''
        const text = String(user)
        const ctx = text.split('资料：')[1]?.split('用户问题：')[0] ?? ''
        const refs = [...ctx.matchAll(/\[(\d+)\] （来源：([^）]+)）/g)].map((m) => `[${m[1]}]`)
        const q = text.split('用户问题：')[1]?.trim() ?? ''
        const answer = refs.length
          ? `（mock 回答）关于「${q.slice(0, 30)}」：根据知识库资料 ${refs.join('、')} 可以得到对应结论。`
          : '知识库中没有找到相关内容。'
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({
          id: 'mock-chat', object: 'chat.completion', model: j.model ?? 'mock',
          choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: answer } }],
          usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
        }))
      } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ error: { message: String(e) } }))
      }
    })
    return
  }
  res.writeHead(404).end()
}).listen(port, '127.0.0.1', () => console.log(`mock-embeddings: http://127.0.0.1:${port}/v1/embeddings dims=${dims}`))
