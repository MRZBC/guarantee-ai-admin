/**
 * integration.test.ts — 跨目录集成测试（规范第 49 节）。
 *
 * 真实场景：
 *   <tmp>/project/   ← 模拟代码仓库（.agent/...）
 *   <tmp>/vault/     ← 模拟 Obsidian Vault
 * 两者是**完全不同的目录**，通过 .agent/vault.local.yaml 绑定。
 *
 * 按规范顺序跑完整条链路：
 *   resolve → health → state → search → read → update_state → append_log
 */

import * as fs from 'node:fs';
import * as path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { startMcpClient, type McpTestClient } from './helpers/mcpClient.js';
import { createFixture, existsVault, readVault, type Fixture } from './helpers/fixture.js';

let fixture: Fixture;
let client: McpTestClient;

beforeAll(async () => {
  fixture = createFixture({ projectId: 'guarantee-ai-admin', projectName: '智能电子保函运营管理平台' });
  client = await startMcpClient({ serverEntry: fixture.serverEntry, projectRoot: fixture.projectDir });
  await client.initialize();
});

afterAll(async () => {
  await client.close();
  fixture.cleanup();
});

describe('跨目录集成：project 与 vault 是两个独立目录', () => {
  it('0. 前提：project 目录与 vault 目录确实不同', () => {
    expect(path.resolve(fixture.projectDir)).not.toBe(path.resolve(fixture.vaultDir));
    expect(fixture.vaultDir.startsWith(fixture.projectDir)).toBe(false);
    expect(fixture.projectDir.startsWith(fixture.vaultDir)).toBe(false);
  });

  it('1. resolve：解析出项目身份与 Vault 路径', async () => {
    const r = await client.callToolOk('knowledge_resolve');
    expect(r['projectId']).toBe('guarantee-ai-admin');
    expect(r['projectName']).toBe('智能电子保函运营管理平台');
    expect(r['vaultId']).toBe('guarantee-ai-admin');
    expect(String(r['vaultPath']).replace(/\\/g, '/')).toBe(fixture.vaultDir.replace(/\\/g, '/'));
    expect((r['identity'] as Record<string, unknown>)['canWrite']).toBe(true);
  });

  it('2. health：身份 OK、可写，但项目工作区尚缺失 → degraded', async () => {
    const h = await client.callToolOk('knowledge_health');
    expect(h['identityStatus']).toBe('ok');
    expect(h['canWrite']).toBe(true);
    expect(h['status']).toBe('degraded');
  });

  it('3. bootstrap（先 dryRun 预演，再真正执行）：只创建缺失项', async () => {
    const dry = await client.callToolOk('knowledge_bootstrap', { dryRun: true });
    expect(dry['dryRun']).toBe(true);
    const planned = (dry['created'] as Array<Record<string, unknown>>).map((c) => String(c['path']));
    expect(planned).toContain('04_Work/Active/guarantee-ai-admin/TASKS.md');
    // dryRun 不得真的写入
    expect(existsVault(fixture, '04_Work/Active/guarantee-ai-admin/TASKS.md')).toBe(false);

    const applied = await client.callToolOk('knowledge_bootstrap', { dryRun: false });
    expect(applied['applied']).toBe(true);
    expect(existsVault(fixture, '04_Work/Active/guarantee-ai-admin/TASKS.md')).toBe(true);

    // 幂等：第二次执行什么都不创建
    const again = await client.callToolOk('knowledge_bootstrap', { dryRun: false });
    expect((again['created'] as unknown[]).length).toBe(0);
    expect((again['skipped'] as unknown[]).length).toBeGreaterThan(0);
  });

  it('4. state（写）：创建 PROJECT.md 与项目 STATE.md', async () => {
    const def = await client.callToolOk('knowledge_update_state', {
      scope: 'project-definition',
      currentObjective: '把项目上下文从聊天历史中抽离，形成跨 Agent 的 Knowledge OS。',
    });
    expect(def['created']).toBe(true);

    const st = await client.callToolOk('knowledge_update_state', {
      scope: 'project',
      currentMilestone: 'Knowledge OS 基础设施',
      completed: ['建立 MCP Server', '建立 Skill'],
      inProgress: ['Runtime 适配层'],
      nextAction: '打开 .agent/project.yaml，确认 project.id 与 Vault 绑定一致。',
      blockers: [],
      verification: { verified: ['npm run build 通过'], notVerified: ['Codex 侧接入'] },
      handoffNotes: 'MCP 使用 stdio，任何支持 MCP 的 Runtime 都能接入。',
    });
    expect(st['created']).toBe(true);
    expect(existsVaultPath('04_Work/Active/guarantee-ai-admin/STATE.md')).toBe(true);
  });

  it('5. state（读）：读回刚写入的内容，三个 scope 都可见', async () => {
    const r = await client.callToolOk('knowledge_state');
    const states = r['states'] as Array<Record<string, unknown>>;

    const project = states.find((s) => s['scope'] === 'project')!;
    expect(project['exists']).toBe(true);
    const ps = project['sections'] as Record<string, Record<string, unknown>>;
    expect(ps['nextAction']?.['raw']).toContain('打开 .agent/project.yaml');
    expect(ps['completed']?.['items']).toEqual(['建立 MCP Server', '建立 Skill']);
    expect(ps['inProgress']?.['items']).toEqual(['Runtime 适配层']);
    expect(ps['handoffNotes']?.['raw']).toContain('任何支持 MCP 的 Runtime');
    expect(ps['verification']?.['raw']).toContain('npm run build 通过');

    const def = states.find((s) => s['scope'] === 'project-definition')!;
    const ds = def['sections'] as Record<string, Record<string, unknown>>;
    expect(ds['objective']?.['raw']).toContain('Knowledge OS');
  });

  it('6. wiki + decision + search + read 全链路', async () => {
    // 沉淀一条长期知识
    const wiki = await client.callToolOk('knowledge_upsert_wiki', {
      type: 'technology',
      title: 'Spring AI Memory',
      content: 'Spring AI 2.0 用 ChatMemory 抽象会话记忆；OpenAiChatModel 需要基于自身 options 派生。',
      related: ['Knowledge OS'],
    });
    expect(wiki['path']).toBe('03_Wiki/Technologies/Spring AI Memory.md');

    // 记录一个长期决策
    const dec = await client.callToolOk('knowledge_create_decision', {
      title: 'Knowledge OS 采用标准 MCP over stdio',
      decision: '能力层实现为标准 MCP Server，stdio 传输。',
      why: '保持 Runtime 无关，任何支持 MCP 的 Agent 都能接入。',
      context: '需要同时服务 Codex / OpenCode / Claude Code / DeepSeek Harness。',
    });
    expect(dec['path']).toBe('03_Wiki/Decisions/决策 - Knowledge OS 采用标准 MCP over stdio.md');

    // 检索：命中刚写入的两页
    const s1 = await client.callToolOk('knowledge_search', { query: 'Spring AI Memory' });
    expect((s1['results'] as Array<Record<string, unknown>>)[0]!['path']).toBe('03_Wiki/Technologies/Spring AI Memory.md');

    const s2 = await client.callToolOk('knowledge_search', { query: 'MCP over stdio', types: ['decision'] });
    const decPaths = (s2['results'] as Array<Record<string, unknown>>).map((x) => String(x['path']));
    expect(decPaths.some((p) => p.includes('Knowledge OS 采用标准 MCP over stdio'))).toBe(true);

    // 读取命中的页面
    const page = await client.callToolOk('knowledge_read', { path: String(wiki['path']) });
    expect(page['type']).toBe('technology');
    expect((page['frontmatter'] as Record<string, unknown>)['type']).toBe('technology');
    expect(page['headings']).toContain('# Spring AI Memory');
    expect(String(page['content'])).toContain('ChatMemory');

    // 搜索不会把 Vault 之外的东西带进来
    const all = await client.callToolOk('knowledge_search', { query: 'Spring', limit: 50 });
    for (const hit of all['results'] as Array<Record<string, unknown>>) {
      expect(String(hit['path'])).not.toContain('..');
      expect(path.isAbsolute(String(hit['path']))).toBe(false);
    }
  });

  it('7. append_log：把这次工作写进历史', async () => {
    const log = await client.callToolOk('knowledge_append_log', {
      type: 'WORK',
      summary: '建立 Knowledge OS 基础设施并跑通跨目录链路。',
      completed: ['MCP Server（10 个工具）', 'canonical Skill', 'Vault 身份校验'],
      verification: ['集成测试通过'],
      decisions: ['[[决策 - Knowledge OS 采用标准 MCP over stdio]]'],
      stateChange: { before: '无 Knowledge OS', after: 'MCP + Skill + Vault 三方绑定完成' },
      nextAction: '接入各 Runtime 并做会话连续性测试。',
    });
    expect(log['path']).toBe('LOG.md');

    const text = readVault(fixture, 'LOG.md');
    expect(text).toContain('建立 Knowledge OS 基础设施并跑通跨目录链路。');
    expect(text).toContain('MCP Server（10 个工具）');
    expect(text).toContain('集成测试通过');
    expect(text).toContain('After 之后：MCP + Skill + Vault 三方绑定完成');
  });

  it('8. health：全部基础设施齐备后变为 healthy', async () => {
    const h = await client.callToolOk('knowledge_health');
    expect(h['status']).toBe('healthy');
    expect(h['canWrite']).toBe(true);
  });

  it('9. 一个文件都没写到 project 目录里（Vault 与代码仓库严格分离）', async () => {
    const walk = (dir: string): string[] => {
      const out: string[] = [];
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) out.push(...walk(p));
        else out.push(p);
      }
      return out;
    };
    const projectFiles = walk(fixture.projectDir).map((p) =>
      path.relative(fixture.projectDir, p).replace(/\\/g, '/'),
    );
    // 项目目录里只应有 .agent 配置，不应有任何 Vault 内容
    for (const f of projectFiles) {
      expect(f.startsWith('.agent/')).toBe(true);
    }
    expect(projectFiles).toContain('.agent/project.yaml');
    expect(projectFiles).toContain('.agent/vault.local.yaml');
  });
});

function existsVaultPath(rel: string): boolean {
  return fs.existsSync(path.join(fixture.vaultDir, rel));
}
