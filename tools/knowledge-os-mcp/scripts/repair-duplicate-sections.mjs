#!/usr/bin/env node
/**
 * repair-duplicate-sections.mjs — 修复由 apply-knowledge（已于 2026-09-30 退役）早期缺陷
 * 生成的重复结构。本脚本本身仍可用于修 Vault 里既存的重复小节。
 *
 * 缺陷（已修）：`knowledge/wiki-*.md` 源文件自带 `# 标题`，而 MCP 也会依据
 * frontmatter.title 生成一个标题 → 页面上出现两个连续的同名一级标题。
 * 同理，源文件结尾的 `## 相关` 与 MCP 追加的 `## Related 相关` 撞名。
 *
 * 本脚本只做两件精确的事：
 *   1. 若第 2 个非空块是「与第 1 个一级标题同名的一级标题」，删掉它
 *   2. 若文件末尾是空的 `## Related 相关` / `## 相关` 小节，且前面已有一个，删掉末尾那个
 *
 * 它**只**处理这两个形状，不碰其它任何内容；改前先备份。
 *
 * 用法：
 *   node tools/knowledge-os-mcp/scripts/repair-duplicate-sections.mjs --dry-run
 *   node tools/knowledge-os-mcp/scripts/repair-duplicate-sections.mjs
 */

import { readFileSync, writeFileSync, readdirSync, statSync, copyFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const dryRun = process.argv.includes('--dry-run');

// 从 vault.local.yaml 读 Vault 路径（不猜、不扫描）
function readVaultPath() {
  const cfg = join(repoRoot, '.agent', 'vault.local.yaml');
  const text = readFileSync(cfg, 'utf8');
  const m = /^\s*path:\s*"?([^"\n]+)"?\s*$/m.exec(text);
  if (!m) throw new Error(`无法从 ${cfg} 解析 vault.path`);
  return m[1].trim();
}

const vaultRoot = readVaultPath();

/** 递归收集 .md 文件（排除工具目录）。 */
function collect(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.') || entry.name === 'node_modules') continue;
    const p = join(dir, entry.name);
    if (entry.isDirectory()) collect(p, out);
    else if (entry.name.endsWith('.md')) out.push(p);
  }
  return out;
}

/** 在给定的行数组里，按是否处于代码围栏内区分「真标题」。 */
function headingLines(lines) {
  const fence = [];
  let inFence = false;
  for (let i = 0; i < lines.length; i++) {
    if (/^\s*(```|~~~)/.test(lines[i])) {
      inFence = !inFence;
      continue;
    }
    if (!inFence) fence.push(i);
  }
  return new Set(fence);
}

function repair(text) {
  const hadCrlf = /\r\n/.test(text);
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const real = headingLines(lines);
  const notes = [];

  // --- 1. 删掉第二个同名的一级标题（含其后紧跟的空行） -------------------
  const h1Indexes = [...real].filter((i) => /^#\s+/.test(lines[i]));
  if (h1Indexes.length >= 2) {
    const first = lines[h1Indexes[0]].trim();
    const second = lines[h1Indexes[1]].trim();
    if (first === second) {
      let end = h1Indexes[1] + 1;
      while (end < lines.length && lines[end].trim() === '') end++;
      lines.splice(h1Indexes[1], end - h1Indexes[1]);
      notes.push(`删除重复一级标题：${second}`);
    }
  }

  // --- 2. 删掉末尾重复的「相关」小节 ------------------------------------
  // 条件从严：末尾这个「相关」小节必须 (a) 前面还有另一个「相关」小节，
  // 且 (b) 它的正文只有列表项（没有别的实质内容）。这样才不会误删真正的收尾章节。
  const realAfter = headingLines(lines);
  const h2Rel = [...realAfter].filter((i) => /^##\s+(相关|Related 相关|Related)\s*$/.test(lines[i]));
  if (h2Rel.length >= 2) {
    const lastIdx = h2Rel[h2Rel.length - 1];
    const body = lines.slice(lastIdx + 1).filter((l) => l.trim() !== '');
    const onlyLinks = body.every((l) => /^-\s+/.test(l.trim()));
    if (onlyLinks) {
      let from = lastIdx;
      while (from > 0 && lines[from - 1].trim() === '') from--;
      lines.splice(from);
      notes.push(`删除末尾重复的「${lines[lastIdx]?.trim() ?? '相关'}」小节（${body.length} 条链接）`);
    }
  }

  if (notes.length === 0) return { changed: false, notes };

  let out = lines.join('\n').replace(/\n{3,}/g, '\n\n').replace(/\n*$/, '\n');
  if (hadCrlf) out = out.replace(/\n/g, '\r\n');
  return { changed: true, notes, text: out };
}

const files = collect(vaultRoot);
let changed = 0;
let skipped = 0;

for (const file of files) {
  const original = readFileSync(file, 'utf8');
  const result = repair(original);
  if (!result.changed) {
    skipped++;
    continue;
  }
  changed++;
  console.log(`${dryRun ? '·' : '✓'} ${file.slice(vaultRoot.length)}`);
  for (const n of result.notes) console.log(`    - ${n}`);
  if (!dryRun) {
    copyFileSync(file, `${file}.bak`);
    writeFileSync(file, result.text, 'utf8');
  }
}

console.log(`\n扫描 ${files.length} 个 Markdown；需修复 ${changed} 个，已是最新 ${skipped} 个。`);
if (dryRun) console.log('（--dry-run：未写入任何文件）\n');
else if (changed > 0) console.log('每个被修改的文件都留有同名 .bak 备份，确认无误后可自行删除。\n');
