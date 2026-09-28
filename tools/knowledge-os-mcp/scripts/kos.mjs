#!/usr/bin/env node
/**
 * kos.mjs — 对**真实项目**运行 Knowledge OS MCP 工具的命令行入口。
 *
 * 用途：
 *   - 人工验证（不依赖任何 Agent Runtime）
 *   - 把要执行的工具调用序列写进一个 JSON 文件，一次跑完
 *
 * 用法：
 *   node tools/knowledge-os-mcp/scripts/kos.mjs health
 *   node tools/knowledge-os-mcp/scripts/kos.mjs resolve
 *   node tools/knowledge-os-mcp/scripts/kos.mjs state
 *   node tools/knowledge-os-mcp/scripts/kos.mjs call knowledge_search '{"query":"MCP"}'
 *   node tools/knowledge-os-mcp/scripts/kos.mjs script path/to/calls.json
 *
 * calls.json 格式：
 *   [
 *     { "name": "knowledge_health", "arguments": {} },
 *     { "name": "knowledge_bootstrap", "arguments": { "dryRun": false } }
 *   ]
 *
 * 输出：每个调用的结构化结果（stdout，JSON），出错时非零退出。
 */

import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const serverEntry = resolve(here, '..', 'dist', 'index.js');

const SHORTCUTS = {
  health: { name: 'knowledge_health', arguments: {} },
  resolve: { name: 'knowledge_resolve', arguments: {} },
  state: { name: 'knowledge_state', arguments: {} },
};

function parseArgs(argv) {
  const [cmd, ...rest] = argv;

  if (!cmd || cmd === '-h' || cmd === '--help') {
    console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1, 26).join('\n'));
    process.exit(0);
  }

  if (cmd in SHORTCUTS) return [SHORTCUTS[cmd]];

  if (cmd === 'call') {
    const [name, json] = rest;
    if (!name) throw new Error('call 需要工具名');
    return [{ name, arguments: json ? JSON.parse(json) : {} }];
  }

  if (cmd === 'script') {
    const [file] = rest;
    if (!file) throw new Error('script 需要文件路径');
    const parsed = JSON.parse(readFileSync(resolve(process.cwd(), file), 'utf8'));
    return Array.isArray(parsed) ? parsed : [parsed];
  }

  throw new Error(`未知命令：${cmd}`);
}

class Client {
  #child;
  #buffer = '';
  #pending = new Map();
  #nextId = 1;
  stderr = [];

  constructor() {
    this.#child = spawn(process.execPath, [serverEntry], {
      cwd: repoRoot,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, KNOWLEDGE_PROJECT_ROOT: repoRoot },
    });
    this.#child.stderr.setEncoding('utf8');
    this.#child.stderr.on('data', (c) => this.stderr.push(c));
    this.#child.stdout.setEncoding('utf8');
    this.#child.stdout.on('data', (chunk) => {
      this.#buffer += chunk;
      let i;
      while ((i = this.#buffer.indexOf('\n')) !== -1) {
        const line = this.#buffer.slice(0, i).trim();
        this.#buffer = this.#buffer.slice(i + 1);
        if (!line) continue;
        const msg = JSON.parse(line);
        const entry = this.#pending.get(msg.id);
        if (entry) {
          this.#pending.delete(msg.id);
          entry.resolve(msg);
        }
      }
    });
  }

  #request(method, params) {
    const id = this.#nextId++;
    return new Promise((res, rej) => {
      const t = setTimeout(() => {
        this.#pending.delete(id);
        rej(new Error(`超时：${method}\nstderr: ${this.stderr.join('').slice(-800)}`));
      }, 30_000);
      this.#pending.set(id, {
        resolve: (v) => {
          clearTimeout(t);
          res(v);
        },
      });
      this.#child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }

  async initialize() {
    const r = await this.#request('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'kos-cli', version: '1.0.0' },
    });
    if (r.error) throw new Error(`initialize 失败：${JSON.stringify(r.error)}`);
    this.#child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
    return r.result;
  }

  async callTool(name, args) {
    const r = await this.#request('tools/call', { name, arguments: args ?? {} });
    if (r.error) throw new Error(`${name} JSON-RPC 错误：${JSON.stringify(r.error)}`);
    return r.result;
  }

  close() {
    try {
      this.#child.stdin.end();
    } catch {}
    setTimeout(() => this.#child.kill(), 2000);
  }
}

const calls = parseArgs(process.argv.slice(2));
const client = new Client();

try {
  await client.initialize();

  let failed = false;
  for (const call of calls) {
    const result = await client.callTool(call.name, call.arguments);
    const payload = result.structuredContent ?? { raw: result.content };
    console.log(`\n=== ${call.name} ===`);
    if (result.isError) failed = true;
    console.log(JSON.stringify(payload, null, 2));
  }
  process.exitCode = failed ? 1 : 0;
} catch (err) {
  console.error(String(err?.stack ?? err));
  process.exitCode = 1;
} finally {
  client.close();
}
