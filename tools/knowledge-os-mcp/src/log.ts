/**
 * log.ts — LOG（追加式历史）。
 *
 * 硬约束：**只追加，永不重写**。
 *   - 不解析、不重排、不改写既有条目
 *   - 新条目永远加在文件末尾（与既有 Vault 的惯例一致：最新在最后）
 *   - 因此不需要乐观锁：追加不会破坏别人的修改
 */

import { KnowledgeError } from './errors.js';
import { appendText, tryReadSnapshot } from './fsx.js';
import { requireWritable, type VaultContext } from './vault.js';
import { formatDate } from './state.js';

export const LOG_TYPES = ['WORK', 'RESEARCH', 'DECISION', 'MAINTENANCE', 'MIGRATION', 'HANDOFF'] as const;
export type LogType = (typeof LOG_TYPES)[number];

export interface LogEntryInput {
  readonly type: LogType;
  /** 项目标识；缺省用当前 project.id */
  readonly project?: string;
  readonly summary: string;
  readonly completed?: readonly string[];
  readonly verification?: readonly string[];
  readonly decisions?: readonly string[];
  /** 状态变化，Before / After 成对出现 */
  readonly stateChange?: { readonly before?: string; readonly after?: string };
  readonly nextAction?: string;
  readonly blockers?: readonly string[];
  /** 覆盖日期（测试可注入） */
  readonly now?: Date;
}

export interface LogAppendReport {
  readonly path: string;
  readonly created: boolean;
  readonly bytesAppended: number;
  readonly entryDate: string;
  readonly preview: string;
}

/** 渲染一个 LOG 条目（与既有 LOG.md 的模板保持一致）。 */
export function renderLogEntry(input: LogEntryInput, projectId: string): { text: string; date: string } {
  const now = input.now ?? new Date();
  const date = formatDate(now);
  const project = (input.project ?? projectId).trim() || projectId;

  const lines: string[] = [];
  lines.push('---');
  lines.push('');
  lines.push(`## [${date}] ${input.type} | ${project}`);
  lines.push('');

  const bullet = (title: string, items?: readonly string[]) => {
    if (!items || items.length === 0) return;
    lines.push(`### ${title}`);
    lines.push('');
    for (const item of items) {
      const t = String(item).trim();
      if (t !== '') lines.push(`- ${t}`);
    }
    lines.push('');
  };

  if (input.summary.trim() !== '') {
    lines.push('### Summary 摘要');
    lines.push('');
    lines.push(input.summary.trim());
    lines.push('');
  }

  bullet('Completed 已完成', input.completed);
  bullet('Verification 验证', input.verification);
  bullet('Decisions 决策', input.decisions);

  if (input.stateChange && (input.stateChange.before || input.stateChange.after)) {
    lines.push('### State Change 状态变化');
    lines.push('');
    lines.push(`- Before 之前：${input.stateChange.before ?? '（未记录）'}`);
    lines.push(`- After 之后：${input.stateChange.after ?? '（未记录）'}`);
    lines.push('');
  }

  bullet('Blockers 阻塞项', input.blockers);

  if (input.nextAction && input.nextAction.trim() !== '') {
    lines.push('### Next Action 下一步');
    lines.push('');
    lines.push(`- ${input.nextAction.trim()}`);
    lines.push('');
  }

  return { text: lines.join('\n'), date };
}

/**
 * 追加一条 LOG 条目。
 * 写入前在文件末尾补一个换行，保证与既有内容之间有分隔。
 */
export async function appendLog(ctx: VaultContext, input: LogEntryInput): Promise<LogAppendReport> {
  requireWritable(ctx);

  // 领域层守卫：防止写出「什么都没记录」的空条目。
  // 注意必须 trim —— 空白字符串在 JS 里是真值，不 trim 就会漏过。
  const hasSummary = (input.summary ?? '').trim() !== '';
  const hasCompleted = (input.completed ?? []).some((c) => String(c).trim() !== '');
  const hasNextAction = (input.nextAction ?? '').trim() !== '';
  if (!hasSummary && !hasCompleted && !hasNextAction) {
    throw new KnowledgeError(
      'invalid_argument',
      'LOG 条目至少需要 summary / completed / nextAction 之一（不能记录一个空条目）',
      { type: input.type },
    );
  }

  const { absolutePath, relative } = ctx.guard.resolve(ctx.layout.log);
  const existing = await tryReadSnapshot(absolutePath);
  const newline = existing?.newline ?? '\n';

  const projectId = ctx.identity.projectId ?? 'unknown';
  const { text, date } = renderLogEntry(input, projectId);

  // 保证与前文分隔：文件不以换行结尾时先补一个。
  let payload = '';
  const raw = existing?.raw ?? '';
  if (raw !== '' && !raw.endsWith('\n')) payload += newline;
  payload += text;

  await appendText(absolutePath, payload, newline);

  return {
    path: relative,
    created: !existing,
    bytesAppended: Buffer.byteLength(payload, 'utf8'),
    entryDate: date,
    preview: `${input.type} | ${input.summary.split('\n')[0]?.slice(0, 120) ?? ''}`,
  };
}
