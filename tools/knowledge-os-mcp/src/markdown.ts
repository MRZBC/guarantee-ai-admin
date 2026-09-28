/**
 * markdown.ts — Markdown / YAML frontmatter 解析与「定点外科修改」。
 *
 * 这是本项目最重要的「不破坏用户笔记」的实现：
 * 更新 STATE.md 时**绝不整文件重写**，只替换目标小节的内容，
 * 其余行（包括用户的措辞、空行、列表符号、中文标题）逐字保留。
 *
 * 支撑这一点的是 Vault 的标题惯例：`## English 中文`。
 * 因此匹配规则是「标题的任一段落命中别名即算命中」，
 * 于是 `## Next Action 下一步行动`、`## 下一步行动`、`## Next Action` 都能被找到。
 */

import { KnowledgeError } from './errors.js';

export interface Heading {
  readonly level: number;
  readonly text: string;
  readonly lineIndex: number;
}

export interface ParsedMarkdown {
  readonly frontmatter: Record<string, unknown>;
  /** frontmatter 起始行（`---` 那一行）；无 frontmatter 时为 null */
  readonly frontmatterStart: number | null;
  /** frontmatter 结束行（第二个 `---`） */
  readonly frontmatterEnd: number | null;
  /** 正文起点（frontmatter 之后的第一个非空前导行之后） */
  readonly bodyStart: number;
  readonly body: string;
  readonly headings: readonly Heading[];
  readonly lines: readonly string[];
  readonly newline: '\n' | '\r\n';
}

/** 解析 `---` 包裹的 YAML frontmatter（只做扁平键值，够本 Vault 用）。 */
export function parseFrontmatter(text: string): {
  frontmatter: Record<string, unknown>;
  frontmatterEnd: number | null;
  body: string;
} {
  const normalized = text.replace(/\r\n/g, '\n');
  const lines = normalized.split('\n');
  if (lines[0]?.trim() !== '---') {
    return { frontmatter: {}, frontmatterEnd: null, body: normalized };
  }
  let end = -1;
  for (let i = 1; i < lines.length; i++) {
    if (lines[i]!.trim() === '---') {
      end = i;
      break;
    }
  }
  if (end === -1) {
    return { frontmatter: {}, frontmatterEnd: null, body: normalized };
  }

  const fm: Record<string, unknown> = {};
  for (let i = 1; i < end; i++) {
    const line = lines[i]!;
    if (line.trim() === '' || line.trimStart().startsWith('#')) continue;
    const colon = line.indexOf(':');
    if (colon === -1) continue;
    const key = line.slice(0, colon).trim();
    const text = line.slice(colon + 1).trim();
    let value: unknown = text;
    if (text.length >= 2 && ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith("'") && text.endsWith("'")))) {
      value = text.slice(1, -1);
    } else if (text === '') {
      value = null;
    } else if (text === 'true') {
      value = true;
    } else if (text === 'false') {
      value = false;
    } else if (/^-?\d+$/.test(text)) {
      value = Number.parseInt(text, 10);
    }
    fm[key] = value;
  }

  return { frontmatter: fm, frontmatterEnd: end, body: lines.slice(end + 1).join('\n') };
}

function detectNewline(text: string): '\n' | '\r\n' {
  let crlf = 0;
  let lf = 0;
  for (let i = 0; i < text.length; i++) {
    if (text.charCodeAt(i) === 10) {
      if (i > 0 && text.charCodeAt(i - 1) === 13) crlf++;
      else lf++;
    }
  }
  return crlf > lf ? '\r\n' : '\n';
}

const HEADING_RE = /^(#{1,6})\s+(.*?)\s*$/;

export function parseMarkdown(text: string): ParsedMarkdown {
  const newline = detectNewline(text);
  const normalized = text.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n');
  const lines = normalized.split('\n');

  const { frontmatter, frontmatterEnd } = parseFrontmatter(normalized);

  const headings: Heading[] = [];
  // 跳过 frontmatter 区域，避免把 `---` 里的内容当标题
  const scanStart = frontmatterEnd === null ? 0 : frontmatterEnd + 1;
  let inFence = false;
  for (let i = scanStart; i < lines.length; i++) {
    const line = lines[i]!;
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      continue;
    }
    if (inFence) continue;
    const m = HEADING_RE.exec(line);
    if (m) {
      headings.push({ level: m[1]!.length, text: m[2]!.trim(), lineIndex: i });
    }
  }

  return {
    frontmatter,
    frontmatterStart: frontmatterEnd === null ? null : 0,
    frontmatterEnd,
    bodyStart: frontmatterEnd === null ? 0 : frontmatterEnd + 1,
    body: frontmatterEnd === null ? normalized : lines.slice(frontmatterEnd + 1).join('\n'),
    headings,
    lines,
    newline,
  };
}

/** 标题的匹配键集合：`## Next Action 下一步行动` → {next action, 下一步行动}。 */
export function headingKeys(text: string): string[] {
  const cleaned = text.replace(/[*_`]/g, '').trim();
  const keys: string[] = [normalizeKey(cleaned)];

  // 中英混排惯例：第一个空格前是英文，之后是中文
  const spaceIdx = cleaned.search(/\s/);
  if (spaceIdx > 0) {
    const en = cleaned.slice(0, spaceIdx);
    const zh = cleaned.slice(spaceIdx + 1);
    keys.push(normalizeKey(en));
    keys.push(normalizeKey(zh));
  }

  // 中文段落也可能被空格分开（如 `## Handoff Notes 交接说明`）
  for (const part of cleaned.split(/\s{2,}|\s(?=[\u4e00-\u9fff])/)) {
    if (part.trim() !== '') keys.push(normalizeKey(part));
  }

  return [...new Set(keys.filter((k) => k !== ''))];
}

function normalizeKey(s: string): string {
  return s
    .toLowerCase()
    .replace(/[（(].*?[)）]/g, '')
    .replace(/[\s:：、,，.。/／\-—_]+/g, '')
    .trim();
}

export interface SectionSpec {
  /** 稳定的字段名（出现在工具返回值里） */
  readonly field: string;
  /** 可接受的标题别名（英文或中文，任意一个命中即可） */
  readonly aliases: readonly string[];
  /** 若目标文件里没有该小节，新建时使用的标题 */
  readonly canonicalHeading: string;
  /** 新建小节时插在哪个 field 之前；null 表示追加到文档末尾 */
  readonly before?: string | null;
}

export interface LocatedSection {
  readonly spec: SectionSpec;
  readonly heading: Heading;
  /** 正文行范围 [start, end) （不含标题行） */
  readonly bodyStart: number;
  readonly bodyEnd: number;
}

/** 在已解析文档中定位一个小节。 */
export function locateSection(doc: ParsedMarkdown, spec: SectionSpec): LocatedSection | null {
  const aliasKeys = spec.aliases.map(normalizeKey);
  const heading = doc.headings.find((h) => {
    const keys = headingKeys(h.text);
    return keys.some((k) => aliasKeys.includes(k));
  });
  if (!heading) return null;

  let end = doc.lines.length;
  for (const h of doc.headings) {
    if (h.lineIndex > heading.lineIndex && h.level <= heading.level) {
      end = h.lineIndex;
      break;
    }
  }

  return {
    spec,
    heading,
    bodyStart: heading.lineIndex + 1,
    bodyEnd: end,
  };
}

/** 取小节正文（已按 \n 归一化）。找不到小节返回 null。 */
export function readSection(doc: ParsedMarkdown, spec: SectionSpec): string | null {
  const located = locateSection(doc, spec);
  if (!located) return null;
  return doc.lines.slice(located.bodyStart, located.bodyEnd).join('\n');
}

function trimBlankEdges(lines: string[]): string[] {
  let start = 0;
  let end = lines.length;
  while (start < end && lines[start]!.trim() === '') start++;
  while (end > start && lines[end - 1]!.trim() === '') end--;
  return lines.slice(start, end);
}

/**
 * 定点替换一个小节的正文。返回新的完整文本。
 *
 * 行为：
 *  - 小节存在 → 只替换其正文行，标题行与其余小节逐字不动
 *  - 小节不存在 → 按 canonicalHeading 新建，插到 before 指定的小节之前（或文档末尾）
 *  - 传入 null → 小节正文被清空（但标题保留）
 */
export function replaceSection(
  doc: ParsedMarkdown,
  spec: SectionSpec,
  newBody: string | null,
): { text: string; created: boolean } {
  const lines = [...doc.lines];
  const bodyLines = newBody === null ? [] : trimBlankEdges(newBody.replace(/\r\n/g, '\n').split('\n'));
  const block = bodyLines.length === 0 ? [''] : ['', ...bodyLines, ''];

  const located = locateSection(doc, spec);
  if (located) {
    const before = lines.slice(0, located.bodyStart);
    const after = lines.slice(located.bodyEnd);
    const next = [...before, ...block, ...after];
    return { text: next.join('\n'), created: false };
  }

  // --- 新建小节 --------------------------------------------------------
  let insertAt = lines.length;
  if (spec.before) {
    // before 是另一个 SectionSpec.field，别名表由 state.ts 预先注册到 SECTION_ANCHORS
    const anchor = SECTION_ANCHORS[spec.before];
    const locatedAnchor = anchor ? locateSection(doc, anchor) : null;
    if (locatedAnchor) insertAt = locatedAnchor.heading.lineIndex;
  }

  const newBlock = ['', `## ${spec.canonicalHeading}`, ...block];
  const next = [...lines.slice(0, insertAt), ...newBlock, ...lines.slice(insertAt)];
  return { text: next.join('\n'), created: true };
}

/**
 * 锚点表：用于「新建小节时插在谁前面」。
 * 由 state.ts 在模块初始化时填充，避免 markdown.ts 反向依赖 state.ts。
 */
export const SECTION_ANCHORS: Record<string, SectionSpec> = {};

/** 更新（或插入）frontmatter 中的若干键，其余键逐字保留。 */
export function upsertFrontmatter(text: string, updates: Record<string, string | number>): string {
  const normalized = text.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n');
  const lines = normalized.split('\n');
  const keys = Object.keys(updates);
  if (keys.length === 0) return normalized;

  if (lines[0]?.trim() !== '---') {
    // 没有 frontmatter → 新建
    const fmLines = keys.map((k) => `${k}: ${formatFmValue(updates[k]!)}`);
    return ['---', ...fmLines, '---', '', ...lines].join('\n');
  }

  let end = -1;
  for (let i = 1; i < lines.length; i++) {
    if (lines[i]!.trim() === '---') {
      end = i;
      break;
    }
  }
  if (end === -1) return normalized;

  const remaining = new Set(keys);
  const out: string[] = [lines[0]!];
  for (let i = 1; i < end; i++) {
    const line = lines[i]!;
    const colon = line.indexOf(':');
    if (colon === -1) {
      out.push(line);
      continue;
    }
    const key = line.slice(0, colon).trim();
    if (remaining.has(key)) {
      out.push(`${key}: ${formatFmValue(updates[key]!)}`);
      remaining.delete(key);
    } else {
      out.push(line);
    }
  }
  for (const k of remaining) out.push(`${k}: ${formatFmValue(updates[k]!)}`);
  out.push('---');
  return [...out, ...lines.slice(end + 1)].join('\n');
}

function formatFmValue(v: string | number): string {
  if (typeof v === 'number') return String(v);

  // 统一去掉调用方可能已经带上的引号，避免出现 phase: "\"\"" 这种双重转义
  let s = v.trim();
  if (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'")))) {
    s = s.slice(1, -1);
  }

  if (s === '') return '""';

  // 需要引号的情形：含特殊字符，或看起来像非字符串
  const needsQuote = !/^[A-Za-z0-9\u4e00-\u9fff][A-Za-z0-9\u4e00-\u9fff ._\-/]*$/.test(s) ||
    /^(true|false|null|~|-?\d+)$/.test(s) ||
    /[:#]/.test(s) ||
    s !== s.trim();
  return needsQuote ? `"${s.replace(/"/g, '\\"')}"` : s;
}

/** 把 Markdown 正文渲染为紧凑的一行摘要（用于搜索结果 snippet）。 */
export function toPlainText(markdown: string, maxLength = 200): string {
  const noFm = parseFrontmatter(markdown).body;
  const text = noFm
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*>\s?/gm, '')
    .replace(/[*_~]/g, '')
    .replace(/\|/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return text.length <= maxLength ? text : text.slice(0, maxLength - 1) + '…';
}

/** 简易文件名清洗，用于自动生成安全文件名（去掉 Vault 禁止的字符）。 */
export function sanitizeFileTitle(title: string): string {
  const cleaned = title
    .replace(/[\u0000-\u001f]/g, '')
    .replace(/[#^[\]|\\/:*?"<>]/g, '')
    .replace(/\.{2,}/g, '.') // 折叠连续的点，避免生成 `..` 这类容易误读的名字
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^[.\s]+/, '')
    .replace(/[.\s]+$/, '')
    .trim();
  if (cleaned === '') {
    throw new KnowledgeError('invalid_argument', 'title 清洗后为空，无法生成文件名', { title });
  }
  return cleaned;
}
