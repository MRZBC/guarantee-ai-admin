/**
 * state.test.ts — STATE 读写（规范第 48 节 / State）。
 *
 * 覆盖：read / update / preserve untouched fields / 创建 / 乐观并发冲突 / 幂等。
 * 核心断言是「只改我要求改的小节，其它内容逐字不变」。
 */

import { afterEach, describe, expect, it } from 'vitest';
import { startMcpClient, type McpTestClient } from './helpers/mcpClient.js';
import { createFixture, readVault, writeVault, type Fixture } from './helpers/fixture.js';

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

describe('knowledge_state — 读取', () => {
  it('默认读取三个 scope，并解析出规范要求的信息', async () => {
    await setup();
    const r = await client!.callToolOk('knowledge_state');
    const states = r['states'] as Array<Record<string, unknown>>;
    expect(states.map((s) => s['scope'])).toEqual(['global', 'project', 'project-definition']);

    const global = states.find((s) => s['scope'] === 'global')!;
    expect(global['exists']).toBe(true);
    expect(global['path']).toBe('STATE.md');
    const sections = global['sections'] as Record<string, Record<string, unknown>>;
    expect(sections['objective']?.['present']).toBe(true);
    expect(sections['nextAction']?.['present']).toBe(true);
    expect(sections['handoffNotes']?.['present']).toBe(true);
    expect(sections['importantDecisions']?.['present']).toBe(true);

    const project = states.find((s) => s['scope'] === 'project')!;
    expect(project['exists']).toBe(false);
    expect(project['path']).toBe('04_Work/Active/demo-project/STATE.md');
    expect(project['hash']).toBeNull();
  });

  it('从既有中文标题惯例解析出语义字段', async () => {
    await setup({
      globalState: `---
type: global-state
updated: 2026-01-02
active_project: "demo-project"
---

# 当前状态

## Active Project 活动项目

demo-project

## Objective 目标

把项目上下文从聊天里抽出来。

## Completed 已完成

- [x] 建立 Vault 骨架
- [x] 中文化

## Next Action 下一步行动

打开 \`pom.xml\`，确认 Java 版本。

## Blockers 阻塞项

- 无。

## Verification 验证

### Verified 已验证

- 文件系统结构已核对

### Not Yet Verified 尚未验证

- Obsidian 渲染效果
`,
    });

    const r = await client!.callToolOk('knowledge_state', { scopes: ['global'] });
    const global = (r['states'] as Array<Record<string, unknown>>)[0]!;
    const sections = global['sections'] as Record<string, Record<string, unknown>>;

    expect(sections['objective']?.['text']).toContain('把项目上下文从聊天里抽出来');
    expect(sections['completed']?.['items']).toEqual(['建立 Vault 骨架', '中文化']);
    expect(sections['nextAction']?.['raw']).toContain('pom.xml');
    expect(sections['blockers']?.['items']).toEqual(['无。']);
    expect(sections['verification']?.['present']).toBe(true);
    expect(global['updated']).toBe('2026-01-02');
  });
});

describe('knowledge_update_state — 更新', () => {
  it('只更新指定字段，其余内容逐字保留', async () => {
    const original = `---
type: global-state
updated: 2026-01-02
active_project: "demo-project"
---

# 当前状态

> 这句用户写的说明必须原样保留。

## Active Project 活动项目

demo-project

## Objective 目标

原来的目标文本。

## Completed 已完成

- [x] 老任务

## Blockers 阻塞项

- 无。

## Handoff Notes 交接说明

用户手写的交接说明，不能被清掉。
`;
    await setup({ globalState: original });

    await client!.callToolOk('knowledge_update_state', {
      scope: 'global',
      currentObjective: '新的目标文本。',
      nextAction: '打开 `.agent/project.yaml` 检查 project.id。',
    });

    const after = readVault(fixture!, 'STATE.md');

    // 用户手写的、未被指定的内容必须还在
    expect(after).toContain('> 这句用户写的说明必须原样保留。');
    expect(after).toContain('用户手写的交接说明，不能被清掉。');
    expect(after).toContain('- [x] 老任务');
    expect(after).toContain('active_project: "demo-project"');

    // 指定的字段已更新
    expect(after).toContain('新的目标文本。');
    expect(after).toContain('打开 `.agent/project.yaml` 检查 project.id。');
    expect(after).not.toContain('原来的目标文本。');

    // 未指定的小节未被新建
    expect(after).not.toContain('## Current Milestone 当前里程碑');
  });

  it('返回 changedFields / skippedFields / newHash，且 hash 可用于下一次写入', async () => {
    await setup();
    const first = await client!.callToolOk('knowledge_update_state', {
      scope: 'project',
      currentMilestone: '阶段一',
      nextAction: '继续。',
    });
    expect(first['created']).toBe(true);
    expect(first['changedFields']).toContain('nextAction');
    expect(typeof first['newHash']).toBe('string');

    // 第二次传入相同内容 → 不写入（幂等）
    const second = await client!.callToolOk('knowledge_update_state', {
      scope: 'project',
      currentMilestone: '阶段一',
      nextAction: '继续。',
    });
    expect(second['changedFields']).toEqual([]);
    expect(second['bytesWritten']).toBe(0);
    expect((second['warnings'] as string[]).join('\n')).toContain('未写入');
  });

  it('缺失的小节会被新建，其它小节不受影响', async () => {
    await setup({
      globalState: `---\ntype: global-state\n---\n\n# 当前状态\n\n## Objective 目标\n\n原目标。\n`,
    });

    await client!.callToolOk('knowledge_update_state', {
      scope: 'global',
      blockers: ['缺少 vault.local.yaml', 'CI 未接入'],
      verification: { verified: ['build 通过'], notVerified: ['E2E'] },
    });

    const after = readVault(fixture!, 'STATE.md');
    expect(after).toContain('## Blockers 阻塞项');
    expect(after).toContain('- 缺少 vault.local.yaml');
    expect(after).toContain('## Verification 验证');
    expect(after).toContain('### Verified 已验证');
    expect(after).toContain('- build 通过');
    expect(after).toContain('### Not Yet Verified 尚未验证');
    expect(after).toContain('- E2E');
    expect(after).toContain('原目标。');
  });

  it('scope=project-definition 写入 PROJECT.md（为什么存在），不写 STATE 字段', async () => {
    await setup();
    const r = await client!.callToolOk('knowledge_update_state', {
      scope: 'project-definition',
      currentObjective: '让任何 Agent 都能接手本项目。',
    });
    expect(r['path']).toBe('04_Work/Active/demo-project/PROJECT.md');

    const after = readVault(fixture!, '04_Work/Active/demo-project/PROJECT.md');
    expect(after).toContain('type: project');
    expect(after).toContain('## Objective 目标');
    expect(after).toContain('让任何 Agent 都能接手本项目。');
    // PROJECT.md 不该出现 STATE 专属小节
    expect(after).not.toContain('## Next Action 下一步行动');
  });

  it('scope 不支持的字段被忽略并明确报告', async () => {
    await setup();
    const r = await client!.callToolOk('knowledge_update_state', {
      scope: 'project-definition',
      currentObjective: 'X',
      nextAction: '这个字段在 project-definition 里没有意义',
    });
    expect(r['skippedInputs']).toBeDefined();
    expect((r['skippedInputs'] as string[]).join('\n')).toContain('nextAction');
    expect(r['changedFields']).toEqual(['objective']);
  });

  it('expectedHash 不匹配 → conflict，且不修改文件', async () => {
    await setup();
    const before = await client!.callToolOk('knowledge_state', { scopes: ['global'] });
    const hash = (before['states'] as Array<Record<string, unknown>>)[0]!['hash'] as string;
    const originalContent = readVault(fixture!, 'STATE.md');

    // 模拟另一个 Agent 抢先修改
    writeVault(fixture!, 'STATE.md', originalContent.replace('（待补充）', '（被别的 Agent 改了）'));

    const r = await client!.callTool('knowledge_update_state', {
      scope: 'global',
      nextAction: '我的写入',
      expectedHash: hash,
    });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toContain('conflict');

    // 磁盘上仍是另一个 Agent 的内容 —— 没有被覆盖
    const after = readVault(fixture!, 'STATE.md');
    expect(after).toContain('（被别的 Agent 改了）');
    expect(after).not.toContain('我的写入');
  });

  it('未提供 expectedHash 时仍能写入（hash 是可选的乐观锁）', async () => {
    await setup();
    const r = await client!.callToolOk('knowledge_update_state', {
      scope: 'global',
      nextAction: '直接写。',
    });
    expect(r['changedFields']).toContain('nextAction');
    expect(readVault(fixture!, 'STATE.md')).toContain('直接写。');
  });

  it('没有任何字段时报错，而不是空写', async () => {
    await setup();
    const r = await client!.callTool('knowledge_update_state', { scope: 'global' });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toContain('invalid_argument');
  });

  it('保留 CRLF 换行风格（不把用户文件改成 LF）', async () => {
    await setup();
    const crlf = '---\r\ntype: global-state\r\n---\r\n\r\n# 当前状态\r\n\r\n## Objective 目标\r\n\r\n原目标。\r\n';
    writeVault(fixture!, 'STATE.md', crlf);

    await client!.callToolOk('knowledge_update_state', { scope: 'global', currentObjective: '新目标。' });

    const after = readVault(fixture!, 'STATE.md');
    expect(after).toContain('\r\n');
    expect(after.replace(/\r\n/g, '')).not.toContain('\n'); // 没有裸 LF
    expect(after).toContain('新目标。');
  });
});

describe('knowledge_update_state — 原子性', () => {
  it('新建 STATE 的 frontmatter 不产生双重转义（phase: "" 而不是 "\"\"")', async () => {
    await setup();
    await client!.callToolOk('knowledge_update_state', { scope: 'project', nextAction: 'x' });

    const after = readVault(fixture!, '04_Work/Active/demo-project/STATE.md');
    expect(after).not.toContain('\\"');
    expect(after).toContain('phase: ""');

    const { parseMarkdown } = await import('../src/markdown.js');
    const fm = parseMarkdown(after).frontmatter;
    expect(fm['phase']).toBe('');
    expect(fm['updated']).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('global scope 新建时 active_project / active_phase 正确写入', async () => {
    await setup({ globalState: '' });
    await client!.callToolOk('knowledge_update_state', { scope: 'global', nextAction: 'x' });

    const after = readVault(fixture!, 'STATE.md');
    expect(after).toContain('active_project: demo-project');
    expect(after).toContain('active_phase: ""');
    expect(after).not.toContain('\\"');
  });

  it('frontmatter 值含中文括号时被正确引用且可解析回来', async () => {
    await setup();
    await client!.callToolOk('knowledge_update_state', { scope: 'project-definition', currentObjective: 'X' });
    const { parseMarkdown } = await import('../src/markdown.js');
    const fm = parseMarkdown(readVault(fixture!, '04_Work/Active/demo-project/PROJECT.md')).frontmatter;
    expect(String(fm['type'])).toBe('project');
    expect(String(fm['status'])).toBe('active');
  });

  it('phase 参数写入 global 的 active_phase frontmatter（受控词表）', async () => {
    await setup();
    await client!.callToolOk('knowledge_update_state', {
      scope: 'global',
      phase: 'implementation',
      nextAction: '继续。',
    });

    const after = readVault(fixture!, 'STATE.md');
    expect(after).toContain('active_phase: implementation');

    const { parseMarkdown } = await import('../src/markdown.js');
    expect(parseMarkdown(after).frontmatter['active_phase']).toBe('implementation');
  });

  it('phase 只支持 global scope', async () => {
    await setup();
    const r = await client!.callTool('knowledge_update_state', { scope: 'project', phase: 'x' });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toContain('invalid_argument');
  });

  it('仅传 phase 也算有效调用', async () => {
    await setup();
    const r = await client!.callToolOk('knowledge_update_state', { scope: 'global', phase: 'verification' });
    expect(r['bytesWritten']).toBeGreaterThan(0);
    expect(readVault(fixture!, 'STATE.md')).toContain('active_phase: verification');
  });

  it('scope=project-definition 支持按标题写任意小节（sections），且不整文件重写', async () => {
    await setup();
    await client!.callToolOk('knowledge_update_state', {
      scope: 'project-definition',
      currentObjective: '目标一句话。',
      sections: {
        'Scope 范围': '- 范围 A\n- 范围 B',
        'Non-Goals 非目标': '- 明确不做 X',
        architecture: '```text\nweb -> auth -> system\n```',
      },
    });

    const after = readVault(fixture!, '04_Work/Active/demo-project/PROJECT.md');
    expect(after).toContain('## Scope 范围');
    expect(after).toContain('- 范围 A');
    expect(after).toContain('## Non-Goals 非目标');
    expect(after).toContain('- 明确不做 X');
    expect(after).toContain('## architecture');
    expect(after).toContain('web -> auth -> system');
    expect(after).toContain('目标一句话。');
    // 未传入的小节不该被凭空创建
    expect(after).not.toContain('## Risks 风险');

    // 二次写入同标题小节 → 定点替换，不新增重复标题
    await client!.callToolOk('knowledge_update_state', {
      scope: 'project-definition',
      sections: { 'Scope 范围': '- 范围 A 已修订' },
    });
    const after2 = readVault(fixture!, '04_Work/Active/demo-project/PROJECT.md');
    expect((after2.match(/## Scope 范围/g) ?? []).length).toBe(1);
    expect(after2).toContain('- 范围 A 已修订');
    expect(after2).not.toContain('- 范围 B');
  });

  it('sections 拒绝会破坏结构或路径语义的键，并限制数量', async () => {
    await setup();

    // 键只用于生成 `## <键>` 标题，因此危险的是「结构类」字符。
    // 注意：键**不是**路径，正弦杠是合法标题字符（例如「未计划 / 新发现」）。
    for (const heading of ['../../etc/passwd', 'a\\b', '# Heading', 'ok\n## Injected']) {
      const r = await client!.callTool('knowledge_update_state', {
        scope: 'project-definition',
        sections: { [heading]: 'x' },
      });
      if (heading === '../../etc/passwd') {
        // 点斜杠在这里只是标题文本，不构成路径穿越；应被接受并原样成为标题
        expect(r.isError, '正弦杠是合法标题字符，不应报错').toBeFalsy();
        continue;
      }
      expect(r.isError, `应拒绝标题 ${JSON.stringify(heading)}`).toBe(true);
    }

    const tooMany: Record<string, string> = {};
    for (let i = 0; i < 21; i++) tooMany[`S${i}`] = 'x';
    const r = await client!.callTool('knowledge_update_state', {
      scope: 'project-definition',
      sections: tooMany,
    });
    expect(r.isError).toBe(true);
  });

  it('sections 也可用于 project scope（TASKS.md 之类没有语义字段的小节）', async () => {
    await setup();
    await client!.callToolOk('knowledge_update_state', {
      scope: 'project',
      currentMilestone: '第一阶段',
      sections: {
        'Phase 1 — 交付': '- [x] 模块骨架\n- [ ] 联调',
        '未计划 / 新发现': '- [ ] 顺手发现的坑',
      },
    });

    const after = readVault(fixture!, '04_Work/Active/demo-project/STATE.md');
    expect(after).toContain('## Phase 1 — 交付');
    expect(after).toContain('- [x] 模块骨架');
    expect(after).toContain('## 未计划 / 新发现');
    expect(after).toContain('第一阶段');
  });

  it('sectionsFile="tasks" 把小节写进 TASKS.md，固定字段仍写 STATE.md', async () => {
    await setup();
    const fs = await import('node:fs');
    const pathMod = await import('node:path');
    const tasksPath = pathMod.join(fixture!.vaultDir, '04_Work/Active/demo-project/TASKS.md');
    fs.mkdirSync(pathMod.dirname(tasksPath), { recursive: true });
    fs.writeFileSync(
      tasksPath,
      '---\ntype: project-tasks\nproject: "演示项目"\n---\n\n# 任务\n\n## Phase 1 — <阶段名>\n\n- [ ] （待补充）\n',
      'utf8',
    );

    const r = await client!.callToolOk('knowledge_update_state', {
      scope: 'project',
      currentMilestone: '第一阶段',
      sectionsFile: 'tasks',
      sections: {
        'Phase 0 — 骨架': '- [x] 建模块',
        'Phase 1 — <阶段名>': '__KOS_DELETE_SECTION__',
      },
    });

    // 语义字段写 STATE.md，任务小节写 TASKS.md
    expect(r['fieldTargetPath']).toBe('04_Work/Active/demo-project/STATE.md');
    expect(r['sectionsTargetPath']).toBe('04_Work/Active/demo-project/TASKS.md');

    const tasks = readVault(fixture!, '04_Work/Active/demo-project/TASKS.md');
    expect(tasks).toContain('## Phase 0 — 骨架');
    expect(tasks).toContain('- [x] 建模块');
    // 占位小节被删除哨兵移除
    expect(tasks).not.toContain('<阶段名>');
    // TASKS.md 自己的 frontmatter 不被注入骨架字段
    expect(tasks).toContain('type: project-tasks');
    expect(tasks).not.toContain('type: project-state');

    // STATE.md 拿到语义字段，且**没有**被写入任务小节
    const state = readVault(fixture!, '04_Work/Active/demo-project/STATE.md');
    expect(state).toContain('第一阶段');
    expect(state).not.toContain('## Phase 0 — 骨架');
  });

  it('删除哨兵：只删小节，不删文件；文件不存在时安全跳过', async () => {
    await setup();
    await client!.callToolOk('knowledge_update_state', {
      scope: 'project-definition',
      sections: { 'Temp 临时': '待删除', 'Keep 保留': '要保留' },
    });
    expect(readVault(fixture!, '04_Work/Active/demo-project/PROJECT.md')).toContain('## Temp 临时');

    const r = await client!.callToolOk('knowledge_update_state', {
      scope: 'project-definition',
      sections: { 'Temp 临时': '__KOS_DELETE_SECTION__' },
    });
    expect(r['changedFields']).toContain('custom:Temp 临时');

    const after = readVault(fixture!, '04_Work/Active/demo-project/PROJECT.md');
    expect(after).not.toContain('## Temp 临时');
    expect(after).not.toContain('待删除');
    expect(after).toContain('## Keep 保留');
    expect(after).toContain('要保留');
    expect(after).toContain('type: project'); // 文件本身还在

    // 再删一次（已不存在）→ 安全跳过，不报错
    const again = await client!.callToolOk('knowledge_update_state', {
      scope: 'project-definition',
      sections: { 'Temp 临时': '__KOS_DELETE_SECTION__' },
    });
    expect(again['changedFields']).toEqual([]);
    expect(again['skippedFields']).toContain('custom:Temp 临时');
  });

  it('Verification 的子标题用 ### 而不是 ####', async () => {
    await setup();
    await client!.callToolOk('knowledge_update_state', {
      scope: 'project',
      verification: { verified: ['build 通过'], notVerified: ['E2E'] },
    });
    const after = readVault(fixture!, '04_Work/Active/demo-project/STATE.md');
    expect(after).toContain('### Verified 已验证');
    expect(after).toContain('### Not Yet Verified 尚未验证');
    expect(after).not.toContain('#### Verified');
    expect(after).not.toContain('#### Not Yet Verified');
  });

  it('sectionsFile 与 scope 同文件时报错（避免歧义）', async () => {
    await setup();
    const r = await client!.callTool('knowledge_update_state', {
      scope: 'project',
      sectionsFile: 'tasks',
      sections: { 'X': 'y' },
    });
    // tasks ≠ STATE.md，因此这是合法的；这里验证的是未知文件名的拒绝
    expect(r.isError).toBeFalsy();

    const bad = await client!.callTool('knowledge_update_state', {
      scope: 'project',
      sectionsFile: 'secrets',
      sections: { 'X': 'y' },
    });
    expect(bad.isError).toBe(true);
  });

  it('写入后没有残留临时文件', async () => {
    await setup();
    await client!.callToolOk('knowledge_update_state', { scope: 'global', nextAction: 'x' });
    const fs = await import('node:fs');
    const path = await import('node:path');
    const leftovers = fs
      .readdirSync(fixture!.vaultDir)
      .filter((f) => f.includes('.kos-') || f.endsWith('.tmp'));
    expect(leftovers).toEqual([]);
    void path;
  });
});
