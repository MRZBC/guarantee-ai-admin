#!/usr/bin/env node
/**
 * index.ts — knowledge-os MCP Server 入口（stdio transport）。
 *
 * 这是一个**完全独立的标准 MCP Server**：
 *   - 只实现 Model Context Protocol
 *   - 不依赖 Claude SDK / Codex SDK / OpenCode SDK / DeepSeek Harness SDK
 *   - 不依赖任何 Agent Runtime 的运行方式
 * 任何支持 MCP 的 Runtime 都能通过 stdio 启动它：
 *   node tools/knowledge-os-mcp/dist/index.js
 *
 * 重要：stdout **只**用于 JSON-RPC 协议帧。
 * 所有日志一律写 stderr，否则会破坏协议。
 */

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { registerTools, SERVER_VERSION } from './tools/index.js';

/** 只在 stderr 输出，绝不污染 stdout 的协议流。 */
function log(message: string, extra?: Record<string, unknown>): void {
  const suffix = extra ? ' ' + JSON.stringify(extra) : '';
  process.stderr.write(`[knowledge-os] ${message}${suffix}\n`);
}

async function main(): Promise<void> {
  const server = new McpServer(
    {
      name: 'knowledge-os',
      version: SERVER_VERSION,
      title: 'Portable Knowledge OS',
    },
    {
      capabilities: { tools: {} },
      instructions:
        'Portable Knowledge OS：把项目上下文从聊天历史中抽离出来，持久化到与代码仓库一一对应的 Obsidian Vault。' +
        '标准流程：knowledge_health → knowledge_resolve → knowledge_state →（必要时）knowledge_search / knowledge_read ' +
        '→ 核对代码真实状态 → 执行 Next Action → 验证 → knowledge_update_state → knowledge_append_log ' +
        '→（必要时）knowledge_create_decision / knowledge_upsert_wiki。' +
        'Vault 路径只来自 .agent/vault.local.yaml，本 Server 不会扫描磁盘寻找 Vault。',
    },
  );

  registerTools(server);

  const transport = new StdioServerTransport();
  await server.connect(transport);
  log(`server ready (v${SERVER_VERSION}, node ${process.version}, pid ${process.pid})`);

  const shutdown = async (signal: string): Promise<void> => {
    log(`received ${signal}, closing`);
    try {
      await server.close();
    } catch (err) {
      log(`close failed: ${(err as Error).message}`);
    }
    process.exit(0);
  };

  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
}

main().catch((err: unknown) => {
  log(`fatal: ${err instanceof Error ? err.stack ?? err.message : String(err)}`);
  process.exit(1);
});
