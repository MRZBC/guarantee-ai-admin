/**
 * decision-wiki-search.test.ts — Decision / Wiki / Search（规范第 48 节）。
 *
 * 覆盖：
 *   Decision: create / existing merge / 不覆盖既有结论
 *   Wiki:     type → 目录自动落位 / 已存在时追加不覆盖
 *   Search:   filename / title / heading / body / type 过滤 / limit
 */

import { afterEach, describe, expect, it } from 'vitest';
import { startMcpClient, type McpTestClient } from './helpers/mcpClient.js';
import { createFixture, existsVault, readVault, type Fixture } from './helpers/fixture.js';

let fixture: Fixture | null = null;
let client: McpTestClient | null = null;

async function setup(options: Parameters<typeof createFixture>[0] = {}): Promise<void> {
  fixture = createFixture(options);
  client = await startMcpClient({ serverEntry: fixture.serverEntry, projectRoot: fixture.projectDir });
  await client.initialize();
}

afterEach(async () => {
  if (client) await client.close();
  if (fixture) fixture.cleanup();
  client = null;
  fixture = null;
});

describe('knowledge_create_decision', () => {
  it('新建决策页，使用既有 Vault 的命名惯例（决策 - <标题>.md）', async () => {
    await setup();
    const r = await client!.callToolOk('knowledge_create_decision', {
      title: '采用标准 MCP over stdio',
      decision: 'Knowledge OS 的能力层实现为独立标准 MCP Server，通过 stdio 通信。',
      why: '避免把核心逻辑绑死在某一个 Agent Runtime 上。',
      context: '需要同时支持 Codex / OpenCode / Claude Code / DeepSeek Harness。',
      alternatives: ['为每个 Runtime 写插件', '只做 Skill 不做 MCP'],
      constraints: ['不依赖任何 Agent SDK', 'Node >= 20'],
      consequences: ['无需为每个 Runtime 维护适配逻辑', '流式/长任务场景需要额外设计'],
      revisitConditions: ['当某个 Runtime 不支持 stdio MCP 时'],
      related: ['Knowledge OS', 'Agent Skills'],
    });

    expect(r['created']).toBe(true);
    expect(r['path']).toBe('03_Wiki/Decisions/决策 - 采用标准 MCP over stdio.md');

    const doc = readVault(fixture!, '03_Wiki/Decisions/决策 - 采用标准 MCP over stdio.md');
    expect(doc).toContain('type: decision');
    expect(doc).toContain('status: active');
    expect(doc).toMatch(/date: \d{4}-\d{2}-\d{2}/);
    expect(doc).toContain('## Decision 决定');
    expect(doc).toContain('## Why 为什么');
    expect(doc).toContain('## Context 背景');
    expect(doc).toContain('## Alternatives 备选方案');
    expect(doc).toContain('## Constraints 约束');
    expect(doc).toContain('## Consequences 后果');
    expect(doc).toContain('## Revisit Conditions 重新评估的条件');
    expect(doc).toContain('## Related 相关');
  });

  it('同名决策已存在 → 合并（只填空白小节），不覆盖既有结论', async () => {
    await setup();
    const existing = `---
type: decision
status: active
date: 2026-01-01
---

# 决策 - 数据存储选型

## Decision 决定

使用 MySQL 8。

## Why 为什么

团队熟悉，运维成本低。

## Context 背景

（待补充）

## Alternatives 备选方案

（待补充）

## Revisit Conditions 重新评估的条件

（待补充）
`;
    const { writeVault } = await import('./helpers/fixture.js');
    writeVault(fixture!, '03_Wiki/Decisions/决策 - 数据存储选型.md', existing);

    const r = await client!.callToolOk('knowledge_create_decision', {
      title: '数据存储选型',
      decision: '改用 PostgreSQL。', // 与既有结论冲突
      why: '需要 pgvector。',
      context: '需要向量检索能力。', // 空白小节 → 应被填充
      revisitConditions: ['当 MySQL 支持原生向量检索时'], // 空白小节 → 应被填充
    });

    expect(r['created']).toBe(false);
    expect(r['merged']).toBe(true);

    const after = readVault(fixture!, '03_Wiki/Decisions/决策 - 数据存储选型.md');
    // 既有结论被保留
    expect(after).toContain('使用 MySQL 8。');
    expect(after).toContain('团队熟悉，运维成本低。');
    // 冲突内容没有被写进去
    expect(after).not.toContain('改用 PostgreSQL。');
    // 空白小节被填充
    expect(after).toContain('需要向量检索能力。');
    expect(after).toContain('当 MySQL 支持原生向量检索时');
    // 冲突被显式报告
    expect((r['conflicts'] as string[]).join('\n')).toContain('保留既有结论');

    const fields = r['fields'] as Array<Record<string, unknown>>;
    expect(fields.find((f) => f['field'] === 'decision')?.['action']).toBe('conflict-kept-existing');
    expect(fields.find((f) => f['field'] === 'context')?.['action']).toBe('filled-empty');
  });

  it('alternatives 写成「方案 —— 理由」时理由落在第二列，不重复方案本身', async () => {
    await setup();
    await client!.callToolOk('knowledge_create_decision', {
      title: '备选方案渲染',
      decision: 'd',
      why: 'w',
      alternatives: [
        '为每个 Runtime 写插件 —— 会导致多份分叉的核心逻辑',
        '只做 Skill 不做 MCP - 无法安全读写 Vault',
        '没写理由的方案',
      ],
    });

    const doc = readVault(fixture!, '03_Wiki/Decisions/决策 - 备选方案渲染.md');
    expect(doc).toContain('| 为每个 Runtime 写插件 | 会导致多份分叉的核心逻辑 |');
    expect(doc).toContain('| 只做 Skill 不做 MCP | 无法安全读写 Vault |');
    expect(doc).toContain('| 没写理由的方案 | （本次未记录理由，待补充） |');
    // 理由不应被塞进第一列
    expect(doc).not.toMatch(/\| 为每个 Runtime 写插件 —— 会导致多份分叉的核心逻辑 \|/);
  });

  it('缺少 title / decision / why 时拒绝', async () => {
    await setup();
    for (const args of [
      { title: '', decision: 'd', why: 'w' },
      { title: 't', decision: '', why: 'w' },
      { title: 't', decision: 'd', why: '' },
    ]) {
      const r = await client!.callTool('knowledge_create_decision', args);
      expect(r.isError).toBe(true);
    }
    // 没有创建任何文件
    const fs = await import('node:fs');
    const path = await import('node:path');
    const files = fs.readdirSync(path.join(fixture!.vaultDir, '03_Wiki/Decisions')).filter((f) => f.endsWith('.md'));
    expect(files).toEqual([]);
  });

  it('标题中的危险字符被清洗，不会造成路径穿越', async () => {
    await setup();
    // sanitizeFileTitle 会去掉 / 与 \ 等字符
    const r = await client!.callToolOk('knowledge_create_decision', {
      title: 'A/B\\C:D*E?F"G<H>I|J',
      decision: 'd',
      why: 'w',
    });
    expect(String(r['path'])).toMatch(/^03_Wiki\/Decisions\/决策 - [^/\\]+\.md$/);
    expect(String(r['path'])).not.toContain('..');
  });
});

describe('knowledge_upsert_wiki', () => {
  const typeToDir: Array<[string, string]> = [
    ['concept', '03_Wiki/Concepts'],
    ['technology', '03_Wiki/Technologies'],
    ['project', '03_Wiki/Projects'],
    ['lesson', '03_Wiki/Lessons'],
    ['source', '03_Wiki/Sources'],
    ['synthesis', '03_Wiki/Syntheses'],
  ];

  for (const [type, dir] of typeToDir) {
    it(`type=${type} 自动落位到 ${dir}/`, async () => {
      await setup();
      const title = type === 'synthesis' ? '如何选择向量存储' : `测试页面 ${type}`;
      const r = await client!.callToolOk('knowledge_upsert_wiki', {
        type,
        title,
        content: `这是 ${type} 类型的长期知识。`,
      });

      expect(String(r['path'])).toMatch(new RegExp(`^${dir}/`));
      expect(r['created']).toBe(true);
      const doc = readVault(fixture!, String(r['path']));
      expect(doc).toContain(`type: ${type}`);
      expect(doc).toContain(`这是 ${type} 类型的长期知识。`);
    });
  }

  it('synthesis 使用「综合 - 」前缀（与既有命名惯例一致）', async () => {
    await setup();
    const r = await client!.callToolOk('knowledge_upsert_wiki', {
      type: 'synthesis',
      title: '本地大模型推理的取舍',
      content: '结论……',
    });
    expect(r['path']).toBe('03_Wiki/Syntheses/综合 - 本地大模型推理的取舍.md');
  });

  it('页面已存在 → 追加带日期的更新小节，不覆盖原内容', async () => {
    await setup();
    await client!.callToolOk('knowledge_upsert_wiki', {
      type: 'technology',
      title: 'Spring AI Memory',
      content: '第一版认识：Spring AI 2.0 的 Memory 由 ChatMemory 接口抽象。',
    });

    const r = await client!.callToolOk('knowledge_upsert_wiki', {
      type: 'technology',
      title: 'Spring AI Memory',
      content: '补充：ConversationMemory 需要显式接入 ChatClient。',
    });

    expect(r['created']).toBe(false);
    expect(r['appended']).toBe(true);
    expect((r['warnings'] as string[]).join('\n')).toContain('没有覆盖');

    const doc = readVault(fixture!, '03_Wiki/Technologies/Spring AI Memory.md');
    expect(doc).toContain('第一版认识');
    expect(doc).toContain('补充：ConversationMemory');
    expect(doc).toMatch(/## 更新 \d{4}-\d{2}-\d{2}/);
  });

  it('完全相同的内容不会重复追加（幂等）', async () => {
    await setup();
    const args = { type: 'concept', title: '幂等测试', content: '同样的内容。' };
    await client!.callToolOk('knowledge_upsert_wiki', args);
    const r = await client!.callToolOk('knowledge_upsert_wiki', args);

    expect(r['appended']).toBe(false);
    expect((r['warnings'] as string[]).join('\n')).toContain('幂等');

    const doc = readVault(fixture!, '03_Wiki/Concepts/幂等测试.md');
    expect(doc.split('同样的内容。').length - 1).toBe(1);
  });

  it('页面已有「相关」小节时不再追加，避免出现两个同名小节', async () => {
    await setup();
    // 新建（此时 MCP 会依据 related 生成 ## Related 相关）
    await client!.callToolOk('knowledge_upsert_wiki', {
      type: 'technology',
      title: '相关小节去重',
      content: '正文。\n\n## 相关\n\n- [[技术 - 模块化单体与依赖方向]]',
      related: ['技术 - 模块化单体与依赖方向'],
    });

    const doc = readVault(fixture!, '03_Wiki/Technologies/相关小节去重.md');
    // 正文自带「## 相关」，因此不应该再补一个
    const relatedCount = (doc.match(/^##\s+(相关|Related 相关)\s*$/gm) ?? []).length;
    expect(relatedCount).toBe(1);

    // 只有 1 个一级标题（标题由 MCP 生成，正文不该再带一个）
    const h1Count = (doc.match(/^#\s+/gm) ?? []).length;
    expect(h1Count).toBe(1);
  });

  it('同名小节已存在时不重复追加（防止结构重复）', async () => {
    await setup();
    await client!.callToolOk('knowledge_upsert_wiki', {
      type: 'concept',
      title: '重复小节保护',
      content: '## 更新 2026-01-01\n\n第一次内容。',
    });
    const first = readVault(fixture!, '03_Wiki/Concepts/重复小节保护.md');
    expect((first.match(/^##\s+更新 2026-01-01\s*$/gm) ?? []).length).toBe(1);

    // 再用同一个 updateHeading 追加：正文不同，但小节名相同 → 应被拒绝而不是追加
    const r = await client!.callToolOk('knowledge_upsert_wiki', {
      type: 'concept',
      title: '重复小节保护',
      content: '第二次内容。',
      updateHeading: '## 更新 2026-01-01',
    });
    expect(r['appended']).toBe(false);
    expect((r['warnings'] as string[]).join('\n')).toContain('未重复追加');

    const after = readVault(fixture!, '03_Wiki/Concepts/重复小节保护.md');
    expect((after.match(/^##\s+更新 2026-01-01\s*$/gm) ?? []).length).toBe(1);
    expect(after).not.toContain('第二次内容。');
  });

  it('未知 type 被拒绝，且不会写任何文件', async () => {
    await setup();
    const r = await client!.callTool('knowledge_upsert_wiki', {
      type: 'diary',
      title: 'x',
      content: 'y',
    });
    expect(r.isError).toBe(true);
    expect(existsVault(fixture!, '03_Wiki/diary')).toBe(false);
  });

  it('Agent 无法通过 title 指定任意目录', async () => {
    await setup();
    const r = await client!.callTool('knowledge_upsert_wiki', {
      type: 'concept',
      title: '../../99_Evil',
      content: 'x',
    });
    // 要么被清洗成安全文件名，要么整体报错 —— 但绝不能落到 Vault 之外
    if (!r.isError) {
      const p = String((r.structuredContent as Record<string, unknown>)['path']);
      expect(p.startsWith('03_Wiki/Concepts/')).toBe(true);
      expect(p).not.toContain('..');
    }
  });
});

describe('knowledge_search', () => {
  async function seed(): Promise<void> {
    await setup();
    const { writeVault } = await import('./helpers/fixture.js');
    writeVault(
      fixture!,
      '03_Wiki/Technologies/Spring AI Memory.md',
      '---\ntype: technology\nstatus: draft\nupdated: 2026-02-01\ntitle: Spring AI Memory 会话记忆\n---\n\n# Spring AI Memory 会话记忆\n\n## 概览\n\nSpring AI 2.0 通过 ChatMemory 接口抽象会话记忆。\nConversationMemory 需要显式接入。\n',
    );
    writeVault(
      fixture!,
      '03_Wiki/Decisions/决策 - 会话记忆选型.md',
      '---\ntype: decision\nstatus: active\ndate: 2026-02-02\n---\n\n# 决策 - 会话记忆选型\n\n## Decision 决定\n\n采用 Redis-backed 会话记忆。\n',
    );
    writeVault(
      fixture!,
      '03_Wiki/Concepts/检索增强生成.md',
      '---\ntype: concept\nstatus: draft\n---\n\n# 检索增强生成\n\nRAG 把检索结果拼进提示词。这里不涉及会话记忆的实现细节。\n',
    );
    writeVault(
      fixture!,
      '02_Raw/Articles/OpenAI Memory 文章.md',
      '---\ntype: raw\n---\n\n# OpenAI Memory 文章\n\n原文摘录：memory 是一个 pending 特性。\n',
    );
  }

  it('按文件名匹配', async () => {
    await seed();
    const r = await client!.callToolOk('knowledge_search', { query: 'Spring AI Memory' });
    const results = r['results'] as Array<Record<string, unknown>>;
    expect(results.length).toBeGreaterThan(0);
    expect(results[0]!['path']).toBe('03_Wiki/Technologies/Spring AI Memory.md');
    expect(results[0]!['score']).toBeGreaterThan(0.5);
    expect(results[0]!['type']).toBe('wiki');
    expect((results[0]!['matched'] as string[]).join(',')).toContain('filename');
  });

  it('按 heading 匹配', async () => {
    await seed();
    const r = await client!.callToolOk('knowledge_search', { query: '概览' });
    const results = r['results'] as Array<Record<string, unknown>>;
    expect(results.some((x) => String(x['path']).includes('Spring AI Memory'))).toBe(true);
  });

  it('按正文匹配，并返回 snippet', async () => {
    await seed();
    const r = await client!.callToolOk('knowledge_search', { query: 'ChatMemory 接口抽象' });
    const results = r['results'] as Array<Record<string, unknown>>;
    expect(results.length).toBeGreaterThan(0);
    expect(String(results[0]!['snippet'])).toContain('ChatMemory');
  });

  it('按 frontmatter title 匹配', async () => {
    await seed();
    const r = await client!.callToolOk('knowledge_search', { query: '会话记忆' });
    const results = r['results'] as Array<Record<string, unknown>>;
    expect(results.some((x) => String(x['path']).includes('Spring AI Memory'))).toBe(true);
  });

  it('types 过滤生效', async () => {
    await seed();
    const onlyDecisions = await client!.callToolOk('knowledge_search', {
      query: '会话记忆',
      types: ['decision'],
    });
    const results = onlyDecisions['results'] as Array<Record<string, unknown>>;
    expect(results.length).toBeGreaterThan(0);
    for (const r of results) {
      expect(r['type']).toBe('decision');
    }

    const onlyRaw = await client!.callToolOk('knowledge_search', { query: 'memory', types: ['raw'] });
    for (const r of onlyRaw['results'] as Array<Record<string, unknown>>) {
      expect(r['type']).toBe('raw');
    }
  });

  it('limit 生效', async () => {
    await seed();
    const r1 = await client!.callToolOk('knowledge_search', { query: '记忆', limit: 1 });
    expect((r1['results'] as unknown[]).length).toBeLessThanOrEqual(1);
    expect(r1['limit']).toBe(1);

    const r2 = await client!.callToolOk('knowledge_search', { query: '记忆', limit: 2 });
    expect((r2['results'] as unknown[]).length).toBeLessThanOrEqual(2);
  });

  it('tokens 覆盖：中文 2-gram 与英文词干都能命中', async () => {
    await seed();
    // "Redis backed" 中间有空格，分词后仍应命中决策页
    const r = await client!.callToolOk('knowledge_search', { query: 'Redis 会话' });
    const results = r['results'] as Array<Record<string, unknown>>;
    expect(results.some((x) => String(x['path']).includes('会话记忆选型'))).toBe(true);
  });

  it('结果按 score 降序，且 score 在 0..1 之间', async () => {
    await seed();
    const r = await client!.callToolOk('knowledge_search', { query: '会话记忆' });
    const results = r['results'] as Array<Record<string, unknown>>;
    const scores = results.map((x) => Number(x['score']));
    for (const s of scores) {
      expect(s).toBeGreaterThan(0);
      expect(s).toBeLessThanOrEqual(1);
    }
    const sorted = [...scores].sort((a, b) => b - a);
    expect(scores).toEqual(sorted);
  });

  it('空 query 被拒绝', async () => {
    await seed();
    const r = await client!.callTool('knowledge_search', { query: '   ' });
    expect(r.isError).toBe(true);
  });

  it('不索引工具目录（.obsidian / .claude / copilot）', async () => {
    await seed();
    const { writeVault } = await import('./helpers/fixture.js');
    writeVault(fixture!, '.obsidian/plugins/secret.md', '# 会话记忆 插件内部文件\n');
    writeVault(fixture!, '.claude/skills/x/SKILL.md', '# 会话记忆 技能文件\n');
    writeVault(fixture!, 'copilot/conversations/a.md', '# 会话记忆 对话记录\n');

    const r = await client!.callToolOk('knowledge_search', { query: '会话记忆', limit: 100 });
    const paths = (r['results'] as Array<Record<string, unknown>>).map((x) => String(x['path']));
    expect(paths.some((p) => p.startsWith('.obsidian/'))).toBe(false);
    expect(paths.some((p) => p.startsWith('.claude/'))).toBe(false);
    expect(paths.some((p) => p.startsWith('copilot/'))).toBe(false);
  });
});
