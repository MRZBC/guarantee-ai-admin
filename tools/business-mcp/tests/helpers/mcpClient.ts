/**
 * mcpClient.ts — 一个**极简 MCP stdio 客户端**，只用于测试。
 *
 * 为什么自己写而不引 MCP SDK 的 Client：
 *   测试的目的恰恰是"从外部按标准协议与网关对话"。自己实现能保证覆盖真实的 stdin/stdout 帧格式，
 *   而不会被 SDK 内部约定掩盖协议层问题（沿用 `tools/knowledge-os-mcp/tests/helpers/mcpClient.ts` 的做法）。
 *
 * 只实现测试需要的能力：initialize / tools/list / tools/call，以及"进程启动失败"的观察能力。
 */

import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';

export interface JsonRpcResponse {
  jsonrpc: '2.0';
  id: number | string;
  result?: unknown;
  error?: { code: number; message: string; data?: unknown };
}

export interface McpToolDescriptor {
  name: string;
  description?: string;
  inputSchema?: unknown;
  annotations?: Record<string, unknown>;
}

export interface McpToolResult {
  content: Array<{ type: string; text: string }>;
  structuredContent?: Record<string, unknown>;
  isError?: boolean;
}

export interface McpTestClient {
  readonly stderr: string[];
  readonly stdoutLines: string[];
  readonly child: ChildProcessWithoutNullStreams;
  initialize(): Promise<unknown>;
  listTools(): Promise<McpToolDescriptor[]>;
  listToolsRaw(): Promise<JsonRpcResponse>;
  callTool(name: string, args?: Record<string, unknown>): Promise<McpToolResult>;
  /** 等待进程退出（用于"应当拒绝启动"的用例）。 */
  waitForExit(timeoutMs?: number): Promise<{ code: number | null; signal: string | null }>;
  close(): Promise<void>;
}

export interface SpawnOptions {
  /** dist/index.js 的绝对路径 */
  serverEntry: string;
  env?: Record<string, string>;
  cwd?: string;
}

/** 清掉宿主机上可能存在的 GUARANTEE_* 变量，保证测试结论只来自用例显式给的配置。 */
export function isolatedEnv(overrides: Record<string, string> = {}): Record<string, string> {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (value === undefined) continue;
    if (key.startsWith('GUARANTEE_')) continue;
    env[key] = value;
  }
  return { ...env, ...overrides };
}

export async function startMcpClient(options: SpawnOptions): Promise<McpTestClient> {
  const child = spawn(process.execPath, [options.serverEntry], {
    cwd: options.cwd ?? process.cwd(),
    stdio: ['pipe', 'pipe', 'pipe'],
    env: options.env ?? isolatedEnv(),
  });

  const stderr: string[] = [];
  const stdoutLines: string[] = [];
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk: string) => {
    stderr.push(chunk);
  });

  let buffer = '';
  const pending = new Map<number, { resolve: (v: JsonRpcResponse) => void; reject: (e: Error) => void }>();
  let nextId = 1;
  let closed = false;

  child.stdout.setEncoding('utf8');
  child.stdout.on('data', (chunk: string) => {
    buffer += chunk;
    let idx: number;
    while ((idx = buffer.indexOf('\n')) !== -1) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (line === '') continue;
      stdoutLines.push(line);
      let msg: JsonRpcResponse;
      try {
        msg = JSON.parse(line) as JsonRpcResponse;
      } catch {
        // 任何非 JSON 行都意味着协议被污染（日志误写 stdout 等）
        throw new Error(`stdout 出现非 JSON 内容（协议被污染）：${line.slice(0, 200)}`);
      }
      if (msg.id === undefined || msg.id === null) continue; // server → client 通知，忽略
      const entry = pending.get(Number(msg.id));
      if (entry) {
        pending.delete(Number(msg.id));
        entry.resolve(msg);
      }
    }
  });

  const exited = new Promise<{ code: number | null; signal: string | null }>((resolve) => {
    child.on('exit', (code, signal) => resolve({ code, signal }));
  });
  child.on('error', (err) => {
    for (const [, entry] of pending) entry.reject(err);
    pending.clear();
  });

  function request(method: string, params?: unknown, timeoutMs = 20_000): Promise<JsonRpcResponse> {
    if (closed) return Promise.reject(new Error('client 已关闭'));
    const id = nextId++;
    const payload = JSON.stringify({ jsonrpc: '2.0', id, method, params: params ?? {} });
    return new Promise<JsonRpcResponse>((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`请求超时：${method}（stderr 末尾：${stderr.join('').slice(-400)}）`));
      }, timeoutMs);
      pending.set(id, {
        resolve: (v) => {
          clearTimeout(timer);
          resolve(v);
        },
        reject: (e) => {
          clearTimeout(timer);
          reject(e);
        },
      });
      child.stdin.write(payload + '\n');
    });
  }

  function unwrap(res: JsonRpcResponse, method: string): unknown {
    if (res.error) {
      throw new Error(`${method} 返回 JSON-RPC 错误：${res.error.code} ${res.error.message}`);
    }
    return res.result;
  }

  async function listToolsRaw(): Promise<JsonRpcResponse> {
    return request('tools/list', {});
  }

  return {
    stderr,
    stdoutLines,
    child,
    async initialize() {
      const res = await request('initialize', {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'business-mcp-protocol-test', version: '0.1.0' },
      });
      const result = unwrap(res, 'initialize');
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
      return result;
    },
    listToolsRaw,
    async listTools() {
      const result = unwrap(await listToolsRaw(), 'tools/list') as { tools?: McpToolDescriptor[] };
      return result.tools ?? [];
    },
    async callTool(name, args) {
      const res = await request('tools/call', { name, arguments: args ?? {} });
      const result = unwrap(res, `tools/call ${name}`) as McpToolResult;
      if (!result || !Array.isArray(result.content)) {
        throw new Error(`tools/call ${name} 返回了非预期结构：${JSON.stringify(result).slice(0, 300)}`);
      }
      return result;
    },
    waitForExit(timeoutMs = 15_000) {
      return Promise.race([
        exited,
        new Promise<never>((_, reject) =>
          setTimeout(() => reject(new Error(`进程在 ${timeoutMs}ms 内没有退出；stderr=${stderr.join('')}`)), timeoutMs),
        ),
      ]);
    },
    async close() {
      if (closed) return;
      closed = true;
      try {
        child.stdin.end();
      } catch {
        /* ignore */
      }
      const timer = setTimeout(() => child.kill(), 3000);
      await exited;
      clearTimeout(timer);
    },
  };
}
