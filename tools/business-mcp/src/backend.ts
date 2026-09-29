/**
 * backend.ts — 平台受控只读接口的 HTTP 客户端（**冻结契约**）。
 *
 * 契约（由 guarantee-ai 的 T5-03 实现，本任务先按它对 stub 后端做协议测试）：
 *   GET  /api/ai/mcp/tools
 *        → 200 { "data": [ { "name": "...", "description": "...", "inputSchema": { ... } } ] }
 *   POST /api/ai/mcp/tools/{name}    body = 工具参数 JSON
 *        → 200 { "data": { "result": <任意>, "dataSource": "口径文本"|null, "truncated": false } }
 *   鉴权：Authorization: Bearer <MCP-Token>
 *
 * 网关**不做任何权限判断**（那是平台侧 `AiToolRegistry` + `DataScopeService` 的职责，
 * 见红线 §2.3-1）；网关只做三件事：带上凭据、转发、把错误翻译成人能读的文本。
 * 网关也**不读数据库、不提供任意路径透传**。
 */

import type { GatewayConfig } from './config.js';
import { GatewayError, codeForStatus, messageForStatus, toGatewayError } from './errors.js';
import type { Logger } from './log.js';

/** 平台清单条目（`GET /api/ai/mcp/tools`）。 */
export interface BackendTool {
  readonly name: string;
  readonly description: string;
  readonly inputSchema: Record<string, unknown>;
}

/** 工具调用结果（`POST /api/ai/mcp/tools/{name}`）。 */
export interface ToolCallPayload {
  readonly result: unknown;
  readonly dataSource: string | null;
  readonly truncated: boolean;
}

/** 宽松的对象 JSON Schema：后端没给 schema 时的兜底（不猜测参数名）。 */
export const PERMISSIVE_INPUT_SCHEMA: Record<string, unknown> = {
  type: 'object',
  properties: {},
  additionalProperties: true,
};

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 从平台错误响应里抽出可读 message（尽量，不保证有）。 */
function extractBackendMessage(rawText: string): string | undefined {
  const text = rawText.trim();
  if (text === '') return undefined;
  try {
    const parsed: unknown = JSON.parse(text);
    if (isPlainObject(parsed)) {
      const candidates = [parsed.message, parsed.error, parsed.msg];
      for (const candidate of candidates) {
        if (typeof candidate === 'string' && candidate.trim() !== '') return candidate.trim().slice(0, 300);
      }
      const data = parsed.data;
      if (isPlainObject(data) && typeof data.message === 'string' && data.message.trim() !== '') {
        return data.message.trim().slice(0, 300);
      }
    }
  } catch {
    /* 非 JSON 错误体：截断原文，别回显整页 HTML */
  }
  return text.length > 0 ? text.slice(0, 300) : undefined;
}

export class BackendClient {
  private readonly config: GatewayConfig;
  private readonly logger: Logger;

  constructor(config: GatewayConfig, logger: Logger) {
    this.config = config;
    this.logger = logger;
  }

  /** 平台基址（用于日志排查，不含凭据）。 */
  get baseUrl(): string {
    return this.config.baseUrl;
  }

  private async request(method: 'GET' | 'POST', path: string, body?: unknown): Promise<unknown> {
    const url = `${this.config.baseUrl}${path}`;
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.config.token}`,
      Accept: 'application/json',
    };
    if (body !== undefined) headers['Content-Type'] = 'application/json';

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.config.timeoutMs);

    let response: Response;
    try {
      response = await fetch(url, {
        method,
        headers,
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
        signal: controller.signal,
      });
    } catch (err) {
      if (controller.signal.aborted) {
        throw new GatewayError(
          'TIMEOUT',
          `请求平台超时（${this.config.timeoutMs}ms）：${method} ${path}`,
        );
      }
      throw toGatewayError(err);
    } finally {
      clearTimeout(timer);
    }

    const rawText = await response.text();

    if (!response.ok) {
      const backendMessage = extractBackendMessage(rawText);
      const code = codeForStatus(response.status);
      throw new GatewayError(code, messageForStatus(response.status, backendMessage), {
        httpStatus: response.status,
        backendMessage: code === 'SERVER_ERROR' ? undefined : backendMessage,
      });
    }

    let parsed: unknown;
    try {
      parsed = rawText.trim() === '' ? null : JSON.parse(rawText);
    } catch {
      throw new GatewayError(
        'INVALID_RESPONSE',
        `平台返回了非 JSON 响应体（${method} ${path}，${rawText.length} 字节）`,
        { httpStatus: response.status },
      );
    }

    if (!isPlainObject(parsed) || !('data' in parsed)) {
      throw new GatewayError(
        'INVALID_RESPONSE',
        `平台响应缺少 data 字段（${method} ${path}）；冻结契约要求 { data: ... }`,
        { httpStatus: response.status },
      );
    }
    return parsed.data;
  }

  /**
   * 拉取当前服务账号**可见**的只读工具清单。
   *
   * 注意：清单已经过平台侧权限裁剪（无 `ai:mcp:read` 或缺少某域权限时对应工具不出现）。
   * 网关不会再"补齐"缺失的工具 —— 补齐等于绕过裁剪。
   */
  async listTools(): Promise<BackendTool[]> {
    const data = await this.request('GET', '/api/ai/mcp/tools');
    if (!Array.isArray(data)) {
      throw new GatewayError('INVALID_RESPONSE', 'GET /api/ai/mcp/tools 的 data 不是数组');
    }

    const tools: BackendTool[] = [];
    for (const item of data) {
      if (!isPlainObject(item) || typeof item.name !== 'string' || item.name.trim() === '') {
        this.logger.warn('清单条目缺少合法 name，已丢弃', { item: JSON.stringify(item).slice(0, 200) });
        continue;
      }
      const name = item.name.trim();
      const description = typeof item.description === 'string' ? item.description : '';
      const inputSchema = isPlainObject(item.inputSchema) ? item.inputSchema : PERMISSIVE_INPUT_SCHEMA;
      tools.push({ name, description, inputSchema });
    }
    return tools;
  }

  /** 转发一次只读工具调用。 */
  async callTool(name: string, args: Record<string, unknown>): Promise<ToolCallPayload> {
    const data = await this.request('POST', `/api/ai/mcp/tools/${encodeURIComponent(name)}`, args);
    if (!isPlainObject(data)) {
      throw new GatewayError('INVALID_RESPONSE', `POST /api/ai/mcp/tools/${name} 的 data 不是对象`);
    }
    if (!('result' in data)) {
      throw new GatewayError(
        'INVALID_RESPONSE',
        `POST /api/ai/mcp/tools/${name} 的 data 缺少 result 字段；冻结契约要求 { result, dataSource, truncated }`,
      );
    }
    return {
      result: data.result,
      dataSource: typeof data.dataSource === 'string' && data.dataSource !== '' ? data.dataSource : null,
      truncated: data.truncated === true,
    };
  }
}
