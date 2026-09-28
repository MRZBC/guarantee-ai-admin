/**
 * wiki.ts — WIKI（长期知识）。
 *
 * 只有具备长期价值的信息才进 Wiki：
 *   稳定的技术知识、可复用的方法、持久概念、项目经验、用户确认的长期偏好。
 *
 * 路径由 Server 根据 type 自动决定，模型**不能**传任意路径：
 *   concept    → 03_Wiki/Concepts/
 *   technology → 03_Wiki/Technologies/
 *   project    → 03_Wiki/Projects/
 *   lesson     → 03_Wiki/Lessons/
 *   source     → 03_Wiki/Sources/
 *   synthesis  → 03_Wiki/Syntheses/
 *   person     → 03_Wiki/People/
 *   company    → 03_Wiki/Companies/
 *   work       → 03_Wiki/Works/
 */

import { KnowledgeError } from './errors.js';
import { atomicWrite, tryReadSnapshot } from './fsx.js';
import { sanitizeFileTitle, toPlainText } from './markdown.js';
import { SYNTHESIS_PREFIX, WIKI_TYPE_DIRS, type WikiType, WIKI_TYPES } from './layout.js';
import { formatDate } from './state.js';
import { requireWritable, type VaultContext } from './vault.js';

export const WIKI_STATUS_VALUES = ['draft', 'active', 'proposed', 'unconfirmed', 'superseded', 'archived'] as const;
export type WikiStatus = (typeof WIKI_STATUS_VALUES)[number];

export interface WikiInput {
  readonly type: WikiType;
  readonly title: string;
  readonly content: string;
  readonly related?: readonly string[];
  readonly tags?: readonly string[];
  readonly status?: WikiStatus;
  /** 合并到已存在页面时，用这个标题区分新增内容；缺省为「## 更新 <日期>」 */
  readonly updateHeading?: string;
  readonly now?: Date;
}

export interface WikiReport {
  readonly path: string;
  readonly type: WikiType;
  readonly title: string;
  readonly created: boolean;
  readonly merged: boolean;
  readonly appended: boolean;
  readonly sectionsAdded: readonly string[];
  readonly warnings: readonly string[];
  readonly newHash: string;
}

/** 自动落位：type → Vault 内相对路径。 */
export function wikiPathForType(ctx: VaultContext, type: WikiType, title: string): string {
  const dir = WIKI_TYPE_DIRS[type];
  if (!dir) {
    throw new KnowledgeError('invalid_argument', `未知 Wiki type：${type}`, { allowed: WIKI_TYPES });
  }
  const safeTitle = sanitizeFileTitle(title);
  const fileName = type === 'synthesis' ? `${SYNTHESIS_PREFIX}${safeTitle}.md` : `${safeTitle}.md`;
  return `${ctx.layout.wiki}/${dir}/${fileName}`;
}

function renderRelated(related: readonly string[] | undefined): string {
  if (!related || related.length === 0) return '';
  return related
    .map((r) => String(r).trim())
    .filter((r) => r !== '')
    .map((r) => (r.startsWith('[[') ? `- ${r}` : `- [[${r}]]`))
    .join('\n');
}

function renderTags(tags: readonly string[] | undefined): string[] {
  if (!tags || tags.length === 0) return [];
  const clean = tags
    .map((t) => String(t).trim().replace(/^#/, '').replace(/[\s,]+/g, '-'))
    .filter((t) => t !== '');
  return clean.length ? [`tags: [${clean.join(', ')}]`] : [];
}

/** 小节标题的归一化 key（用于判重）。 */
function headingKey(s: string): string {
  return s.toLowerCase().replace(/^#+\s*/, '').replace(/[\s:：]+/g, '').trim();
}

/** 正文里是否已经自带「相关」小节。 */
function contentHasRelatedSection(content: string): boolean {
  for (const m of content.matchAll(/^#{1,6}\s+(.*)$/gm)) {
    const key = headingKey(m[1]!);
    if (key === headingKey('相关') || key === headingKey('Related 相关') || key === headingKey('Related')) {
      return true;
    }
  }
  return false;
}

/** 组装一个全新 Wiki 页。 */
export function renderWikiDocument(input: WikiInput, now: Date): string {
  const fm: string[] = [
    '---',
    `type: ${input.type}`,
    `status: ${input.status ?? 'draft'}`,
    `created: ${formatDate(now)}`,
    `updated: ${formatDate(now)}`,
    ...renderTags(input.tags),
    '---',
  ];

  const content = input.content.trim();
  const body: string[] = [fm.join('\n'), '', `# ${input.title.trim()}`, '', content, ''];

  /**
   * 只有正文**没有**自带「相关」小节时才自动补。
   * 否则页面上会出现两个「相关」小节 —— 内容没丢，但结构重复，
   * 人读起来会以为其中一个是空的。
   */
  const related = renderRelated(input.related);
  if (related && !contentHasRelatedSection(content)) {
    body.push('## Related 相关', '', related, '');
  }

  return body.join('\n');
}

/**
 * 新建或增补一个 Wiki 页。
 *
 * 已存在时**不覆盖**：只在文件末尾追加一个带日期的小节，保留全部历史内容。
 * 这样即使两个 Agent 对同一主题理解不同，也不会互相抹掉对方的结论。
 */
export async function upsertWiki(ctx: VaultContext, input: WikiInput): Promise<WikiReport> {
  requireWritable(ctx);

  if (!input.title?.trim()) {
    throw new KnowledgeError('invalid_argument', 'Wiki 页面需要 title');
  }
  if (!input.content?.trim()) {
    throw new KnowledgeError('invalid_argument', 'Wiki 页面需要 content');
  }
  if (!WIKI_TYPES.includes(input.type)) {
    throw new KnowledgeError('invalid_argument', `未知 Wiki type：${input.type}`, { allowed: WIKI_TYPES });
  }

  const now = input.now ?? new Date();
  const relative = wikiPathForType(ctx, input.type, input.title);
  const { absolutePath } = ctx.guard.resolve(relative);
  const existing = await tryReadSnapshot(absolutePath);

  const title = sanitizeFileTitle(input.title);

  // --- 新建 -------------------------------------------------------------
  if (!existing) {
    const content = renderWikiDocument({ ...input, title }, now);
    const write = await atomicWrite(absolutePath, { content, newline: '\n' });
    return {
      path: relative,
      type: input.type,
      title,
      created: true,
      merged: false,
      appended: false,
      sectionsAdded: ['(新建页面)'],
      warnings: [],
      newHash: write.hash,
    };
  }

  // --- 已存在：追加，不覆盖 --------------------------------------------
  const warnings: string[] = [];
  const sectionsAdded: string[] = [];
  const heading = (input.updateHeading?.trim() || `## 更新 ${formatDate(now)}`).replace(/^#+\s*/, '');

  // 完全重复的内容不重复追加（幂等性，避免 Agent 反复调用堆积垃圾）
  if (existing.raw.includes(input.content.trim())) {
    return {
      path: relative,
      type: input.type,
      title,
      created: false,
      merged: false,
      appended: false,
      sectionsAdded: [],
      warnings: [`内容已存在于 ${relative}，未重复追加（幂等）。`],
      newHash: existing.hash,
    };
  }

  /**
   * 重复小节保护：若目标文件里已经有同名小节（标题忽略大小写与前后空白），就不再追加。
   *
   * 为什么需要：调用方常常在 content 里自带 `## 相关` 之类的收尾小节，
   * 而本函数也会在末尾补一个。不检查的话页面上会出现两个同名小节 ——
   * 内容没丢，但结构被破坏，人读起来会以为其中一个是空的。
   */
  const existingHeadings = new Set(
    [...existing.raw.matchAll(/^#{1,6}\s+(.*)$/gm)].map((m) => headingKey(m[1]!)),
  );

  const newHeadingKey = headingKey(heading);
  if (existingHeadings.has(newHeadingKey)) {
    warnings.push(
      `页面已存在且已有「${heading}」小节，未重复追加（避免出现同名小节）。` +
        `若需改写该小节，请人工编辑 ${relative}。`,
    );
    return {
      path: relative,
      type: input.type,
      title,
      created: false,
      merged: false,
      appended: false,
      sectionsAdded: [],
      warnings,
      newHash: existing.hash,
    };
  }

  let appended = `\n## ${heading}\n\n${input.content.trim()}\n`;

  /**
   * 只有当页面里完全没有「相关」小节时才补。
   * 调用方的正文经常自带 `## 相关`，此时再补一个会让两个小节重复。
   * 语言写成中英任一种都算已有。
   */
  const hasRelatedSection = [...existingHeadings].some(
    (k) => k === headingKey('相关') || k === headingKey('Related 相关') || k === headingKey('Related'),
  );
  const related = renderRelated(input.related);
  if (related && !hasRelatedSection) {
    appended += `\n${related}\n`;
  }
  sectionsAdded.push(heading);

  const base = existing.raw.endsWith('\n') ? existing.raw : existing.raw + '\n';
  const mergedText = base + appended;

  const write = await atomicWrite(absolutePath, {
    content: mergedText,
    newline: existing.newline,
    bom: existing.bom,
    expectedHash: existing.hash,
    conflictLabel: 'Wiki 页面在写入前被其他 Agent 修改，已中止以保护对方修改',
  });

  warnings.push(
    `页面已存在，本次**没有覆盖**原有内容，只追加了「${heading}」小节。` +
      `如需整合，请人工阅读 ${relative} 后决定。`,
  );

  return {
    path: relative,
    type: input.type,
    title,
    created: false,
    merged: true,
    appended: true,
    sectionsAdded,
    warnings,
    newHash: write.hash,
  };
}

/** 读取一个 Wiki 页的纯文本摘要（内部用）。 */
export function wikiSummary(raw: string, max = 200): string {
  return toPlainText(raw, max);
}
