/**
 * search.ts — SEARCH（本地文件系统检索，第 1 版）。
 *
 * 刻意**不引入**：embedding / vector database / RAG / Elasticsearch。
 * 第 1 版只用：文件名、标题、标题层级（heading）、frontmatter、正文 + 简单 relevance score。
 *
 * 架构上为将来预留：本模块只暴露 `search(ctx, query)` 这个窄接口，
 * 未来换成 SQLite FTS5 / 混合检索时，调用方（工具层）无需改动。
 * 缓存策略：只做**进程内**的内容缓存以加速重复查询，它永远不是事实真源
 * （事实真源始终是 Vault 里的 Markdown）。
 */

import * as fs from 'node:fs/promises';
import type { Dirent } from 'node:fs';
import * as path from 'node:path';
import { KnowledgeError } from './errors.js';
import { readSnapshot } from './fsx.js';
import { parseMarkdown, toPlainText } from './markdown.js';
import type { VaultContext } from './vault.js';

/** 永不索引的工具目录（与 00_System/SCHEMA.md 的「排除路径」一致）。 */
export const EXCLUDED_DIRS = new Set([
  '.obsidian',
  '.copilot',
  '.claude',
  '.git',
  '.trash',
  '.smart-env',
  '.trashbin',
  'copilot',
  'node_modules',
]);

export const SEARCH_TYPES = ['wiki', 'decision', 'project', 'state', 'source', 'raw', 'system', 'any'] as const;
export type SearchType = (typeof SEARCH_TYPES)[number];

export interface SearchOptions {
  readonly query: string;
  readonly types?: readonly SearchType[];
  readonly limit?: number;
  /** 只在这些 Vault 内相对目录里搜索（可选） */
  readonly paths?: readonly string[];
  readonly now?: Date;
}

export interface SearchHit {
  readonly title: string;
  readonly path: string;
  readonly type: SearchType;
  readonly score: number;
  readonly snippet: string;
  /** 命中的维度，便于 Agent 判断为什么这条排前面 */
  readonly matched: readonly string[];
  readonly updated: string | null;
}

export interface SearchReport {
  readonly query: string;
  readonly total: number;
  readonly scanned: number;
  readonly returned: number;
  readonly limit: number;
  readonly types: readonly SearchType[];
  readonly results: readonly SearchHit[];
  readonly warnings: readonly string[];
}

/** 分类：把 Vault 路径映射到规范里的 type 词表。 */
export function classifyPath(ctx: VaultContext, relative: string): SearchType {
  const { layout } = ctx;
  const p = relative;
  const startsWith = (prefix: string) => p === prefix || p.startsWith(prefix + '/');

  if (p === layout.state || p.endsWith(`/${layout.stateFile}`)) return 'state';
  if (startsWith(layout.decisions)) return 'decision';
  if (startsWith(layout.wiki)) return p.includes('/Sources/') ? 'source' : 'wiki';
  if (startsWith(layout.active) || startsWith(layout.archive)) return 'project';
  if (/^02_Raw\//.test(p) || /\/02_Raw\//.test(p)) return 'raw';
  if (/^0\d_/.test(p) || startsWith(layout.system)) return 'system';
  return 'any';
}

interface CandidateFile {
  readonly relative: string;
  readonly absolutePath: string;
  readonly type: SearchType;
}

/** 递归收集候选文件（排除工具目录）。 */
async function collectFiles(ctx: VaultContext, roots: readonly string[]): Promise<CandidateFile[]> {
  const out: CandidateFile[] = [];
  const seen = new Set<string>();

  const walk = async (dirAbs: string, dirRel: string, depth: number): Promise<void> => {
    if (depth > 8) return;
    let entries: Dirent[];
    try {
      entries = await fs.readdir(dirAbs, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      if (entry.isSymbolicLink()) continue; // 不跟随符号链接，与 pathguard 的策略一致
      if (entry.isDirectory()) {
        if (EXCLUDED_DIRS.has(entry.name)) continue;
        if (entry.name.startsWith('.') && entry.name !== '.') continue;
        await walk(path.join(dirAbs, entry.name), dirRel === '' ? entry.name : `${dirRel}/${entry.name}`, depth + 1);
        continue;
      }
      if (!entry.isFile()) continue;
      if (!entry.name.toLowerCase().endsWith('.md')) continue;

      const relative = dirRel === '' ? entry.name : `${dirRel}/${entry.name}`;
      if (seen.has(relative.toLowerCase())) continue;
      seen.add(relative.toLowerCase());
      out.push({
        relative,
        absolutePath: path.join(dirAbs, entry.name),
        type: classifyPath(ctx, relative),
      });
    }
  };

  for (const root of roots) {
    const { absolutePath, relative } = ctx.guard.resolveSearchRoot(root);
    await walk(absolutePath, relative, 0);
  }

  return out;
}

/** 词法归一化：小写、去标点，便于中英文混排匹配。 */
function normalize(text: string): string {
  return text.toLowerCase().replace(/\s+/g, ' ').trim();
}

/** 简单词干处理：英文去复数/ed/ing，中文保持原样。 */
function tokenize(query: string): string[] {
  const normalized = normalize(query);
  const raw = normalized.split(/[\s,，。、;；:：/|()（）\[\]【】"']+/).filter((t) => t.length > 0);
  const tokens = new Set<string>(raw);

  // 中文：整串之外再补 2-gram，保证「会话记忆」这类查询能命中「会话」「记忆」
  for (const t of raw) {
    if (/[\u4e00-\u9fff]/.test(t) && t.length >= 4) {
      for (let i = 0; i + 1 < t.length; i++) tokens.add(t.slice(i, i + 2));
    }
  }
  // 英文：去掉常见后缀
  for (const t of raw) {
    if (/^[a-z]{5,}$/.test(t)) {
      for (const suffix of ['ing', 'ed', 'es', 's']) {
        if (t.endsWith(suffix) && t.length - suffix.length >= 3) {
          tokens.add(t.slice(0, t.length - suffix.length));
          break;
        }
      }
    }
  }
  return [...tokens].filter((t) => t.length > 0);
}

function countOccurrences(haystack: string, needle: string): number {
  if (needle === '') return 0;
  let count = 0;
  let idx = haystack.indexOf(needle);
  while (idx !== -1) {
    count++;
    idx = haystack.indexOf(needle, idx + needle.length);
  }
  return count;
}

function makeSnippet(body: string, tokens: readonly string[], maxLength = 220): string {
  const plain = toPlainText(body, Number.MAX_SAFE_INTEGER);
  const lower = plain.toLowerCase();
  let bestIdx = -1;
  for (const token of tokens) {
    const idx = lower.indexOf(token);
    if (idx !== -1 && (bestIdx === -1 || idx < bestIdx)) bestIdx = idx;
  }
  if (bestIdx === -1) return plain.slice(0, maxLength);
  const start = Math.max(0, bestIdx - 60);
  const snippet = plain.slice(start, start + maxLength);
  return (start > 0 ? '…' : '') + snippet + (start + maxLength < plain.length ? '…' : '');
}

interface ScoredFile {
  readonly candidate: CandidateFile;
  readonly score: number;
  readonly matched: string[];
  readonly title: string;
  readonly snippet: string;
  readonly updated: string | null;
  readonly type: SearchType;
}

/** 执行检索。 */
export async function searchVault(ctx: VaultContext, options: SearchOptions): Promise<SearchReport> {
  const query = options.query?.trim() ?? '';
  if (query === '') {
    throw new KnowledgeError('invalid_argument', 'query 不能为空');
  }

  const limit = Math.min(Math.max(options.limit ?? 10, 1), 100);
  const types = options.types && options.types.length > 0 ? options.types : (['any'] as SearchType[]);
  const wantsEverything = types.includes('any');

  const tokens = tokenize(query);
  const normalizedQuery = normalize(query);

  const roots = options.paths && options.paths.length > 0 ? options.paths : ['.'];
  const candidates = await collectFiles(ctx, roots);
  const warnings: string[] = [];
  const scored: ScoredFile[] = [];

  for (const candidate of candidates) {
    if (!wantsEverything && !types.includes(candidate.type)) continue;

    let raw: string;
    try {
      const snapshot = await readSnapshot(candidate.absolutePath);
      raw = snapshot.raw;
    } catch {
      warnings.push(`无法读取，已跳过：${candidate.relative}`);
      continue;
    }

    const doc = parseMarkdown(raw);
    const fileName = path.basename(candidate.relative, '.md');
    const headingText = doc.headings.map((h) => h.text).join(' ');
    const fmText = Object.entries(doc.frontmatter)
      .map(([k, v]) => `${k}: ${String(v)}`)
      .join(' ');
    const bodyText = toPlainText(doc.body, Number.MAX_SAFE_INTEGER);

    const matched: string[] = [];
    let score = 0;

    // 1) 文件名（最高权重）
    const normFile = normalize(fileName);
    if (normFile === normalizedQuery) {
      score += 60;
      matched.push('filename-exact');
    } else if (normFile.includes(normalizedQuery)) {
      score += 30;
      matched.push('filename');
    }

    // 2) frontmatter 里的 title / type
    const fmTitle = typeof doc.frontmatter['title'] === 'string' ? doc.frontmatter['title'] : '';
    if (fmTitle && normalize(fmTitle).includes(normalizedQuery)) {
      score += 25;
      matched.push('frontmatter-title');
    }

    // 3) heading
    const normHeadings = normalize(headingText);
    if (normHeadings.includes(normalizedQuery)) {
      score += 20;
      matched.push('heading');
    }

    // 4) frontmatter 其它字段
    if (normalize(fmText).includes(normalizedQuery)) {
      score += 10;
      matched.push('frontmatter');
    }

    // 5) 正文
    const normBody = normalize(bodyText);
    if (normBody.includes(normalizedQuery)) {
      score += 18;
      matched.push('body-phrase');
    }

    // 6) 逐 token 命中（覆盖部分匹配）
    let tokenHits = 0;
    for (const token of tokens) {
      let hit = false;
      if (normFile.includes(token)) {
        score += 8;
        hit = true;
        matched.push(`filename:${token}`);
      }
      if (normHeadings.includes(token)) {
        score += 4;
        hit = true;
        matched.push(`heading:${token}`);
      }
      const bodyCount = countOccurrences(normBody, token);
      if (bodyCount > 0) {
        score += Math.min(2 + Math.log2(1 + bodyCount), 6);
        hit = true;
      }
      if (hit) tokenHits++;
    }

    // 覆盖率加成：命中 token 越多越相关
    if (tokens.length > 1 && tokenHits > 0) {
      score *= 1 + (tokenHits / tokens.length) * 0.5;
    }

    if (score <= 0) continue;

    // 归一化到 0..1（便于 Agent 理解相对强弱，而不是面对无上界的分数）
    const normalizedScore = score / (score + 40);

    scored.push({
      candidate,
      score: normalizedScore,
      matched: [...new Set(matched)].slice(0, 6),
      title: fmTitle || headingText.split(' ').slice(0, 1).join(' ') || fileName,
      snippet: makeSnippet(doc.body, tokens),
      updated: typeof doc.frontmatter['updated'] === 'string' ? doc.frontmatter['updated'] : null,
      type: candidate.type,
    });
  }

  scored.sort((a, b) => b.score - a.score || a.candidate.relative.localeCompare(b.candidate.relative));

  const results: SearchHit[] = scored.slice(0, limit).map((s) => ({
    title: s.title,
    path: s.candidate.relative,
    type: s.type,
    score: Number(s.score.toFixed(4)),
    snippet: s.snippet,
    matched: s.matched,
    updated: s.updated,
  }));

  return {
    query,
    total: scored.length,
    scanned: candidates.length,
    returned: results.length,
    limit,
    types,
    results,
    warnings: warnings.slice(0, 20),
  };
}
