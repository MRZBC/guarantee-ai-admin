/**
 * A minimal MCP stdio client used by the integration / continuity tests.
 *
 * 为什么自己写而不引 MCP SDK 的 Client：
 *   本测试的目的恰恰是「从外部按标准协议与 Server 对话」。
 *   自己实现（约 80 行）能保证测试覆盖真实的 stdin/stdout 帧格式，
 *   而不会因为 SDK 内部约定掩盖协议层的问题。
 *
 * 只实现测试需要的能力：initialize / tools/list / tools/call。
 */

import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';

export interface JsonRpcResponse {
  jsonrpc: '2.0';
  id: number | string;
  result?: unknown;
  error?: { code: number; message: string; data?: unknown };
}

export interface McpToolResult {
  content: Array<{ type: string; text: string }>;
  structuredContent?: Record<string, unknown>;
  isError?: boolean;
}

export interface McpTestClient {
  readonly stderr: string[];
  initialize(): Promise<unknown>;
  listTools(): Promise<Array<{ name: string; description?: string; inputSchema?: unknown }>>;
  callTool(name: string, args?: Record<string, unknown>): Promise<McpToolResult>;
  /** 调用工具并断言不是 isError，返回 structuredContent */
  callToolOk(name: string, args?: Record<string, unknown>): Promise<Record<string, unknown>>;
  close(): Promise<void>;
}

export interface SpawnOptions {
  /** dist/index.js 的绝对路径 */
  serverEntry: string;
  /** 被测项目根（会作为 KNOWLEDGE_PROJECT_ROOT 传给 Server） */
  projectRoot: string;
  env?: Record<string, string>;
  cwd?: string;
}

/** 启动 Server 并返回一个可用的协议客户端。 */
export async function startMcpClient(options: SpawnOptions): Promise<McpTestClient> {
  const child: ChildProcessWithoutNullStreams = spawn(
    process.execPath,
    [options.serverEntry],
    {
      cwd: options.cwd ?? options.projectRoot,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: {
        ...process.env,
        KNOWLEDGE_PROJECT_ROOT: options.projectRoot,
        // 隔离宿主机的兜底环境变量，保证测试结论只来自 .agent/vault.local.yaml
        KNOWLEDGE_VAULT_PATH: '',
        KNOWLEDGE_VAULT_ID: '',
        ...options.env,
      },
    },
  );

  const stderr: string[] = [];
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
      let msg: JsonRpcResponse;
      try {
        msg = JSON.parse(line) as JsonRpcResponse;
      } catch {
        // 不是 JSON 帧 → 说明有东西污染了 stdout，这是严重错误
        throw new Error(`stdout 出现非 JSON 内容（协议被污染）：${line.slice(0, 200)}`);
      }
      if (msg.id === undefined || msg.id === null) continue; // server → client 通知，测试忽略
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

  async function initialize(): Promise<unknown> {
    const res = await request('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'knowledge-os-integration-test', version: '1.0.0' },
    });
    const result = unwrap(res, 'initialize');
    // 标准要求：initialize 之后发送 initialized 通知
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
    return result;
  }

  return {
    stderr,
    initialize,
    async listTools() {
      const res = await request('tools/list', {});
      const result = unwrap(res, 'tools/list') as { tools?: Array<{ name: string }> };
      return (result.tools ?? []) as Array<{ name: string; description?: string; inputSchema?: unknown }>;
    },
    async callTool(name, args) {
      const res = await request('tools/call', { name, arguments: args ?? {} });
      const result = unwrap(res, `tools/call ${name}`) as McpToolResult;
      if (!result || !Array.isArray(result.content)) {
        throw new Error(`tools/call ${name} 返回了非预期结构：${JSON.stringify(result).slice(0, 300)}`);
      }
      return result;
    },
    async callToolOk(name, args) {
      const result = await this.callTool(name, args);
      if (result.isError) {
        throw new Error(`工具 ${name} 返回错误：${result.content.map((c) => c.text).join('\n')}`);
      }
      if (!result.structuredContent) {
        throw new Error(`工具 ${name} 没有返回 structuredContent`);
      }
      return result.structuredContent;
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
