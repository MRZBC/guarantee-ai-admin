/**
 * errors.ts — 把各种失败归一成**外部 Agent 能读懂**的错误。
 *
 * 原则（RK-MCP-01 / REQ-MCP-05）：
 *   - 401/403 要说清"Token 缺失/无效/已撤销"或"没有 ai:mcp:read"，而不是一个裸 500；
 *   - 429 要说清"限流/配额超限，稍后重试"；
 *   - 平台 5xx 与网络故障要区分（前者是平台问题，后者是链路/配置问题）；
 *   - **不回显 Token、不回显平台内部堆栈、不回显数据库细节**。
 */

export type GatewayErrorCode =
  | 'CONFIG_INVALID'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'BAD_REQUEST'
  | 'RATE_LIMITED'
  | 'SERVER_ERROR'
  | 'NETWORK'
  | 'TIMEOUT'
  | 'INVALID_RESPONSE'
  | 'TOOL_NOT_ALLOWED'
  | 'TOOL_UNKNOWN'
  | 'BAD_ARGUMENTS';

export class GatewayError extends Error {
  readonly code: GatewayErrorCode;
  readonly httpStatus?: number;
  /** 平台返回的原始 message（可能为空）。用于排查，不含凭据。 */
  readonly backendMessage?: string;

  constructor(code: GatewayErrorCode, message: string, options: { httpStatus?: number; backendMessage?: string } = {}) {
    super(message);
    this.name = 'GatewayError';
    this.code = code;
    this.httpStatus = options.httpStatus;
    this.backendMessage = options.backendMessage;
  }
}

const HINTS: Partial<Record<GatewayErrorCode, string>> = {
  UNAUTHORIZED: 'Token 缺失、无效或已被撤销；请核对 GUARANTEE_MCP_TOKEN，或重新签发（撤销即时生效）。',
  FORBIDDEN: '服务账号缺少 ai:mcp:read 权限；请让管理员为服务账号补权限码后重试。',
  NOT_FOUND: '平台没有该工具（或该工具在当前服务账号的注册集里被裁剪掉了）。',
  RATE_LIMITED: '超过每 Token 的 QPS 或每日配额；请降低频率或申请更高配额。',
  SERVER_ERROR: '平台侧异常；这是平台问题，不是调用参数问题，请把本次 traceId 提供给平台维护者。',
  NETWORK: '无法连接平台（地址、端口、网络策略或平台未启动）；请核对 GUARANTEE_MCP_BASE_URL。',
  TIMEOUT: '平台在超时时间内没有返回；可提高 GUARANTEE_MCP_TIMEOUT_MS 或稍后重试。',
  INVALID_RESPONSE: '平台返回体不符合冻结契约，网关拒绝猜测语义（避免把错误结果当成数据喂给模型）。',
  TOOL_NOT_ALLOWED: '业务 MCP 只暴露只读工具；写能力（propose*/create/update/delete…）不通过 MCP 提供。',
  TOOL_UNKNOWN: '未知工具名；请先 tools/list 获取当前可用清单。',
  BAD_ARGUMENTS: '工具参数必须是 JSON 对象。',
};

/** 一行可读文本：`❌ [CODE] 说明（HTTP nnn）提示`。 */
export function formatReadable(err: GatewayError): string {
  const status = err.httpStatus ? `（HTTP ${err.httpStatus}）` : '';
  const hint = HINTS[err.code] ? `\n提示：${HINTS[err.code]}` : '';
  const backend = err.backendMessage ? `\n平台信息：${err.backendMessage}` : '';
  return `❌ [${err.code}] ${err.message}${status}${backend}${hint}`;
}

const STATUS_MAP: Record<number, GatewayErrorCode> = {
  400: 'BAD_REQUEST',
  401: 'UNAUTHORIZED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  405: 'BAD_REQUEST',
  422: 'BAD_REQUEST',
  429: 'RATE_LIMITED',
};

export function codeForStatus(status: number): GatewayErrorCode {
  const mapped = STATUS_MAP[status];
  if (mapped) return mapped;
  if (status >= 500) return 'SERVER_ERROR';
  if (status >= 400) return 'BAD_REQUEST';
  return 'SERVER_ERROR';
}

const STATUS_TEXT: Record<number, string> = {
  400: '平台判定请求参数不合法',
  401: '平台拒绝：机器凭据未通过校验',
  403: '平台拒绝：服务账号权限不足',
  404: '平台未找到该工具',
  422: '平台判定请求参数不合法',
  429: '平台限流：调用过于频繁或超出配额',
};

export function messageForStatus(status: number, backendMessage?: string): string {
  if (status >= 500) {
    return `平台内部错误（HTTP ${status}）${backendMessage ? `：${backendMessage}` : ''}`;
  }
  const base = STATUS_TEXT[status] ?? `平台返回了非预期的状态码（HTTP ${status}）`;
  return `${base}${backendMessage ? `：${backendMessage}` : ''}`;
}

/** 把任意异常归一成 GatewayError。 */
export function toGatewayError(err: unknown): GatewayError {
  if (err instanceof GatewayError) return err;
  if (err instanceof Error) {
    if (err.name === 'AbortError' || err.name === 'TimeoutError') {
      return new GatewayError('TIMEOUT', '请求平台超时');
    }
    // Node 的 fetch 失败统一是 TypeError: fetch failed，原因在 cause 里
    const cause = (err as { cause?: unknown }).cause;
    const causeText =
      cause instanceof Error ? `（${cause.message}）` : cause ? `（${String(cause)}）` : '';
    return new GatewayError('NETWORK', `请求平台失败：${err.message}${causeText}`);
  }
  return new GatewayError('NETWORK', `请求平台失败：${String(err)}`);
}
