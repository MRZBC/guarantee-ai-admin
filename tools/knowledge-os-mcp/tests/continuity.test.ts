/**
 * continuity.test.ts — 会话连续性与跨 Agent 连续性（规范第 50 / 51 节）。
 *
 * 这两个测试是整套系统**唯一有价值的检验**：
 * 新的 Server 进程 + 新的客户端 + 不给任何历史上下文，能不能接着干？
 *
 * 实现方式刻意做到「真隔离」：
 *   - 每条 Session 都 spawn 一个**全新的 node 进程**（新的 MCP Server 实例，内存全空）
 *   - 客户端不保存任何来自上一会话的数据
 *   - 唯一在 Session 之间传递的东西是磁盘上的 Vault Markdown
 *   → 因此「B 能继续」这件事只可能来自 Vault，不可能来自进程内缓存。
 */

import { afterEach, describe, expect, it } from 'vitest';
import { startMcpClient, type McpTestClient } from './helpers/mcpClient.js';
import { createFixture, readVault, type Fixture } from './helpers/fixture.js';

let fixture: Fixture | null = null;
const clients: McpTestClient[] = [];

/** 开一个全新的「会话」：新的 Server 进程 + 新的客户端。 */
async function openSession(f: Fixture, label: string): Promise<McpTestClient> {
  const c = await startMcpClient({
    serverEntry: f.serverEntry,
    projectRoot: f.projectDir,
    env: { KOS_SESSION_LABEL: label },
  });
  await c.initialize();
  clients.push(c);
  return c;
}

/** 模拟一个 Agent 冷启动的恢复流程：health → resolve → state。 */
async function coldStartRecovery(c: McpTestClient): Promise<{
  canWrite: boolean;
  projectId: string | null;
  nextAction: string;
  handoffNotes: string;
  currentMilestone: string;
  completed: string[];
  stateHash: string | null;
}> {
  const health = await c.callToolOk('knowledge_health');
  const resolved = await c.callToolOk('knowledge_resolve');
  const state = await c.callToolOk('knowledge_state');

  const project = (state['states'] as Array<Record<string, unknown>>).find((s) => s['scope'] === 'project')!;
  const sections = project['sections'] as Record<string, Record<string, unknown>>;

  return {
    canWrite: health['canWrite'] === true,
    projectId: (resolved['projectId'] as string | null) ?? null,
    nextAction: String(sections['nextAction']?.['raw'] ?? ''),
    handoffNotes: String(sections['handoffNotes']?.['raw'] ?? ''),
    currentMilestone: String(sections['currentMilestone']?.['raw'] ?? ''),
    completed: (sections['completed']?.['items'] as string[] | undefined) ?? [],
    stateHash: (project['hash'] as string | null) ?? null,
  };
}

afterEach(async () => {
  for (const c of clients.splice(0)) await c.close();
  if (fixture) fixture.cleanup();
  fixture = null;
});

describe('Session A → Session B 连续性（规范第 50 节）', () => {
  it('B 在无任何历史上下文的情况下，仅凭 Vault 恢复并继续', async () => {
    fixture = createFixture({ projectId: 'guarantee-ai-admin', projectName: '智能电子保函运营管理平台' });

    // ================= Session A =================
    // A 做一个小任务：为项目的某个模块补一个真实的小改动（这里用文件系统模拟代码改动）
    const sessionA = await openSession(fixture, 'A');

    const healthA = await sessionA.callToolOk('knowledge_health');
    expect(healthA['canWrite']).toBe(true);

    await sessionA.callToolOk('knowledge_bootstrap', { dryRun: false });

    await sessionA.callToolOk('knowledge_update_state', {
      scope: 'project-definition',
      currentObjective: '让任何 Agent 在任意会话中都能接手本项目。',
    });

    // A 完成的工作
    const fsA = await import('node:fs');
    const pathA = await import('node:path');
    fsA.mkdirSync(pathA.join(fixture.projectDir, 'guarantee-common'), { recursive: true });
    fsA.writeFileSync(
      pathA.join(fixture.projectDir, 'guarantee-common', 'TraceId.java'),
      'package com.guarantee.common;\n\npublic final class TraceId {\n    private TraceId() {}\n}\n',
      'utf8',
    );

    await sessionA.callToolOk('knowledge_update_state', {
      scope: 'project',
      currentMilestone: '会话连续性验证',
      completed: ['为 guarantee-common 增加 TraceId'],
      inProgress: [],
      nextAction:
        '打开 guarantee-common/TraceId.java，检查是否已有 MDC 集成。如果没有，加上 MDC 支持并跑 guarantee-common 的测试。',
      verification: { verified: ['TraceId.java 已存在'], notVerified: ['MDC 集成'] },
      handoffNotes: '代码改动没有跑过 Maven 构建，下一个 Agent 必须先跑 mvn -pl guarantee-common test。',
      blockers: [],
    });

    await sessionA.callToolOk('knowledge_append_log', {
      type: 'WORK',
      summary: 'Session A：为 guarantee-common 增加 TraceId 工具类。',
      completed: ['新增 TraceId.java'],
      verification: ['文件已写入'],
      stateChange: { before: '无 TraceId', after: 'TraceId 类已存在，MDC 未集成' },
      nextAction: '为 TraceId 增加 MDC 集成并跑测试。',
    });

    // A 结束：关闭会话（进程真的退出）
    await sessionA.close();

    // ================= Session B =================
    // 全新的 Server 进程 + 全新客户端。不给任何历史上下文 —— 相当于用户只说了一句「继续」。
    const sessionB = await openSession(fixture, 'B');

    // B 唯一的信息来源：Knowledge OS 的恢复流程
    const recovery = await coldStartRecovery(sessionB);

    // 1) 恢复了身份
    expect(recovery.canWrite).toBe(true);
    expect(recovery.projectId).toBe('guarantee-ai-admin');

    // 2) 找到了 Next Action，而且是可执行的原子动作（点名了文件与检查点）
    expect(recovery.nextAction).toContain('guarantee-common/TraceId.java');
    expect(recovery.nextAction).toContain('MDC');
    expect(recovery.nextAction.length).toBeGreaterThan(30);

    // 3) 看到了历史工作与交接提示
    expect(recovery.completed).toContain('为 guarantee-common 增加 TraceId');
    expect(recovery.handoffNotes).toContain('mvn -pl guarantee-common test');
    expect(recovery.currentMilestone).toContain('会话连续性验证');

    // 4) 查询相关知识（B 需要知道 TraceId 属于什么）
    const search = await sessionB.callToolOk('knowledge_search', { query: 'TraceId' });
    const logHit = (search['results'] as Array<Record<string, unknown>>).map((x) => String(x['path']));
    expect(logHit).toContain('LOG.md');

    const logPage = await sessionB.callToolOk('knowledge_read', { path: 'LOG.md' });
    expect(String(logPage['content'])).toContain('Session A');

    // 5) 核对「文档状态」与「代码真实状态」——这是规范第 30 节的要求
    const traceIdPath = pathA.join(fixture.projectDir, 'guarantee-common', 'TraceId.java');
    expect(fsA.existsSync(traceIdPath)).toBe(true);
    const code = fsA.readFileSync(traceIdPath, 'utf8');
    expect(code).toContain('class TraceId');
    // 确认 Next Action 的前提成立：MDC 尚未集成
    expect(code).not.toContain('MDC');

    // 6) B 继续工作：完成 Next Action
    fsA.writeFileSync(
      traceIdPath,
      'package com.guarantee.common;\n\nimport org.slf4j.MDC;\n\npublic final class TraceId {\n    private TraceId() {}\n\n    public static void put(String traceId) {\n        MDC.put("traceId", traceId);\n    }\n}\n',
      'utf8',
    );

    // 7) B 用乐观锁更新状态（hash 来自它自己读到的版本）
    const updateB = await sessionB.callToolOk('knowledge_update_state', {
      scope: 'project',
      currentMilestone: '会话连续性验证',
      completed: ['为 guarantee-common 增加 TraceId', '为 TraceId 增加 MDC 集成'],
      inProgress: [],
      nextAction: '运行 mvn -pl guarantee-common test，确认 @Slf4j 与 MDC 的 traceId 能出现在日志里。',
      verification: { verified: ['TraceId.java 已存在', 'MDC.put 已接入'], notVerified: ['guarantee-common 单元测试'] },
      handoffNotes: 'MDC 集成已写完，但测试未跑。下一个 Agent 必须先跑测试再更新验证状态。',
      expectedHash: recovery.stateHash ?? undefined,
    });
    expect(updateB['changedFields']).toContain('completed');

    await sessionB.callToolOk('knowledge_append_log', {
      type: 'WORK',
      summary: 'Session B：完成 Session A 留下的 Next Action（MDC 集成）。',
      completed: ['TraceId 增加 MDC.put'],
      verification: ['read 已确认 MDC 未集成 → 现已集成'],
      stateChange: { before: 'MDC 未集成', after: 'MDC 已集成，测试未跑' },
      nextAction: '跑 guarantee-common 测试。',
    });

    await sessionB.close();

    // ================= Session C =================
    // 再来一个全新会话，验证「链条」没有断
    const sessionC = await openSession(fixture, 'C');
    const recoveryC = await coldStartRecovery(sessionC);

    expect(recoveryC.canWrite).toBe(true);
    expect(recoveryC.completed).toContain('为 TraceId 增加 MDC 集成');
    expect(recoveryC.nextAction).toContain('mvn -pl guarantee-common test');
    expect(recoveryC.handoffNotes).toContain('测试未跑');

    // C 能看到 A 与 B 的完整历史（append-only 的价值）
    const log = readVault(fixture, 'LOG.md');
    expect(log).toContain('Session A');
    expect(log).toContain('Session B');
    expect(log.indexOf('Session A')).toBeLessThan(log.indexOf('Session B'));
  });
});

describe('跨 Agent 连续性（规范第 51 节）', () => {
  it('Agent A 写 → Agent B 读并继续 → Agent C 再继续，共享同一个 Knowledge OS', async () => {
    fixture = createFixture({ projectId: 'shared-project', projectName: '共享项目' });

    // ---------- Agent A（独立进程）----------
    const agentA = await openSession(fixture, 'agent-A');
    await agentA.callToolOk('knowledge_bootstrap', { dryRun: false });
    await agentA.callToolOk('knowledge_update_state', {
      scope: 'project-definition',
      currentObjective: '验证三个不同 Agent 能共享同一份 Vault。',
    });
    const writeA = await agentA.callToolOk('knowledge_update_state', {
      scope: 'project',
      currentMilestone: '阶段 A',
      completed: ['A 完成了数据模型设计'],
      nextAction: 'B 去实现 Repository 层。',
    });
    await agentA.callToolOk('knowledge_append_log', {
      type: 'WORK',
      summary: 'Agent A：完成数据模型设计。',
      nextAction: '实现 Repository 层。',
    });
    await agentA.close();

    // ---------- Agent B ----------
    const agentB = await openSession(fixture, 'agent-B');
    const readB = await agentB.callToolOk('knowledge_state');
    const projB = (readB['states'] as Array<Record<string, unknown>>).find((s) => s['scope'] === 'project')!;
    const sectB = projB['sections'] as Record<string, Record<string, unknown>>;

    // B 读到 A 的成果与 Next Action
    expect(sectB['completed']?.['items']).toContain('A 完成了数据模型设计');
    expect(String(sectB['nextAction']?.['raw'])).toContain('B 去实现 Repository 层');

    // B 继续，并在 A 的基础上追加（不覆盖 A 的记录）
    await agentB.callToolOk('knowledge_update_state', {
      scope: 'project',
      currentMilestone: '阶段 B',
      completed: ['A 完成了数据模型设计', 'B 完成了 Repository 层'],
      nextAction: 'C 去实现 Service 层并补测试。',
      expectedHash: writeA['newHash'] as string,
    });
    await agentB.callToolOk('knowledge_append_log', {
      type: 'WORK',
      summary: 'Agent B：完成 Repository 层。',
      completed: ['Repository 层'],
      nextAction: '实现 Service 层。',
    });
    await agentB.close();

    // ---------- Agent C ----------
    const agentC = await openSession(fixture, 'agent-C');
    const readC = await agentC.callToolOk('knowledge_state');
    const projC = (readC['states'] as Array<Record<string, unknown>>).find((s) => s['scope'] === 'project')!;
    const sectC = projC['sections'] as Record<string, Record<string, unknown>>;

    expect(sectC['completed']?.['items']).toEqual([
      'A 完成了数据模型设计',
      'B 完成了 Repository 层',
    ]);
    expect(String(sectC['nextAction']?.['raw'])).toContain('C 去实现 Service 层');

    await agentC.callToolOk('knowledge_update_state', {
      scope: 'project',
      currentMilestone: '阶段 C',
      completed: ['A 完成了数据模型设计', 'B 完成了 Repository 层', 'C 完成了 Service 层与测试'],
      nextAction: '收尾并归档该阶段。',
    });

    // 最终：三次工作都留在 LOG 里，顺序正确，且阶段推进可见
    const log = (await agentC.callToolOk('knowledge_read', { path: 'LOG.md' }))['content'] as string;
    expect(log).toContain('Agent A：完成数据模型设计。');
    expect(log).toContain('Agent B：完成 Repository 层。');
    expect(log.indexOf('Agent A')).toBeLessThan(log.indexOf('Agent B'));

    // 没有任何一个 Agent 的修改被另一个覆盖
    const finalSect = ((
      ((await agentC.callToolOk('knowledge_state'))['states'] as Array<Record<string, unknown>>).find(
        (s) => s['scope'] === 'project',
      )!['sections']
    ) as Record<string, Record<string, unknown>>);
    expect(String(finalSect['currentMilestone']?.['raw'])).toContain('阶段 C');

    await agentC.close();
  });

  it('并发保护：A 读到 hash 后 B 抢先修改，A 的写入被拒绝而不是覆盖 B', async () => {
    fixture = createFixture({ projectId: 'race-project', projectName: '并发项目' });

    const agentA = await openSession(fixture, 'race-A');
    const agentB = await openSession(fixture, 'race-B');

    await agentA.callToolOk('knowledge_bootstrap', { dryRun: false });
    const initial = await agentA.callToolOk('knowledge_update_state', {
      scope: 'project',
      nextAction: '初始动作。',
    });
    const hashSeenByA = initial['newHash'] as string;

    // B 抢先写入
    await agentB.callToolOk('knowledge_update_state', {
      scope: 'project',
      nextAction: 'B 修改后的动作。',
      expectedHash: hashSeenByA,
    });

    // A 拿着过期的 hash 写入 → 必须 conflict
    const stale = await agentA.callTool('knowledge_update_state', {
      scope: 'project',
      nextAction: 'A 想写的动作。',
      expectedHash: hashSeenByA,
    });
    expect(stale.isError).toBe(true);
    expect(JSON.stringify(stale.content)).toContain('conflict');

    // 磁盘上是 B 的内容，A 的没有被写进去
    const after = readVault(fixture, '04_Work/Active/race-project/STATE.md');
    expect(after).toContain('B 修改后的动作。');
    expect(after).not.toContain('A 想写的动作。');

    await agentA.close();
    await agentB.close();
  });
});
