#!/usr/bin/env node
/**
 * cold-start-check.mjs — 对**真实 Vault** 做一次冷启动连续性验证。
 *
 * 它模拟的正是规范第 50 节的核心场景：
 *   一个全新的会话、全新的 Server 进程、**没有任何历史上下文**，
 *   只按 Skill 的标准流程走一遍：
 *
 *     knowledge_health → knowledge_resolve → knowledge_state
 *       → knowledge_search（必要时）→ knowledge_read（必要时）
 *       → 核对代码仓库真实状态
 *
 * 与自动化测试的区别：
 *   tests/continuity.test.ts 在临时夹具上验证「A 写 → B 读」，可重复。
 *   本脚本对**真实项目与真实 Vault** 跑同一个恢复流程，用来证明
 *   「换一个会话，Agent 真的能从 Vault 接上」这件事在真实数据上成立。
 *
 * 它**只读**，不修改任何文件。
 *
 * 用法：
 *   node tools/knowledge-os-mcp/scripts/cold-start-check.mjs
 */

import { spawn } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const serverEntry = resolve(here, '..', 'dist', 'index.js');

// ---------------------------------------------------------------------------
// 最小 MCP stdio 客户端（每次 new 都是一个新的 Server 进程 = 一个新的会话）
// ---------------------------------------------------------------------------
class Session {
  #child;
  #buffer = '';
  #pending = new Map();
  #nextId = 1;

  constructor(label) {
    this.label = label;
    this.#child = spawn(process.execPath, [serverEntry], {
      cwd: repoRoot,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, KNOWLEDGE_PROJECT_ROOT: repoRoot, KOS_SESSION: label },
    });
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
          entry(msg);
        }
      }
    });
  }

  #request(method, params) {
    const id = this.#nextId++;
    return new Promise((res, rej) => {
      const t = setTimeout(() => rej(new Error(`超时：${method}`)), 30_000);
      this.#pending.set(id, (v) => {
        clearTimeout(t);
        res(v);
      });
      this.#child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }

  async start() {
    await this.#request('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: `cold-start-${this.label}`, version: '1.0.0' },
    });
    this.#child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
    return this;
  }

  async ok(name, args) {
    const r = await this.#request('tools/call', { name, arguments: args ?? {} });
    if (r.error) throw new Error(`${name}: ${JSON.stringify(r.error)}`);
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
// 断言辅助
// ---------------------------------------------------------------------------
let passed = 0;
let failed = 0;
const check = (label, condition, detail = '') => {
  if (condition) {
    passed++;
    console.log(`  ✓ ${label}`);
  } else {
    failed++;
    console.log(`  ✗ ${label}${detail ? `\n      → ${detail}` : ''}`);
  }
};

// ---------------------------------------------------------------------------
console.log('\n=== 冷启动连续性验证（全新会话，无任何历史上下文）===');
console.log('唯一输入：「继续。」\n');

// ===========================================================================
// Session A —— 一个全新的 Server 进程，只做「恢复上下文」
// ===========================================================================
console.log('--- Session A：只凭 Vault 恢复上下文 ---');
const sessionA = await new Session('A').start();

const health = await sessionA.ok('knowledge_health');
check('knowledge_health.status 不是 unhealthy', health.status !== 'unhealthy', `实际：${health.status}`);
check('identityStatus === "ok"', health.identityStatus === 'ok', `实际：${health.identityStatus}`);
check('canWrite === true', health.canWrite === true);

const resolved = await sessionA.ok('knowledge_resolve');
check('解析出 projectId', resolved.projectId === 'guarantee-ai-admin', `实际：${resolved.projectId}`);
check('vaultPathSource 来自 vault.local.yaml', resolved.vaultPathSource === 'vault.local.yaml');
check('projectWorkspace 指向本项目工作区', resolved.projectWorkspace === '04_Work/Active/guarantee-ai-admin');

const state = await sessionA.ok('knowledge_state');
const proj = state.states.find((s) => s.scope === 'project');
const global = state.states.find((s) => s.scope === 'global');
check('存在项目 STATE.md', proj?.exists === true, `实际：${proj?.exists}`);
check('全局 active_project 已填写', global?.frontmatter?.active_project === 'guarantee-ai-admin');

const nextAction = String(proj.sections.nextAction?.raw ?? '').trim();
const handoff = String(proj.sections.handoffNotes?.raw ?? '').trim();
const completed = proj.sections.completed?.items ?? [];

check('Next Action 非空', nextAction.length > 20, `实际长度：${nextAction.length}`);
check('Next Action 不是「继续开发」这类模糊说法', !/^(继续开发|继续研究|继续优化|处理剩余问题)$/.test(nextAction));
check(
  'Next Action 点名了具体文件',
  /\.(md|json|jsonc|yml|yaml|toml|ps1|mjs|ts)\b/.test(nextAction),
  `内容：${nextAction.slice(0, 120)}`,
);
check('Handoff Notes 非空', handoff.length > 20);
check('Completed 记录了已完成工作', completed.length > 0, `实际 ${completed.length} 条`);

sessionA.close();

// ===========================================================================
// Session B —— 又一个**全新** Server 进程；验证 B 能独立恢复出同样结论
// （两个进程之间没有任何共享内存，唯一媒介是 Vault 里的 Markdown）
// ===========================================================================
console.log('\n--- Session B：另一个全新进程，独立恢复 + 查询知识 ---');
const sessionB = await new Session('B').start();

const stateB = await sessionB.ok('knowledge_state');
const projB = stateB.states.find((s) => s.scope === 'project');
const nextActionB = String(projB.sections.nextAction?.raw ?? '').trim();
check('B 独立恢复出的 Next Action 与 A 一致', nextActionB === nextAction, '两个会话读到了不同的 Next Action');

const search = await sessionB.ok('knowledge_search', { query: 'MCP stdio', limit: 5 });
const hits = search.results ?? [];
check('搜索命中 ≥ 1 条', hits.length > 0, `实际 ${hits.length} 条`);
check(
  '搜索命中了本次决策页',
  hits.some((h) => String(h.path).includes('Knowledge OS 采用标准 MCP over stdio')),
  `命中的路径：${hits.map((h) => h.path).join(' | ')}`,
);

const decisionHit = hits.find((h) => String(h.path).includes('Knowledge OS 采用标准 MCP over stdio'));
if (decisionHit) {
  const page = await sessionB.ok('knowledge_read', { path: decisionHit.path });
  check('决策页 type 为 decision', page.type === 'decision', `实际：${page.type}`);
  check('决策页含 Consequences（代价已诚实记录）', String(page.content).includes('## Consequences 后果'));
  check('决策页含 Revisit Conditions', String(page.content).includes('## Revisit Conditions'));
}

const wikiSearch = await sessionB.ok('knowledge_search', { query: '路径守卫 身份校验', limit: 5 });
check(
  '搜索命中路径安全知识页',
  (wikiSearch.results ?? []).some((h) => String(h.path).includes('Knowledge OS 的路径安全与身份校验')),
  `命中的路径：${(wikiSearch.results ?? []).map((h) => h.path).join(' | ')}`,
);

sessionB.close();

// ===========================================================================
// 核对「文档状态」与「代码/仓库真实状态」（规范第 30 与 58 节）
// ===========================================================================
console.log('\n--- 交叉核对：Documented State ↔ Actual Implementation ---');

check('MCP Server 构建产物存在', existsSync(join(repoRoot, 'tools/knowledge-os-mcp/dist/index.js')));
check('canonical Skill 存在', existsSync(join(repoRoot, '.agents/skills/knowledge-continuity/SKILL.md')));
check(
  'Skill 的 3 个 references 都存在',
  ['state-schema.md', 'knowledge-schema.md', 'decision-schema.md'].every((f) =>
    existsSync(join(repoRoot, '.agents/skills/knowledge-continuity/references', f)),
  ),
);
check('项目身份文件存在', existsSync(join(repoRoot, '.agent/project.yaml')));
check('本机 Vault 配置存在（且不入库）', existsSync(join(repoRoot, '.agent/vault.local.yaml')));
check('Vault 路径示例文件存在', existsSync(join(repoRoot, '.agent/vault.local.yaml.example')));

// 交叉核对：STATE 里 Next Action 提到的文件是否真的存在
const referenced = [
  ...nextAction.matchAll(/([\w./\\-]+\.(?:md|json|jsonc|yml|yaml|toml|ps1|mjs|ts|java|sql|xml))/g),
].map((m) => m[1]);

// 只核对「看起来属于本仓库」的引用：
//   - 绝对路径（C:/... 或 /Users/...）属于机器上的其他位置，无法在这里核对
//   - 不带目录分隔符的裸文件名（如 PROJECT.md）可能是 Vault 内的文件，
//     而不是仓库文件 —— 那正是「Vault 与仓库分离」的体现，不该判为缺失
const repoCandidates = referenced.filter((p) => {
  if (/^[A-Za-z]:/.test(p) || p.startsWith('/')) return false;
  return p.includes('/') || p.includes('\\');
});

console.log(`  · Next Action 引用了 ${referenced.length} 个文件路径：${referenced.join(', ') || '（无）'}`);
console.log(`  · 其中看起来属于本仓库的：${repoCandidates.join(', ') || '（无）'}`);
for (const rel of repoCandidates) {
  const candidates = [join(repoRoot, rel), join(repoRoot, 'tools/knowledge-os-mcp', rel)];
  check(`  引用的仓库文件存在：${rel}`, candidates.some((c) => existsSync(c)));
}
if (repoCandidates.length === 0) {
  console.log('  · 提示：Next Action 的路径引用都不指向仓库内文件（属人工配置或 Vault 内文件），无需在此核对。');
}

// 核对 adapters 是否齐备（STATE 里声称已交付）
const adapters = readdirSync(join(repoRoot, 'adapters')).filter((d) => !d.includes('.'));
check(
  'adapters 四个 Runtime 目录齐备',
  ['deepseek-harness', 'opencode', 'codex', 'claude'].every((d) => adapters.includes(d)),
  `实际：${adapters.join(', ')}`,
);

// 核对 .mcp.json 未写入 Vault 路径（规范要求）
const mcpJson = readFileSync(join(repoRoot, '.mcp.json'), 'utf8');
check('.mcp.json 中没有出现 Vault 绝对路径', !/guarantee-ai-admin-obsidian/.test(mcpJson));
check('.mcp.json 声明了 knowledge-os server', /"knowledge-os"/.test(mcpJson));

// 核对 .gitignore 已忽略 vault.local.yaml
const gitignore = readFileSync(join(repoRoot, '.gitignore'), 'utf8');
check('.gitignore 忽略了 .agent/vault.local.yaml', /\.agent\/vault\.local\.yaml/.test(gitignore));
check('.gitignore 保留了 vault.local.yaml.example 例外', /!vault\.local\.yaml\.example/.test(gitignore));

// ===========================================================================
console.log(`\n=== 结果：${passed} 项通过，${failed} 项失败 ===\n`);
process.exitCode = failed === 0 ? 0 : 1;
