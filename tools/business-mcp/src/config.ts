/**
 * config.ts — 网关配置（全部来自环境变量，**默认关闭**）。
 *
 * 与平台侧配置键的对应关系（`application.yml` 的 `guarantee.ai.mcp.*`）：
 *   guarantee.ai.mcp.enabled      → GUARANTEE_AI_MCP_ENABLED（默认 false：网关拒绝启动）
 *   guarantee.ai.mcp.gateway-url  → GUARANTEE_MCP_GATEWAY_URL（平台视角的"网关回连地址"）
 *   网关自身还需要知道**平台地址**与**机器凭据**，这两个只存在于网关进程环境里：
 *     GUARANTEE_MCP_BASE_URL（平台基址，默认 http://127.0.0.1:8081）
 *     GUARANTEE_MCP_TOKEN（MCP Token，必填；缺失即拒绝启动 —— fail-closed）
 *
 * 配置错误一律抛 ConfigError（可读中文），由入口打印到 stderr 后以退出码 1 结束：
 * 网关宁可起不来，也不能以"没有凭据/没开开关"的姿态对外服务。
 */

export type ToolSource = 'auto' | 'backend' | 'static';

export interface GatewayConfig {
  readonly enabled: boolean;
  readonly baseUrl: string;
  readonly token: string;
  readonly timeoutMs: number;
  /** 暴露给 MCP 客户端的工具名前缀；空串表示不加前缀（即直接暴露后端工具名）。 */
  readonly toolPrefix: string;
  readonly toolSource: ToolSource;
  readonly toolCacheMs: number;
  readonly serverName: string;
  readonly serverVersion: string;
}

export const SERVER_VERSION = '0.1.0';

/** 默认前缀：沿用 DSH 的 `mcp__<server>__<tool>` 展示约定（REQ-MCP-01）。 */
export const DEFAULT_TOOL_PREFIX = 'mcp__guarantee__';

export class ConfigError extends Error {
  readonly code = 'CONFIG_INVALID';
  constructor(message: string) {
    super(message);
    this.name = 'ConfigError';
  }
}

function readBool(raw: string | undefined, fallback: boolean): boolean {
  if (raw === undefined || raw.trim() === '') return fallback;
  return ['true', '1', 'yes', 'on'].includes(raw.trim().toLowerCase());
}

function readPositiveInt(raw: string | undefined, fallback: number, name: string): number {
  if (raw === undefined || raw.trim() === '') return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value) || !Number.isInteger(value) || value <= 0) {
    throw new ConfigError(`${name} 必须是正整数，当前值：${JSON.stringify(raw)}`);
  }
  return value;
}

/**
 * 载入并校验配置。任何一项不合法都抛 ConfigError，而不是"带回退继续跑"。
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): GatewayConfig {
  const enabled = readBool(env.GUARANTEE_AI_MCP_ENABLED, false);
  if (!enabled) {
    throw new ConfigError(
      '业务 MCP 默认关闭（REQ-MCP-05）。确认要对外提供只读能力后，设置 GUARANTEE_AI_MCP_ENABLED=true 再启动；' +
        '平台侧无需改动，网关退出不影响平台自身（AC-MCP-06）。',
    );
  }

  const baseUrlRaw = env.GUARANTEE_MCP_BASE_URL ?? env.GUARANTEE_MCP_GATEWAY_URL ?? 'http://127.0.0.1:8081';
  const baseUrl = baseUrlRaw.trim().replace(/\/+$/, '');
  if (!/^https?:\/\/[^\s]+$/.test(baseUrl)) {
    throw new ConfigError(`GUARANTEE_MCP_BASE_URL 不是合法的 http(s) 地址：${JSON.stringify(baseUrlRaw)}`);
  }

  const token = (env.GUARANTEE_MCP_TOKEN ?? '').trim();
  if (token === '') {
    throw new ConfigError(
      '缺少机器凭据：GUARANTEE_MCP_TOKEN 为空。业务 MCP 只接受 MCP Token（不是用户名密码，也不走首登改密闸门）——' +
        '请先在平台页面签发 Token（见 docs/MCP-外部接入.md §Token 申请流程），或向管理员申请。',
    );
  }

  const toolSourceRaw = (env.GUARANTEE_MCP_TOOL_SOURCE ?? 'auto').trim().toLowerCase();
  if (toolSourceRaw !== 'auto' && toolSourceRaw !== 'backend' && toolSourceRaw !== 'static') {
    throw new ConfigError(
      `GUARANTEE_MCP_TOOL_SOURCE 只能是 auto | backend | static，当前值：${JSON.stringify(toolSourceRaw)}`,
    );
  }

  const toolPrefix = env.GUARANTEE_MCP_TOOL_PREFIX ?? DEFAULT_TOOL_PREFIX;

  return {
    enabled,
    baseUrl,
    token,
    timeoutMs: readPositiveInt(env.GUARANTEE_MCP_TIMEOUT_MS, 15_000, 'GUARANTEE_MCP_TIMEOUT_MS'),
    toolPrefix,
    toolSource: toolSourceRaw,
    toolCacheMs: readPositiveInt(env.GUARANTEE_MCP_TOOL_CACHE_MS, 30_000, 'GUARANTEE_MCP_TOOL_CACHE_MS'),
    serverName: 'guarantee-business',
    serverVersion: SERVER_VERSION,
  };
}

/**
 * 启动审计用的"配置指纹"：**绝不打印 Token 本身**，只给长度与一段哈希前缀，
 * 便于运维核对"这次启动用的是哪把凭据"，同时避免把凭据写进日志文件（REQ-MCP-05 审计）。
 */
export function tokenFingerprint(token: string): string {
  // 与具体的加密库解耦：只做一次简单不可逆摘要，足以区分不同 Token。
  let hash = 0;
  for (let i = 0; i < token.length; i += 1) {
    hash = (hash * 31 + token.charCodeAt(i)) >>> 0;
  }
  return `fp${hash.toString(16).padStart(8, '0')}(len=${token.length})`;
}
