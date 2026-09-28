/**
 * log.test.ts — LOG 追加（规范第 48 节 / Log）。
 *
 * 核心断言：append 而不是 overwrite。
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

describe('knowledge_append_log', () => {
  it('追加到文件末尾，绝不改写既有历史', async () => {
    const original = `---
type: activity-log
updated: 2026-01-01
---

# 活动日志

> **只追加，不重写。**

---

## [2026-01-01] MIGRATION | Vault 初始化

### Completed 已完成

- 建立 Vault 骨架。

---
`;
    await setup({ logContent: original });
    const originalLength = original.length;

    await client!.callToolOk('knowledge_append_log', {
      type: 'WORK',
      summary: '实现 Knowledge OS 的 MCP Server。',
      completed: ['10 个工具', '路径守卫'],
      verification: ['npm run build 通过', 'vitest 全绿'],
      decisions: ['[[决策 - 采用标准 MCP over stdio]]'],
      stateChange: { before: '只有规范文档', after: 'MCP Server 可运行' },
      nextAction: '接入 Codex / OpenCode / Claude Code。',
    });

    const after = readVault(fixture!, 'LOG.md');

    // 1) 既有内容逐字保留（前缀完全相同）
    expect(after.startsWith(original)).toBe(true);
    // 2) 文件变长了（有新内容）
    expect(after.length).toBeGreaterThan(originalLength);
    // 3) 新条目在末尾
    const idxNew = after.indexOf('实现 Knowledge OS 的 MCP Server。');
    const idxOld = after.indexOf('建立 Vault 骨架。');
    expect(idxOld).toBeGreaterThan(-1);
    expect(idxNew).toBeGreaterThan(idxOld);
  });

  it('条目包含所有传入字段', async () => {
    await setup();
    await client!.callToolOk('knowledge_append_log', {
      type: 'RESEARCH',
      summary: '调研 MCP SDK 当前版本。',
      completed: ['确认 @modelcontextprotocol/sdk 1.30.1'],
      verification: ['npm view 查询'],
      decisions: ['不依赖任何 Agent SDK'],
      stateChange: { before: '未知版本', after: '锁定 1.30.1' },
      blockers: ['无法自动验证 Codex 侧配置'],
      nextAction: '按官方文档核对 MCP 配置段。',
    });

    const log = readVault(fixture!, 'LOG.md');
    expect(log).toMatch(/## \[\d{4}-\d{2}-\d{2}\] RESEARCH \| demo-project/);
    expect(log).toContain('### Summary 摘要');
    expect(log).toContain('### Completed 已完成');
    expect(log).toContain('确认 @modelcontextprotocol/sdk 1.30.1');
    expect(log).toContain('### Verification 验证');
    expect(log).toContain('### Decisions 决策');
    expect(log).toContain('### State Change 状态变化');
    expect(log).toContain('Before 之前：未知版本');
    expect(log).toContain('After 之后：锁定 1.30.1');
    expect(log).toContain('### Blockers 阻塞项');
    expect(log).toContain('### Next Action 下一步');
  });

  it('LOG.md 不存在时自动创建', async () => {
    await setup({ logContent: null });
    expect(existsVault(fixture!, 'LOG.md')).toBe(false);

    const r = await client!.callToolOk('knowledge_append_log', {
      type: 'MAINTENANCE',
      summary: '初始化 LOG。',
    });
    expect(r['created']).toBe(true);
    expect(existsVault(fixture!, 'LOG.md')).toBe(true);
    expect(readVault(fixture!, 'LOG.md')).toContain('初始化 LOG。');
  });

  it('连续三次追加后，三条历史都在（append-only）', async () => {
    await setup();
    for (const n of [1, 2, 3]) {
      await client!.callToolOk('knowledge_append_log', {
        type: 'WORK',
        summary: `第 ${n} 次工作`,
        completed: [`步骤 ${n}`],
      });
    }
    const log = readVault(fixture!, 'LOG.md');
    expect(log).toContain('第 1 次工作');
    expect(log).toContain('第 2 次工作');
    expect(log).toContain('第 3 次工作');
    expect(log.indexOf('第 1 次工作')).toBeLessThan(log.indexOf('第 2 次工作'));
    expect(log.indexOf('第 2 次工作')).toBeLessThan(log.indexOf('第 3 次工作'));
  });

  it('project 字段可以覆盖默认 project.id', async () => {
    await setup();
    await client!.callToolOk('knowledge_append_log', {
      type: 'WORK',
      project: 'other-workstream',
      summary: '跨项目工作。',
    });
    expect(readVault(fixture!, 'LOG.md')).toContain('WORK | other-workstream');
  });

  it('空 summary 被拒绝（schema 校验在进入 handler 前就拦下）', async () => {
    await setup();
    const before = readVault(fixture!, 'LOG.md');
    const r = await client!.callTool('knowledge_append_log', { type: 'WORK', summary: '' });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toMatch(/validation error|invalid_argument/i);
    // 关键：LOG.md 一个字节都没变
    expect(readVault(fixture!, 'LOG.md')).toBe(before);
  });

  it('领域层守卫：summary / completed / nextAction 全空时拒绝（不依赖 schema）', async () => {
    // 直接测领域层，确保即使绕过工具 schema 也不会写出空条目
    const { appendLog } = await import('../src/log.js');
    const { openVault } = await import('../src/vault.js');
    await setup();
    const ctx = await openVault({ projectRoot: fixture!.projectDir, ignoreEnv: true });
    await expect(appendLog(ctx, { type: 'WORK', summary: '   ' })).rejects.toThrow(/至少需要/);
  });

  it('非法 type 被拒绝', async () => {
    await setup();
    const r = await client!.callTool('knowledge_append_log', { type: 'CHITCHAT', summary: 'x' });
    expect(r.isError).toBe(true);
  });

  it('保留既有的 CRLF 换行风格', async () => {
    await setup({ logContent: '---\r\ntype: activity-log\r\n---\r\n\r\n# 活动日志\r\n' });
    await client!.callToolOk('knowledge_append_log', { type: 'WORK', summary: 'CRLF 测试。' });
    const after = readVault(fixture!, 'LOG.md');
    expect(after).toContain('\r\n');
    expect(after.replace(/\r\n/g, '')).not.toContain('\n');
  });
});
