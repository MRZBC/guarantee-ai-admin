/**
 * errors.ts — 结构化错误。
 *
 * 设计要点：
 *  - 每个失败都有一个稳定的机器可读 code，Agent 可以据此判断下一步，
 *    而不是去解析人类可读的 message。
 *  - MCP 层把任意抛出转换为「结构化错误 JSON + isError: true」，
 *    因此错误永远不会被静默吞掉，也不会以半成功状态写入 Vault。
 */

/** MCP Server 内所有可预期失败的分类。 */
export type KnowledgeErrorCode =
  | 'project_config_missing'
  | 'project_config_invalid'
  | 'vault_config_missing'
  | 'vault_config_invalid'
  | 'vault_not_found'
  | 'vault_not_readable'
  | 'vault_id_missing'
  | 'vault_id_invalid'
  | 'identity_mismatch'
  | 'invalid_path'
  | 'path_escape'
  | 'symlink_escape'
  | 'not_found'
  | 'not_a_file'
  | 'conflict'
  | 'invalid_argument'
  | 'write_failed'
  | 'internal';

export class KnowledgeError extends Error {
  readonly code: KnowledgeErrorCode;
  readonly details: Record<string, unknown>;

  constructor(code: KnowledgeErrorCode, message: string, details: Record<string, unknown> = {}) {
    super(message);
    this.name = 'KnowledgeError';
    this.code = code;
    this.details = details;
  }

  toJSON(): { ok: false; error: { code: KnowledgeErrorCode; message: string; details: Record<string, unknown> } } {
    return {
      ok: false,
      error: { code: this.code, message: this.message, details: this.details },
    };
  }
}

/** 便捷构造器：少写一点样板。 */
export const fail = (
  code: KnowledgeErrorCode,
  message: string,
  details: Record<string, unknown> = {},
): never => {
  throw new KnowledgeError(code, message, details);
};

/** 把任意 unknown 归一化为 KnowledgeError。 */
export function toKnowledgeError(err: unknown): KnowledgeError {
  if (err instanceof KnowledgeError) return err;

  const e = err as NodeJS.ErrnoException | undefined;
  const sysCode = e?.code;

  // 把常见 errno 映射到本系统的错误码，便于调用方统一处理。
  const mapped: Partial<Record<string, KnowledgeErrorCode>> = {
    ENOENT: 'not_found',
    EACCES: 'vault_not_readable',
    EPERM: 'vault_not_readable',
    EISDIR: 'not_a_file',
    ENOTDIR: 'invalid_path',
    ELOOP: 'symlink_escape',
  };

  const code = (sysCode && mapped[sysCode]) || 'internal';
  const message = err instanceof Error ? err.message : String(err);
  return new KnowledgeError(code, message, sysCode ? { sysCode } : {});
}
