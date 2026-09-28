/**
 * stdio.test.ts — 真实 stdio JSON-RPC 协议冒烟测试。
 *
 * 目的：证明这不是「一个能被测试直接 import 的库」，而是**真的 MCP Server**：
 * 通过子进程 + stdin/stdout 完成 initialize 握手、tools/list、tools/call。
 */

import { afterEach, describe, expect, it } from 'vitest';
import { startMcpClient, type McpTestClient } from './helpers/mcpClient.js';
import { createFixture, type Fixture } from './helpers/fixture.js';

let fixture: Fixture | null = null;
let client: McpTestClient | null = null;

afterEach(async () => {
  if (client) await client.close();
  if (fixture) fixture.cleanup();
  client = null;
  fixture = null;
});

describe('MCP stdio 协议', () => {
  it('完成 initialize 握手并声明 server 身份', async () => {
    fixture = createFixture();
    client = await startMcpClient({ serverEntry: fixture.serverEntry, projectRoot: fixture.projectDir });

    const result = (await client.initialize()) as {
      protocolVersion: string;
      serverInfo: { name: string; version: string };
      capabilities: { tools?: unknown };
    };

    expect(result.serverInfo.name).toBe('knowledge-os');
    expect(result.serverInfo.version).toBe('1.0.0');
    expect(result.capabilities.tools).toBeDefined();
    expect(typeof result.protocolVersion).toBe('string');
  });

  it('tools/list 返回 10 个工具，且不含任何 delete / move / arbitrary_write / execute', async () => {
    fixture = createFixture();
    client = await startMcpClient({ serverEntry: fixture.serverEntry, projectRoot: fixture.projectDir });
    await client.initialize();

    const tools = await client.listTools();
    const names = tools.map((t) => t.name).sort();

    expect(names).toEqual(
      [
        'knowledge_append_log',
        'knowledge_bootstrap',
        'knowledge_create_decision',
        'knowledge_health',
        'knowledge_read',
        'knowledge_resolve',
        'knowledge_search',
        'knowledge_state',
        'knowledge_update_state',
        'knowledge_upsert_wiki',
      ].sort(),
    );

    const forbidden = /delete|remove|move|rename|write_file|execute|exec|shell|command|raw_write/i;
    for (const tool of tools) {
      expect(tool.name).not.toMatch(forbidden);
    }

    // 每个工具都要有描述 —— Agent 靠它决定何时调用
    for (const tool of tools) {
      expect(tool.description, `${tool.name} 缺少 description`).toBeTruthy();
    }
  });

  it('stdout 只有 JSON-RPC 帧，日志全部走 stderr', async () => {
    fixture = createFixture();
    client = await startMcpClient({ serverEntry: fixture.serverEntry, projectRoot: fixture.projectDir });
    await client.initialize();
    await client.listTools();

    // 客户端在解析到非 JSON 行时会抛错，因此能走到这里就说明 stdout 是干净的
    const stderrText = client.stderr.join('');
    expect(stderrText).toContain('[knowledge-os]');
    expect(stderrText).toContain('server ready');
  });

  it('未知工具返回错误结果而不是崩溃', async () => {
    fixture = createFixture();
    client = await startMcpClient({ serverEntry: fixture.serverEntry, projectRoot: fixture.projectDir });
    await client.initialize();

    const result = await client.callTool('knowledge_delete_everything', {});
    expect(result.isError).toBe(true);
    expect(result.content.map((c) => c.text).join('\n')).toMatch(/not found/i);

    // Server 仍然活着：删除类工具根本不存在，所以永远无法被调用
    const tools = await client.listTools();
    expect(tools.length).toBe(10);
    expect(tools.map((t) => t.name)).not.toContain('knowledge_delete_everything');
  });
});
