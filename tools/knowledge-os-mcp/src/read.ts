/**
 * read.ts — READ（读取 Vault 内的知识页面）。
 *
 * 与「任意文件读取」的区别：路径必须由调用方以 **Vault 内相对路径**给出，
 * 并经过 pathguard 的完整校验（绝对路径 / ·· / UNC / 符号链接逃逸全部拒绝）。
 * 不提供按绝对路径读取的入口 —— 这是有意的。
 */

import { readSnapshot } from './fsx.js';
import { classifyPath } from './search.js';
import type { VaultContext } from './vault.js';
import { parseMarkdown } from './markdown.js';

export interface ReadResult {
  readonly path: string;
  readonly type: string;
  readonly frontmatter: Record<string, unknown>;
  readonly content: string;
  readonly headings: readonly string[];
  readonly bytes: number;
  readonly lines: number;
  /** 该文件实际使用的换行风格（写成 'LF' / 'CRLF'，避免 JSON 里出现裸转义） */
  readonly newline: 'LF' | 'CRLF';
  readonly updated: string | null;
}

/** 读取一个 Vault 内文件。目标不存在时抛 not_found（由工具层转成结构化错误）。 */
export async function readVaultPage(ctx: VaultContext, relativePath: unknown): Promise<ReadResult> {
  const { relative, absolutePath } = await ctx.guard.statRelative(relativePath);
  const snapshot = await readSnapshot(absolutePath);
  const doc = parseMarkdown(snapshot.raw);

  const fmType = typeof doc.frontmatter['type'] === 'string' ? doc.frontmatter['type'] : null;

  return {
    path: relative,
    type: fmType ?? classifyPath(ctx, relative),
    frontmatter: doc.frontmatter,
    content: doc.body.replace(/^\n+/, ''),
    headings: doc.headings.map((h) => `${'#'.repeat(h.level)} ${h.text}`),
    bytes: Buffer.byteLength(snapshot.raw, 'utf8'),
    lines: snapshot.raw.split('\n').length,
    newline: snapshot.newline === '\r\n' ? 'CRLF' : 'LF',
    updated: typeof doc.frontmatter['updated'] === 'string' ? doc.frontmatter['updated'] : null,
  };
}
