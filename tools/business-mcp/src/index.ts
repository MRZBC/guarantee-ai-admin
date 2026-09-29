#!/usr/bin/env node
/**
 * index.ts — 业务 MCP 网关入口（stdio transport）。
 *
 * 数据流：
 *   MCP 客户端 ↔ stdio(JSON-RPC) ↔ 本网关 ↔ HTTPS/HTTP ↔ 平台受控只读接口
 *   （GET /api/ai/mcp/tools、POST /api/ai/mcp/tools/{name}）
 *
 * 两个铁律：
 *   1. stdout **只**写 JSON-RPC 协议帧；所有日志写 stderr（否则协议立刻被破坏）。
 *   2. 默认关闭、无 Token 不启动（fail-closed）。平台自身不受网关影响。
 *
 * 启动示例（Windows PowerShell）：
 *   $env:GUARANTEE_AI_MCP_ENABLED='true'
 *   $env:GUARANTEE_MCP_BASE_URL='http://127.0.0.1:8081'
 *   $env:GUARANTEE_MCP_TOKEN='<平台的 MCP Token>'
 *   node tools/business-mcp/dist/index.js
 */

import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { BackendClient } from './backend.js';
import { ConfigError, loadConfig, tokenFingerprint } from './config.js';
import { createLogger } from './log.js';
import { createGatewayServer } from './server.js';

const logger = createLogger();

async function main(): Promise<void> {
  let config;
  try {
    config = loadConfig();
  } catch (err) {
    if (err instanceof ConfigError) {
      // 配置问题必须"说清楚 + 退出"，不能带病运行
      logger.error(`拒绝启动：${err.message}`);
      process.exit(1);
    }
    throw err;
  }

  // REQ-MCP-05：网关自身也记录"谁在什么时候启动了它、用什么配置"。
  // 注意**不打印 Token**，只打印可核对的指纹与长度。
  logger.info('startup audit', {
    at: new Date().toISOString(),
    pid: process.pid,
    node: process.version,
    platformBaseUrl: config.baseUrl,
    toolPrefix: config.toolPrefix,
    toolSource: config.toolSource,
    timeoutMs: config.timeoutMs,
    tokenFingerprint: tokenFingerprint(config.token),
  });

  const client = new BackendClient(config, logger);
  const server = createGatewayServer({ config, client, logger });
  const transport = new StdioServerTransport();
  await server.connect(transport);
  logger.info(`server ready (${config.serverName} v${config.serverVersion}, readonly, tools-only)`);

  const shutdown = async (signal: string): Promise<void> => {
    logger.info(`received ${signal}, closing`);
    try {
      await server.close();
    } catch (err) {
      logger.error(`close failed: ${err instanceof Error ? err.message : String(err)}`);
    }
    process.exit(0);
  };

  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
}

main().catch((err: unknown) => {
  logger.error(`fatal: ${err instanceof Error ? err.stack ?? err.message : String(err)}`);
  process.exit(1);
});
