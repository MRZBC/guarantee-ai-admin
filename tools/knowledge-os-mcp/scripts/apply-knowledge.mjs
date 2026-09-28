#!/usr/bin/env node
/**
 * apply-knowledge.mjs — 把 `knowledge/` 下的知识源文件应用到真实 Vault。
 *
 * 为什么用「源文件 + 应用器」而不是一段一次性 JSON：
 *   1. 知识内容应可读、可评审、可 diff —— 它就是 Markdown，不是转义过的 JSON 字符串。
 *   2. 可重跑：内容修订后重新执行即可，且因为 MCP 的合并/追加语义而天然幂等。
 *   3. 可审计：仓库里能看到「到底往 Vault 写了什么」。
 *
 * ⚠️ `knowledge/` **不是**知识库本身。知识库是 Vault 里的 Markdown。
 *    这里是它的**源**，用于初始化与批量修订。
 *
 * 用法：
 *   node tools/knowledge-os-mcp/scripts/apply-knowledge.mjs            # 应用到真实 Vault
 *   node tools/knowledge-os-mcp/scripts/apply-knowledge.mjs --dry-run  # 只打印将执行什么
 */

import { spawn } from 'node:child_process';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const serverEntry = resolve(here, '..', 'dist', 'index.js');
const knowledgeDir = join(repoRoot, 'knowledge');

const dryRun = process.argv.includes('--dry-run');
const prune = process.argv.includes('--prune-placeholders');

/**
 * 由模板骨架留下的占位小节（标题里带 `<...>`，例如 `## Phase 1 — <阶段名>`）。
 * 知识源里有对应的正式小节后，这些占位应当被清掉，否则任务地图里会留一条
 * 「（待补充）」的孤儿。
 */
const PLACEHOLDER_HEADING = /^##\s+.*<[^>]*>.*$/;

// ---------------------------------------------------------------------------
// 极简 frontmatter / 文档解析
// ---------------------------------------------------------------------------

/** 解析开头的 `---` 块：只支持 `key: value` 与 `- item` 列表。 */
function parseFrontmatter(text) {
  const normalized = text.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n');
  if (!normalized.startsWith('---\n')) return { meta: {}, body: normalized };

  const end = normalized.indexOf('\n---', 3);
  if (end === -1) return { meta: {}, body: normalized };

  const block = normalized.slice(4, end);
  const body = normalized.slice(end + 4).replace(/^\n+/, '');

  const meta = {};
  let currentKey = null;
  for (const rawLine of block.split('\n')) {
    const line = rawLine.replace(/\s+$/, '');
    if (line.trim() === '' || line.trimStart().startsWith('#')) continue;

    const listItem = /^\s*-\s+(.*)$/.exec(line);
    if (listItem && currentKey) {
      if (!Array.isArray(meta[currentKey])) meta[currentKey] = [];
      meta[currentKey].push(unquote(listItem[1].trim()));
      continue;
    }

    const kv = /^([A-Za-z_][\w.-]*):\s?(.*)$/.exec(line);
    if (kv) {
      currentKey = kv[1];
      const value = kv[2].trim();
      meta[currentKey] = value === '' ? [] : unquote(value);
    }
  }
  return { meta, body };
}

function unquote(s) {
  if (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'")))) {
    return s.slice(1, -1);
  }
  return s;
}

/**
 * 按 `# 标题` 切成小节（只认一级标题，代码块内的 # 不算）。
 *
 * H1 之前的**说明性前言**（引用块、普通段落）会被视为源文件自身的文档而被丢弃 ——
 * 那些文字是写给「读源文件的人」的，不应该被写进知识库。
 * 但如果前言里出现了实际的 Markdown 结构（列表、代码围栏），说明作者可能写错了，
 * 这时直接报错而不是静默丢弃。
 */
function splitByH1(text, { file = '<source>' } = {}) {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const sections = [];
  const preamble = [];
  let current = null;
  let inFence = false;

  for (const line of lines) {
    if (/^\s*(```|~~~)/.test(line)) {
      if (current === null) {
        throw new Error(`${file}: H1 之前出现了代码围栏，无法判断归属`);
      }
      inFence = !inFence;
    }

    const h1 = !inFence && /^#\s+(.*?)\s*$/.exec(line);
    if (h1) {
      if (current) sections.push(current);
      current = { title: h1[1].trim(), lines: [] };
      continue;
    }
    if (current) current.lines.push(line);
    else if (/^\s*([-*+]\s|\d+[.)]\s)/.test(line)) {
      throw new Error(`${file}: H1 之前出现了列表项，可能是漏写了标题：${line.slice(0, 60)}`);
    } else if (line.trim() !== '') {
      preamble.push(line);
    }
  }
  if (current) sections.push(current);
  if (sections.length === 0) {
    throw new Error(`${file}: 没有找到任何一级标题（# ...）`);
  }
  return sections.map((s) => ({ title: s.title, body: s.lines.join('\n').replace(/^\n+|\n+$/g, '') }));
}

/** 按 `### 字段名` 切成字段（用于 STATE.md 源文件）。 */
function splitByH3(text) {
  return splitByHeadingLevel(text, 3);
}

/**
 * 按指定级别的标题切块（更高级别的标题留在块内容里）。
 * 这样 `### Verification 验证` 里的 `#### Verified 已验证` 会作为子内容保留，
 * 而不是被误当成另一个顶级字段。
 */
function splitByHeadingLevel(text, level) {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const fields = {};
  const order = [];
  let current = null;
  let inFence = false;
  const re = new RegExp(`^#{${level}}\\s+(.*?)\\s*$`);

  for (const line of lines) {
    if (/^\s*(```|~~~)/.test(line)) inFence = !inFence;

    const m = !inFence && re.exec(line);
    if (m) {
      current = m[1].trim();
      if (!(current in fields)) order.push(current);
      fields[current] = [];
      continue;
    }
    if (current) fields[current].push(line);
  }

  const out = {};
  for (const key of order) {
    out[key] = fields[key].join('\n').replace(/^\n+|\n+$/g, '');
  }
  return out;
}

/**
 * 把「顶层都是 `- ` 列表项」的正文转成数组，否则原样返回字符串。
 *
 * 支持**多行列表项**：第 0 列以 `- ` 开头的是新项，缩进的续行折叠进上一项，
 * 并在续行前补 `- `，这样渲染到 Markdown 表格单元格里仍是一个合法的子列表。
 * 判定为数组的条件要严格 —— 只要有一行既不是列表项也没有缩进，就整体当字符串。
 */
function asListOrString(body) {
  const lines = body.split('\n').filter((l) => l.trim() !== '');
  if (lines.length === 0) return body;

  const isItem = (l) => /^-(\s|$)/.test(l);
  const isIndented = (l) => /^\s/.test(l);
  if (!isItem(lines[0])) return body;
  if (!lines.every((l) => isItem(l) || isIndented(l))) return body;

  const items = [];
  for (const line of lines) {
    if (isItem(line)) {
      items.push(line.replace(/^-\s*/, '').trim());
    } else {
      items[items.length - 1] = `${items[items.length - 1]} - ${line.trim()}`;
    }
  }
  return items;
}

/** 强制成数组：字符串按行拆，已经是数组则原样返回。 */
function forceArray(body) {
  const asList = asListOrString(body);
  if (Array.isArray(asList)) return asList;
  return body
    .split('\n')
    .map((l) => l.replace(/^-\s*/, '').trim())
    .filter((l) => l !== '');
}

/** 强制成字符串：数组用空格连接（用于 why / context 这类散文小节）。 */
function forceString(body) {
  const asList = asListOrString(body);
  if (!Array.isArray(asList)) return body;
  return asList.join(' ');
}

/** `knowledge_create_decision` 各字段的类型（由工具 schema 决定）。 */
const DECISION_ARRAY_FIELDS = new Set(['alternatives', 'constraints', 'consequences', 'revisitConditions', 'related']);
const DECISION_STRING_FIELDS = new Set(['decision', 'why', 'context']);

/**
 * Consequences 小节在源文件里天然分成「**正面** / **负面**」两半，
 * 但 MCP 的 `consequences` 只接受一个字符串数组。
 * 这里把两半展平成带 `正面：` / `负面：` 前缀的条目，由服务端再按前缀分组渲染 ——
 * 这样负面后果不会被塞进正面，也不会丢。
 */
function consequenceArray(body) {
  const lines = body.split('\n');
  const out = [];
  let half = '正面';
  for (const raw of lines) {
    const line = raw.trim();
    if (line === '') continue;
    if (/^\*\*\s*正面\s*\*\*$|^正面$/.test(line)) {
      half = '正面';
      continue;
    }
    if (/^\*\*\s*负面\s*\*\*$|^负面$/.test(line)) {
      half = '负面';
      continue;
    }
    const text = line.replace(/^-\s*/, '').trim();
    if (text === '' || /^（.*待补充.*）$/.test(text)) continue;
    out.push(`${half}：${text}`);
  }
  return out;
}

// ---------------------------------------------------------------------------
// 各种源文件 → MCP 调用
// ---------------------------------------------------------------------------

function buildProjectCalls(file) {
  const { body } = parseFrontmatter(readFileSync(file, 'utf8'));
  const sections = splitByH1(body, { file });

  // 每个 H1 都作为 PROJECT.md 的一个小节
  const sectionMap = {};
  for (const s of sections) sectionMap[s.title] = s.body;

  return [
    {
      name: 'knowledge_update_state',
      arguments: { scope: 'project-definition', sections: sectionMap },
      note: `PROJECT.md：${sections.length} 个小节（${sections.map((s) => s.title).join(' / ')}）`,
    },
  ];
}

function buildStateCalls(file) {
  const { meta, body } = parseFrontmatter(readFileSync(file, 'utf8'));
  const scope = meta.scope;
  if (!scope) throw new Error(`${file} 缺少 frontmatter scope`);

  const fields = splitByH3(body);
  const args = { scope };
  const sectionMap = {};

  const KNOWN = new Set([
    'currentObjective', 'currentMilestone', 'activeProject', 'completed', 'inProgress',
    'nextAction', 'blockers', 'handoffNotes', 'importantDecisions', 'verification',
  ]);

  for (const [key, value] of Object.entries(fields)) {
    if (key === 'verification') {
      // ### Verification 验证 → 其下的 #### Verified 已验证 / #### Not Yet Verified 尚未验证
      args.verification = { verified: [], notVerified: [] };
      const sub = splitByHeadingLevel(value, 4);
      for (const [subKey, subValue] of Object.entries(sub)) {
        const list = asListOrString(subValue);
        const items = Array.isArray(list) ? list : [];
        if (/尚未验证|not yet verified/i.test(subKey)) args.verification.notVerified = items;
        else if (/已验证|^verified$/i.test(subKey)) args.verification.verified = items;
      }
      continue;
    }
    if (KNOWN.has(key)) args[key] = asListOrString(value);
    else sectionMap[key] = value;
  }

  if (meta.phase) args.phase = meta.phase;
  if (Object.keys(sectionMap).length > 0) args.sections = sectionMap;

  return [
    {
      name: 'knowledge_update_state',
      arguments: args,
      note: `STATE(${scope})：${Object.keys(fields).length} 个字段${Object.keys(sectionMap).length ? ` + ${Object.keys(sectionMap).length} 个小节` : ''}`,
    },
  ];
}

function buildTasksCalls(file) {
  const { meta, body } = parseFrontmatter(readFileSync(file, 'utf8'));
  const sections = splitByH1(body, { file });
  const sectionMap = {};
  for (const s of sections) sectionMap[s.title] = s.body;

  // 任务小节写 TASKS.md；这个调用不带任何语义字段，纯按标题写小节
  const args = {
    scope: meta.scope || 'project',
    sectionsFile: meta.sectionsFile || 'tasks',
    sections: sectionMap,
  };

  return [
    {
      name: 'knowledge_update_state',
      arguments: args,
      note: `TASKS.md：${sections.length} 个小节（${sections.map((s) => s.title).join(' / ')}）`,
    },
  ];
}

/** 剥掉正文开头的 `# 标题`（标题由 MCP 依据 frontmatter.title 自己生成）。 */
function stripLeadingH1(body) {
  const lines = body.replace(/\r\n/g, '\n').split('\n');
  let i = 0;
  while (i < lines.length && lines[i].trim() === '') i++;
  if (i < lines.length && /^#\s+/.test(lines[i])) {
    lines.splice(i, 1);
  }
  return lines.join('\n').replace(/^\n+/, '');
}

/**
 * 找出正文里「不在代码围栏内」的一级标题。
 *
 * wiki / decision 的正文里出现 `# 标题` 是危险的：
 *   - 一级标题归 MCP 的标题所有，正文里再来一个会变成重复标题
 *   - 更常见的是 shell / PowerShell 注释（`# 启动 MySQL`）忘了包进代码围栏，
 *     于是被当成标题渲染，页面结构就散了
 * 因此这里在应用前就报错，让作者修源文件，而不是把坏结构写进 Vault。
 */
function findStrayH1(body) {
  const out = [];
  let inFence = false;
  for (const line of body.replace(/\r\n/g, '\n').split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      continue;
    }
    if (!inFence && /^#\s+/.test(line)) out.push(line.trim());
  }
  return out;
}

function buildWikiCall(file) {
  const { meta, body } = parseFrontmatter(readFileSync(file, 'utf8'));
  if (!meta.type) throw new Error(`${file} 缺少 frontmatter type`);
  if (!meta.title) throw new Error(`${file} 缺少 frontmatter title`);

  const stray = findStrayH1(body);
  if (stray.length > 0) {
    throw new Error(
      `${file} 正文里有 ${stray.length} 个一级标题（不在代码围栏内）：${stray.join(' | ')}\n` +
        `    → 一级标题由 MCP 生成。若是 shell 注释，请包进 \`\`\` 代码围栏；若是正文标题，请删掉它。`,
    );
  }

  const args = { type: meta.type, title: meta.title, content: stripLeadingH1(body).trim() };
  if (meta.status) args.status = meta.status;
  if (Array.isArray(meta.related) && meta.related.length) args.related = meta.related;
  if (Array.isArray(meta.tags) && meta.tags.length) args.tags = meta.tags;

  return [{ name: 'knowledge_upsert_wiki', arguments: args, note: `Wiki(${meta.type})：${meta.title}` }];
}

function buildDecisionCall(file) {
  const { meta, body } = parseFrontmatter(readFileSync(file, 'utf8'));
  const sections = splitByH1(body, { file });
  if (sections.length !== 1) {
    throw new Error(`${file} 的决策正文必须只有 1 个一级标题（当前 ${sections.length} 个）`);
  }

  const fields = splitByH3(sections[0].body);
  const args = { title: meta.title || sections[0].title };
  if (meta.status) args.status = meta.status;

  for (const [key, value] of Object.entries(fields)) {
    if (key === 'consequences') {
      args[key] = consequenceArray(value);
    } else if (DECISION_ARRAY_FIELDS.has(key)) {
      args[key] = forceArray(value);
    } else if (DECISION_STRING_FIELDS.has(key)) {
      args[key] = forceString(value);
    } else {
      args[key] = value;
    }
  }

  for (const required of ['decision', 'why']) {
    if (!args[required]) throw new Error(`${file} 缺少必填小节：${required}`);
  }

  return [{ name: 'knowledge_create_decision', arguments: args, note: `Decision：${args.title}` }];
}

// ---------------------------------------------------------------------------
// 最小 MCP 客户端
// ---------------------------------------------------------------------------
class Client {
  #child;
  #buffer = '';
  #pending = new Map();
  #nextId = 1;
  stderr = [];

  constructor() {
    this.#child = spawn(process.execPath, [serverEntry], {
      cwd: repoRoot,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, KNOWLEDGE_PROJECT_ROOT: repoRoot },
    });
    this.#child.stderr.setEncoding('utf8');
    this.#child.stderr.on('data', (c) => this.stderr.push(c));
    this.#child.stdout.setEncoding('utf8');
    this.#child.stdout.on('data', (chunk) => {
      this.#buffer += chunk;
      let i;
      while ((i = this.#buffer.indexOf('\n')) !== -1) {
        const line = this.#buffer.slice(0, i).trim();
        this.#buffer = this.#buffer.slice(i + 1);
        if (!line) continue;
        const msg = JSON.parse(line);
        const entry = this.#pending.get(msg.id);
        if (entry) {
          this.#pending.delete(msg.id);
          entry.resolve(msg);
        }
      }
    });
  }

  #request(method, params) {
    const id = this.#nextId++;
    return new Promise((res, rej) => {
      const t = setTimeout(() => rej(new Error(`超时：${method}\n${this.stderr.join('').slice(-600)}`)), 60_000);
      this.#pending.set(id, {
        resolve: (v) => {
          clearTimeout(t);
          res(v);
        },
      });
      this.#child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }

  async start() {
    const r = await this.#request('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'apply-knowledge', version: '1.0.0' },
    });
    if (r.error) throw new Error(`initialize 失败：${JSON.stringify(r.error)}`);
    this.#child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
    return this;
  }

  async call(name, args) {
    const r = await this.#request('tools/call', { name, arguments: args });
    if (r.error) throw new Error(`${name}：${JSON.stringify(r.error)}`);
    if (r.result?.isError) {
      throw new Error(`${name} 返回错误：${r.result.content?.map((c) => c.text).join('\n')}`);
    }
    return r.result.structuredContent;
  }

  close() {
    try {
      this.#child.stdin.end();
    } catch {}
    setTimeout(() => this.#child.kill(), 1500);
  }
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
const files = readdirSync(knowledgeDir).filter((f) => f.endsWith('.md')).sort();
if (files.length === 0) {
  console.error(`knowledge/ 下没有 .md 源文件：${knowledgeDir}`);
  process.exit(1);
}

const plan = [];
for (const file of files) {
  const full = join(knowledgeDir, file);
  let calls;
  if (file === 'project.md') calls = buildProjectCalls(full);
  else if (file.startsWith('state')) calls = buildStateCalls(full);
  else if (file.startsWith('tasks')) calls = buildTasksCalls(full);
  else if (file.startsWith('wiki-')) calls = [buildWikiCall(full)].flat();
  else if (file.startsWith('decision-')) calls = [buildDecisionCall(full)].flat();
  else {
    console.warn(`跳过未识别命名的文件：${file}（前缀需为 project/state/tasks/wiki-/decision-）`);
    continue;
  }
  plan.push({ file, calls });
}

console.log(`\n=== 知识源 → ${dryRun ? '预演（不写入）' : '应用到真实 Vault'} ===\n`);
for (const { file, calls } of plan) {
  console.log(`${file}`);
  for (const c of calls) console.log(`  · ${c.name} — ${c.note}`);
}
console.log('');

if (dryRun) {
  console.log('（--dry-run：未连接 MCP，未写入任何文件）\n');
  process.exit(0);
}

const client = await new Client().start();
let failed = 0;

try {
  // --- 可选：先清掉模板骨架留下的占位小节 -------------------------------
  if (prune) {
    // knowledge_read 只接受**相对路径**，所以先用 knowledge_resolve 拿到权威路径，
    // 再把逻辑名（tasks）映射成实际相对路径 —— 不要自己硬编码目录。
    const resolved = await client.call('knowledge_resolve', {});
    const workspace = resolved.projectWorkspace;
    if (!workspace) throw new Error('knowledge_resolve 没有返回 projectWorkspace，无法定位 TASKS.md');

    const targets = [
      { label: 'PROJECT.md', scope: 'project-definition', readPath: `${workspace}/PROJECT.md` },
      { label: 'STATE.md', scope: 'project', readPath: `${workspace}/STATE.md` },
      { label: 'TASKS.md', scope: 'project', sectionsFile: 'tasks', readPath: `${workspace}/TASKS.md` },
    ];

    for (const t of targets) {
      let page;
      try {
        page = await client.call('knowledge_read', { path: t.readPath });
      } catch (err) {
        console.log(`· 跳过 ${t.label}（读取失败：${String(err.message).split('\n')[0]}）`);
        continue;
      }

      const placeholders = [...String(page.content).matchAll(/^##\s+(.*<[^>]*>.*)$/gm)].map((m) => m[1].trim());
      if (placeholders.length === 0) continue;

      const sections = {};
      for (const h of placeholders) sections[h] = '__KOS_DELETE_SECTION__';
      const delArgs = { scope: t.scope, sections };
      if (t.sectionsFile) delArgs.sectionsFile = t.sectionsFile;
      const res = await client.call('knowledge_update_state', delArgs);
      console.log(`⌫ 清除占位小节 ${t.readPath}：${placeholders.join(' / ')}（changed=${res.changedFields?.length ?? 0}）`);
    }
  }

  for (const { file, calls } of plan) {
    for (const c of calls) {
      try {
        const result = await client.call(c.name, c.arguments);
        const summary = [];
        if (result.changedFields) summary.push(`changed=${result.changedFields.length}`);
        if (result.skippedFields?.length) summary.push(`unchanged=${result.skippedFields.length}`);
        if (result.createdSections?.length) summary.push(`newSections=${result.createdSections.length}`);
        if (result.path) summary.push(`path=${result.path}`);
        if (result.created !== undefined) summary.push(`created=${result.created}`);
        if (result.appended !== undefined) summary.push(`appended=${result.appended}`);
        if (result.conflicts?.length) summary.push(`CONFLICTS=${result.conflicts.length}`);
        console.log(`✓ ${c.name.padEnd(28)} ${summary.join(' ') || 'ok'}`);
        for (const w of result.warnings ?? []) console.log(`    ⚠ ${w.split('\n')[0]}`);
      } catch (err) {
        failed++;
        console.error(`✗ ${c.name}  ${c.note}\n    ${String(err.message).split('\n')[0]}`);
      }
    }
  }
  console.log(`\n${failed === 0 ? '全部成功' : `${failed} 个调用失败`}\n`);
  process.exitCode = failed === 0 ? 0 : 1;
} finally {
  client.close();
}
