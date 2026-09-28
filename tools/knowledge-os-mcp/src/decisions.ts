/**
 * decisions.ts — DECISION（为什么这样做）。
 *
 * 关键约束：
 *   - 目标路径由 Server 决定，模型只给标题与内容
 *   - 同名 Decision 已存在时执行**合并**：只填空白小节，绝不覆盖既有结论
 *   - 冲突（同一小节已有不同内容）会被报告出来，由 Agent 决定，而不是静默改写
 */

import { KnowledgeError } from './errors.js';
import { atomicWrite, tryReadSnapshot } from './fsx.js';
import {
  parseMarkdown,
  readSection,
  replaceSection,
  toPlainText,
  upsertFrontmatter,
  type ParsedMarkdown,
  type SectionSpec,
} from './markdown.js';
import { type FieldSpec } from './fields.js';
import { DECISION_PREFIX } from './layout.js';
import { sanitizeFileTitle } from './markdown.js';
import { formatDate } from './state.js';
import { requireWritable, type VaultContext } from './vault.js';

export interface DecisionInput {
  readonly title: string;
  readonly decision: string;
  readonly why: string;
  readonly context?: string;
  readonly alternatives?: readonly string[];
  readonly constraints?: readonly string[];
  readonly consequences?: readonly string[];
  readonly revisitConditions?: readonly string[];
  readonly related?: readonly string[];
  readonly status?: 'proposed' | 'active' | 'superseded' | 'revisit';
  readonly now?: Date;
}

export interface DecisionFieldReport {
  readonly field: string;
  readonly action: 'created-section' | 'filled-empty' | 'unchanged' | 'conflict-kept-existing';
  readonly existingPreview?: string;
}

export interface DecisionReport {
  readonly path: string;
  readonly created: boolean;
  readonly merged: boolean;
  readonly fields: readonly DecisionFieldReport[];
  readonly conflicts: readonly string[];
  readonly newHash: string;
}

/** Decision 页的小节定义（对齐 00_System/templates/decision.md.template）。 */
const DECISION_SECTIONS: readonly FieldSpec[] = [
  { field: 'decision', aliases: ['Decision 决定', 'decision', '决定'], canonicalHeading: 'Decision 决定', required: true },
  { field: 'why', aliases: ['Why 为什么', 'why', '为什么'], canonicalHeading: 'Why 为什么', required: true },
  { field: 'context', aliases: ['Context 背景', 'context', '背景'], canonicalHeading: 'Context 背景' },
  { field: 'alternatives', aliases: ['Alternatives 备选方案', 'alternatives', '备选方案'], canonicalHeading: 'Alternatives 备选方案' },
  { field: 'constraints', aliases: ['Constraints 约束', 'constraints', '约束'], canonicalHeading: 'Constraints 约束' },
  { field: 'consequences', aliases: ['Consequences 后果', 'consequences', '后果'], canonicalHeading: 'Consequences 后果' },
  { field: 'revisitConditions', aliases: ['Revisit Conditions 重新评估的条件', 'revisit conditions', '重新评估的条件', '重新评估条件'], canonicalHeading: 'Revisit Conditions 重新评估的条件' },
  { field: 'related', aliases: ['Related 相关', 'related', '相关'], canonicalHeading: 'Related 相关' },
];

/** 模板占位符 / 「无」类字样：出现这些内容说明该小节实际上还没被填过。 */
const PLACEHOLDER_LINE = /^(无。?|none\.?|n\/a|tbd|todo|待补充|待填|待定|待确认|未记录|本次未记录[^。]*|\.\.\.|…)$/i;

/**
 * 判断一个小节是否「还是空的」。
 *
 * 两条判据，任一成立即视为空：
 *   1. 逐行都是 Markdown 结构（标题/引用/列表符号/表格分隔线）或占位符 —— 没有任何实质内容
 *   2. 整段只包含模板占位符（例如 `（待补充）`、`（本次未记录理由，待补充）`）
 *
 * 只要出现一行真正的内容，就判定为「非空」，于是合并时绝不覆盖。
 */
export function isEmptySection(raw: string | null): boolean {
  if (raw === null) return true;

  const lines = raw
    .replace(/\r\n/g, '\n')
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l !== '');

  /** 去掉行内的 Markdown 包装与中英文括号后，看还剩什么。 */
  const plain = (line: string): string =>
    line
      .replace(/^[-*+]\s*(\[[ xX]\]\s*)?/, '') // 列表符号与复选框
      .replace(/^#{1,6}\s*/, '') // 标题
      .replace(/^>\s*/, '') // 引用
      .replace(/^\*\*|\*\*$/g, '') // 粗体
      .trim()
      .replace(/^[（(]\s*/, '')
      .replace(/\s*[)）]$/, '')
      .trim();

  /** 表格分隔线（| --- | --- |）没有信息量。 */
  const isTableSeparator = (line: string): boolean => /^\|[\s:|-]+\|$/.test(line);

  const substantive = lines
    .filter((l) => !isTableSeparator(l))
    .map(plain)
    .filter((l) => l !== '')
    .filter((l) => !PLACEHOLDER_LINE.test(l))
    .filter((l) => !/^（?本次未记录[^）)]*[）)]?$/.test(l));

  return substantive.length === 0;
}

function renderDecisionValue(field: string, value: unknown, now: Date): string {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') return value.trim();

  if (Array.isArray(value)) {
    const items = value.map((v) => String(v).trim()).filter((v) => v !== '');
    if (items.length === 0) return '';
    if (field === 'alternatives') {
      // Decision 模板用两列表格记录备选方案。
      // 支持调用方写成 "方案 —— 为什么没选它"（中文破折号）或 "方案 - 理由"，
      // 这样理由会落到第二列，而不是重复方案本身。
      const rows = items.map((raw) => {
        const m = /^(.*?)\s*(?:——|—|--|\s-\s)\s*(.+)$/.exec(raw);
        if (m && m[1]!.trim() !== '' && m[2]!.trim() !== '') {
          return `| ${m[1]!.trim()} | ${m[2]!.trim()} |`;
        }
        if (/[|]/.test(raw)) {
          // 调用方直接给了整行表格内容
          return `| ${raw.replace(/^\||\|$/g, '').trim()} |`;
        }
        return `| ${raw} | （本次未记录理由，待补充） |`;
      });
      return ['| 备选方案 | 为什么没选它 |', '| --- | --- |', ...rows].join('\n');
    }
    if (field === 'related') {
      return items.map((v) => (v.startsWith('[[') ? `- ${v}` : `- [[${v}]]`)).join('\n');
    }
    return items.map((v) => `- ${v}`).join('\n');
  }

  void now;
  return String(value);
}

/**
 * 渲染 Consequences 小节。
 *
 * 模板把后果分成「正面 / 负面」两半。调用方可以用 `正面：…` / `负面：…` 前缀
 * 指定归属（也接受 `+` / `-`）；没有前缀的条目归入「正面」。
 * 这样既不会把负面后果塞进正面，也不会在负面里留下假的「待补充」。
 */
function renderConsequences(value: readonly string[] | undefined): string {
  if (!value || value.length === 0) return '';

  const positive: string[] = [];
  const negative: string[] = [];
  for (const raw of value) {
    const text = String(raw).trim();
    if (text === '') continue;
    const m = /^(正面|负面|优点|缺点|\+|-)\s*[:：]\s*(.*)$/.exec(text);
    if (m) {
      const kind = m[1]!;
      const body = m[2]!.trim();
      if (kind === '负面' || kind === '缺点' || kind === '-') negative.push(body);
      else positive.push(body);
      continue;
    }
    positive.push(text);
  }

  const lines: string[] = ['**正面**', ''];
  lines.push(...(positive.length ? positive.map((v) => `- ${v}`) : ['- 无。']));
  lines.push('');
  lines.push('**负面**', '');
  lines.push(...(negative.length ? negative.map((v) => `- ${v}`) : ['- 无。']));
  return lines.join('\n');
}

/** 组装一个全新的 Decision 页（使用模板的完整结构）。 */
function renderDecisionDocument(input: DecisionInput, now: Date): string {
  const date = formatDate(now);
  const sections: string[] = [];

  sections.push('## Decision 决定', '', input.decision.trim(), '');
  sections.push('## Why 为什么', '', input.why.trim(), '');
  if (input.context?.trim()) sections.push('## Context 背景', '', input.context.trim(), '');

  const alternatives = renderDecisionValue('alternatives', input.alternatives, now);
  if (alternatives) sections.push('## Alternatives 备选方案', '', alternatives, '');

  const constraints = renderDecisionValue('constraints', input.constraints, now);
  if (constraints) sections.push('## Constraints 约束', '', constraints, '');

  const consequences = renderConsequences(input.consequences);
  if (consequences) sections.push('## Consequences 后果', '', consequences, '');

  const revisit = renderDecisionValue('revisitConditions', input.revisitConditions, now);
  if (revisit) sections.push('## Revisit Conditions 重新评估的条件', '', revisit, '');

  const related = renderDecisionValue('related', input.related, now);
  if (related) sections.push('## Related 相关', '', related, '');

  return [
    '---',
    'type: decision',
    `status: ${input.status ?? 'active'}`,
    `date: ${date}`,
    '---',
    '',
    `# ${DECISION_PREFIX}${input.title.trim()}`,
    '',
    '> **本文件是什么：** 一个具有长期影响的选择，以及它背后的理由。',
    '> Wiki 说什么是真的，决策说我们选了什么、为什么。',
    '',
    sections.join('\n'),
  ].join('\n');
}

const specOf = (field: string): FieldSpec => {
  const found = DECISION_SECTIONS.find((s) => s.field === field);
  if (!found) throw new KnowledgeError('internal', `未知 Decision 字段：${field}`);
  return found;
};

const asSectionSpec = (f: FieldSpec): SectionSpec => ({
  field: f.field,
  aliases: f.aliases,
  canonicalHeading: f.canonicalHeading,
  before: f.before ?? null,
});

/**
 * 创建或合并一个 Decision。
 * 同名文件已存在 → 合并；不存在 → 新建。
 */
export async function createDecision(ctx: VaultContext, input: DecisionInput): Promise<DecisionReport> {
  requireWritable(ctx);

  if (!input.title?.trim()) {
    throw new KnowledgeError('invalid_argument', 'Decision 需要 title');
  }
  if (!input.decision?.trim()) {
    throw new KnowledgeError('invalid_argument', 'Decision 需要 decision（决定了什么）');
  }
  if (!input.why?.trim()) {
    throw new KnowledgeError('invalid_argument', 'Decision 需要 why（为什么）');
  }

  const title = sanitizeFileTitle(input.title);
  const fileName = `${DECISION_PREFIX}${title}.md`;
  const relative = `${ctx.layout.decisions}/${fileName}`;
  const { absolutePath } = ctx.guard.resolve(relative);

  const now = input.now ?? new Date();
  const existing = await tryReadSnapshot(absolutePath);

  // --- 新建 -------------------------------------------------------------
  if (!existing) {
    const content = renderDecisionDocument({ ...input, title }, now);
    const write = await atomicWrite(absolutePath, { content, newline: '\n' });
    return {
      path: relative,
      created: true,
      merged: false,
      fields: DECISION_SECTIONS.map((s) => ({ field: s.field, action: 'created-section' as const })),
      conflicts: [],
      newHash: write.hash,
    };
  }

  // --- 合并 -------------------------------------------------------------
  let doc: ParsedMarkdown = parseMarkdown(existing.raw);
  const reports: DecisionFieldReport[] = [];
  const conflicts: string[] = [];

  const incoming: Record<string, string> = {
    decision: input.decision.trim(),
    why: input.why.trim(),
    context: input.context?.trim() ?? '',
    alternatives: renderDecisionValue('alternatives', input.alternatives, now),
    constraints: renderDecisionValue('constraints', input.constraints, now),
    consequences: renderConsequences(input.consequences),
    revisitConditions: renderDecisionValue('revisitConditions', input.revisitConditions, now),
    related: renderDecisionValue('related', input.related, now),
  };

  for (const fieldSpec of DECISION_SECTIONS) {
    const value = incoming[fieldSpec.field] ?? '';
    if (value === '') {
      reports.push({ field: fieldSpec.field, action: 'unchanged' });
      continue;
    }

    const sectionSpec = asSectionSpec(fieldSpec);
    const current = readSection(doc, sectionSpec);

    if (current === null) {
      const result = replaceSection(doc, sectionSpec, value);
      doc = parseMarkdown(result.text);
      reports.push({ field: fieldSpec.field, action: 'created-section' });
      continue;
    }

    if (isEmptySection(current)) {
      const result = replaceSection(doc, sectionSpec, value);
      doc = parseMarkdown(result.text);
      reports.push({ field: fieldSpec.field, action: 'filled-empty' });
      continue;
    }

    // 已有实质内容 —— 绝不覆盖。
    if (current.trim() === value.trim()) {
      reports.push({ field: fieldSpec.field, action: 'unchanged' });
      continue;
    }

    reports.push({
      field: fieldSpec.field,
      action: 'conflict-kept-existing',
      existingPreview: toPlainText(current, 160),
    });
    conflicts.push(
      `小节「${fieldSpec.canonicalHeading}」已有内容，本次提供的新内容未被写入（保留既有结论）。` +
        `如需修订，请在 Vault 中人工编辑，或在标题中区分（例如加日期后缀）新建一个 Decision。`,
    );
  }

  const mergedText = upsertFrontmatter(doc.lines.join('\n'), {
    updated: formatDate(now),
  });

  const write = await atomicWrite(absolutePath, {
    content: mergedText,
    newline: existing.newline,
    bom: existing.bom,
    expectedHash: existing.hash,
    conflictLabel: 'Decision 文件在写入前被其他 Agent 修改，已中止以保护对方修改',
  });

  return {
    path: relative,
    created: false,
    merged: reports.some((r) => r.action === 'created-section' || r.action === 'filled-empty'),
    fields: reports,
    conflicts,
    newHash: write.hash,
  };
}

/** 读取一个 Decision 页（供 knowledge_read 之外的内部使用）。 */
export async function decisionExists(ctx: VaultContext, title: string): Promise<boolean> {
  const fileName = `${DECISION_PREFIX}${sanitizeFileTitle(title)}.md`;
  const { absolutePath } = ctx.guard.resolve(`${ctx.layout.decisions}/${fileName}`);
  return (await tryReadSnapshot(absolutePath)) !== null;
}

export { specOf as decisionSpecOf };
