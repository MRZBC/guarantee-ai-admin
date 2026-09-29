/**
 * log.ts — 网关日志。
 *
 * **铁律**：stdout 只走 JSON-RPC 协议帧。任何一行非 JSON 的输出都会破坏协议，
 * 因此这里的每一个方法都只写 stderr（与 `tools/knowledge-os-mcp` 同构）。
 */

export interface Logger {
  info(message: string, extra?: Record<string, unknown>): void;
  warn(message: string, extra?: Record<string, unknown>): void;
  error(message: string, extra?: Record<string, unknown>): void;
}

const PREFIX = '[business-mcp]';

function write(level: string, message: string, extra?: Record<string, unknown>): void {
  const suffix = extra && Object.keys(extra).length > 0 ? ' ' + JSON.stringify(extra) : '';
  process.stderr.write(`${PREFIX} ${level} ${message}${suffix}\n`);
}

export function createLogger(): Logger {
  return {
    info: (message, extra) => write('INFO', message, extra),
    warn: (message, extra) => write('WARN', message, extra),
    error: (message, extra) => write('ERROR', message, extra),
  };
}

/** 静默 logger，供单测/嵌入场景使用（协议测试不关心日志）。 */
export const silentLogger: Logger = {
  info: () => undefined,
  warn: () => undefined,
  error: () => undefined,
};
