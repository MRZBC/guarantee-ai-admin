#!/usr/bin/env node
/**
 * call-tool.mjs — 通过 MCP stdio 调用 knowledge-os 的**单个**工具。
 *
 * 为什么需要它：`apply-knowledge.mjs` 只负责把 `knowledge/` 源文件批量应用到 Vault，
 * 而 `knowledge_append_log` 这类「本次工作做完才写一次」的动作没有源文件可放
 * （LOG 是追加式历史，逐条不同）。没有这个入口时，Agent 只能绕过 MCP 直接改
 * `LOG.md`，那会丢掉身份校验与路径守卫（Skill §11 说明的降级代价）。
 *
 * 用法：
 *   node tools/knowledge-os-mcp/scripts/call-tool.mjs <toolName> '<jsonArgs>'
 *   node tools/knowledge-os-mcp/scripts/call-tool.mjs <toolName> --file args.json
 *   node tools/knowledge-os-mcp/scripts/call-tool.mjs knowledge_health '{}'
 *
 * 退出码：0 成功；1 工具返回错误或参数有误。
 */

import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const serverEntry = resolve(here, '..', 'dist', 'index.js');

const [toolName, rawArgs] = process.argv.slice(2);

if (!toolName) {
  console.error('用法：node tools/knowledge-os-mcp/scripts/call-tool.mjs <toolName> \'<jsonArgs>\'');
  process.exit(1);
}

let args = {};
if (rawArgs === '--file') {
  const file = process.argv[4];
  if (!file) {
    console.error('--file 需要一个参数文件路径');
    process.exit(1);
  }
  args = JSON.parse(readFileSync(file, 'utf8'));
} else if (rawArgs !== undefined) {
  try {
    args = JSON.parse(rawArgs);
  } catch (err) {
    console.error(`参数不是合法 JSON：${err.message}`);
    process.exit(1);
  }
}

/** 极简 JSON-RPC / stdio 客户端（与 apply-knowledge.mjs 同形，刻意不共享代码以免互相牵连）。 */
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
      // KNOWLEDGE_PROJECT_ROOT 让 Server 从本仓库解析 .agent/project.yaml，而不是从 cwd 猜
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
        let msg;
        try {
          msg = JSON.parse(line);
        } catch {
          continue;
        }
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
      const t = setTimeout(
        () => rej(new Error(`超时：${method}\n${this.stderr.join('').slice(-600)}`)),
        60_000,
      );
      this.#pending.set(id, {
        resolve: (v) => {
          clearTimeout(t);
          res(v);
        },
      });
      this.#child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }

  async start() {
    const r = await this.#request('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'call-tool', version: '1.0.0' },
    });
    if (r.error) throw new Error(`initialize 失败：${JSON.stringify(r.error)}`);
    this.#child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
    return this;
  }

  async call(name, callArgs) {
    const r = await this.#request('tools/call', { name, arguments: callArgs });
    if (r.error) throw new Error(`${name}：${JSON.stringify(r.error)}`);
    if (r.result?.isError) {
      throw new Error(`${name} 返回错误：${r.result.content?.map((c) => c.text).join('\n')}`);
    }
    return r.result.structuredContent;
  }

  close() {
    try {
      this.#child.stdin.end();
    } catch {}
    setTimeout(() => this.#child.kill(), 1500);
  }
}

const client = await new Client().start();
try {
  const result = await client.call(toolName, args);
  console.log(JSON.stringify(result, null, 2));
} catch (err) {
  console.error(String(err.message));
  process.exitCode = 1;
} finally {
  client.close();
}
