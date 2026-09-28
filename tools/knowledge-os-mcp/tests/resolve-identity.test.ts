/**
 * resolve-identity.test.ts — 项目解析与三层身份校验（规范第 48 节 / Project Resolution + Identity）。
 *
 * 覆盖：
 *   - 有效配置
 *   - 缺失 project.yaml / vault.local.yaml
 *   - Vault 不存在 / 不可访问
 *   - ID 一致 / ID 不一致 / VAULT_ID.md 缺失
 *   - 身份不通过时读写工具的不同行为（读可用、写必须被拒）
 */

import { afterEach, describe, expect, it } from 'vitest';
import { startMcpClient, type McpTestClient } from './helpers/mcpClient.js';
import { createFixture, existsVault, readVault, type Fixture } from './helpers/fixture.js';

let fixture: Fixture | null = null;
let client: McpTestClient | null = null;

async function connect(f: Fixture): Promise<McpTestClient> {
  const c = await startMcpClient({ serverEntry: f.serverEntry, projectRoot: f.projectDir });
  await c.initialize();
  return c;
}

afterEach(async () => {
  if (client) await client.close();
  if (fixture) fixture.cleanup();
  client = null;
  fixture = null;
});

describe('Project Resolution', () => {
  it('有效配置：resolve 返回完整身份信息', async () => {
    fixture = createFixture();
    client = await connect(fixture);

    const r = await client.callToolOk('knowledge_resolve');
    expect(r['projectId']).toBe('demo-project');
    expect(r['projectName']).toBe('演示项目');
    expect(r['vaultId']).toBe('demo-project');
    expect(String(r['vaultPath']).replace(/\\/g, '/')).toBe(fixture.vaultDir.replace(/\\/g, '/'));
    expect(r['vaultPathSource']).toBe('vault.local.yaml');
    expect((r['identity'] as Record<string, unknown>)['status']).toBe('ok');
    expect((r['identity'] as Record<string, unknown>)['canWrite']).toBe(true);
    expect(r['projectWorkspace']).toBe('04_Work/Active/demo-project');
  });

  it('有效配置：canWrite=true；尚未注册项目工作区时状态为 degraded（非致命）', async () => {
    fixture = createFixture();
    client = await connect(fixture);

    const h = await client.callToolOk('knowledge_health');
    // 身份与配置都 OK，所以写能力可用；但 04_Work/Active/<project>/ 还没有 PROJECT/STATE/TASKS，
    // 因此整体是 degraded 而不是 healthy —— 这是正确的语义（可写，但基础设施待补）。
    expect(h['canWrite']).toBe(true);
    expect(h['identityStatus']).toBe('ok');
    expect(h['vaultAccessible']).toBe(true);
    expect(h['status']).toBe('degraded');
    expect(Array.isArray(h['checks'])).toBe(true);

    const actions = (h['recommendedActions'] as string[]).join('\n');
    expect(actions).toContain('knowledge_bootstrap');
    expect(actions).toContain('knowledge_update_state');
  });

  it('bootstrap 补齐工作区后 health 变为 healthy', async () => {
    fixture = createFixture();
    client = await connect(fixture);

    const boot = await client.callToolOk('knowledge_bootstrap', { dryRun: false });
    expect(boot['applied']).toBe(true);

    // 创建 PROJECT.md 与项目 STATE.md
    const def = await client.callToolOk('knowledge_update_state', {
      scope: 'project-definition',
      currentObjective: '把项目上下文从聊天历史里抽离出来',
    });
    expect(def['created']).toBe(true);

    const st = await client.callToolOk('knowledge_update_state', {
      scope: 'project',
      currentMilestone: 'Knowledge OS 基础设施',
      nextAction: '打开 .agent/project.yaml 检查 project.id 是否稳定。',
    });
    expect(st['created']).toBe(true);

    // TASKS.md 由 bootstrap 之外的流程创建 —— 这里直接建，验证 health 收敛
    const fs = await import('node:fs');
    const path = await import('node:path');
    fs.writeFileSync(
      path.join(fixture.vaultDir, '04_Work/Active/demo-project/TASKS.md'),
      '---\ntype: project-tasks\n---\n\n# 任务\n',
      'utf8',
    );

    const h = await client.callToolOk('knowledge_health');
    expect(h['status']).toBe('healthy');
    expect(h['canWrite']).toBe(true);
  });

  it('缺失 project.yaml：health=unhealthy，且明确报出 project_config', async () => {
    fixture = createFixture({ withProjectConfig: false });
    client = await connect(fixture);

    const h = await client.callToolOk('knowledge_health');
    expect(h['status']).toBe('unhealthy');
    expect(h['canWrite']).toBe(false);
    expect(h['projectId']).toBeNull();

    const checks = h['checks'] as Array<Record<string, unknown>>;
    const projectCheck = checks.find((c) => c['name'] === 'project_config');
    expect(projectCheck?.['ok']).toBe(false);
    expect(String(projectCheck?.['detail'])).toContain('.agent/project.yaml');
    expect((h['recommendedActions'] as string[]).join('\n')).toContain('.agent/project.yaml');
  });

  it('缺失 project.yaml：写工具被拒绝', async () => {
    fixture = createFixture({ withProjectConfig: false });
    client = await connect(fixture);

    const r = await client.callTool('knowledge_update_state', { scope: 'project', nextAction: 'x' });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toMatch(/project_config_missing/);
  });

  it('缺失 vault.local.yaml：不得猜测 Vault，返回明确错误与修复指引', async () => {
    fixture = createFixture({ withVaultConfig: false });
    client = await connect(fixture);

    const h = await client.callToolOk('knowledge_health');
    expect(h['status']).toBe('unhealthy');
    expect(h['vaultPath']).toBeNull();

    const actions = (h['recommendedActions'] as string[]).join('\n');
    expect(actions).toContain('vault.local.yaml');
    // 明确承诺不扫描磁盘
    expect((h['checks'] as Array<Record<string, unknown>>).map((c) => c['detail']).join('\n')).toContain(
      '本机私有文件',
    );
  });

  it('vault.path 指向不存在的目录：health 报错且禁止写入', async () => {
    fixture = createFixture();
    // 事后把配置改成指向不存在的目录
    const fs = await import('node:fs');
    const path = await import('node:path');
    fs.writeFileSync(
      path.join(fixture.projectDir, '.agent', 'vault.local.yaml'),
      `version: 1\n\nvault:\n  id: "demo-project"\n  path: "${path.join(fixture.root, 'nope', 'missing-vault').replace(/\\/g, '/')}"\n`,
      'utf8',
    );
    client = await connect(fixture);

    const h = await client.callToolOk('knowledge_health');
    expect(h['status']).toBe('unhealthy');
    expect(h['canWrite']).toBe(false);
    expect(h['vaultAccessible']).toBe(false);
    expect(h['identityStatus']).toBe('unresolved');

    const checks = h['checks'] as Array<Record<string, unknown>>;
    expect(checks.find((c) => c['name'] === 'vault_exists')?.['ok']).toBe(false);

    const w = await client.callTool('knowledge_update_state', { scope: 'project', nextAction: 'x' });
    expect(w.isError).toBe(true);
  });

  it('vault.path 非绝对路径：配置被判为无效', async () => {
    fixture = createFixture();
    const fs = await import('node:fs');
    const path = await import('node:path');
    fs.writeFileSync(
      path.join(fixture.projectDir, '.agent', 'vault.local.yaml'),
      `version: 1\n\nvault:\n  id: "demo-project"\n  path: "relative/vault"\n`,
      'utf8',
    );
    client = await connect(fixture);

    const r = await client.callTool('knowledge_resolve');
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toContain('vault_config_invalid');
  });

  it('不扫描磁盘：Vault 就在旁边但配置没指向它 → 依然失败', async () => {
    // 造一个「同目录下的另一个 vault」：如果实现偷偷扫描，就会错误地用它
    fixture = createFixture();
    const fs = await import('node:fs');
    const path = await import('node:path');
    const decoy = path.join(fixture.root, 'vault-2-decoy');
    fs.mkdirSync(decoy, { recursive: true });
    fs.writeFileSync(
      path.join(decoy, 'VAULT_ID.md'),
      '---\ntype: vault-identity\nproject_id: "demo-project"\n---\n\n# decoy\n',
      'utf8',
    );

    fs.writeFileSync(
      path.join(fixture.projectDir, '.agent', 'vault.local.yaml'),
      `version: 1\n\nvault:\n  id: "demo-project"\n  path: "${path.join(fixture.root, 'does-not-exist').replace(/\\/g, '/')}"\n`,
      'utf8',
    );
    client = await connect(fixture);

    const h = await client.callToolOk('knowledge_health');
    // 即使旁边有个身份匹配的 Vault，也必须失败 —— 因为配置指向的是不存在的路径
    expect(h['status']).toBe('unhealthy');
    expect(String(h['vaultPath'])).not.toContain('decoy');
  });
});

describe('Identity 三层校验', () => {
  it('三层一致 → ok 且可写', async () => {
    fixture = createFixture();
    client = await connect(fixture);
    const h = await client.callToolOk('knowledge_health');
    expect(h['identityStatus']).toBe('ok');
    expect(h['canWrite']).toBe(true);
  });

  it('vault.id 与 project.id 不一致 → mismatch，读可用写被拒', async () => {
    fixture = createFixture({ vaultConfigId: 'some-other-project' });
    client = await connect(fixture);

    const h = await client.callToolOk('knowledge_health');
    expect(h['identityStatus']).toBe('mismatch');
    expect(h['canWrite']).toBe(false);
    expect((h['errors'] as string[]).join('\n')).toContain('vault.id');

    // 读工具仍然可用（Agent 需要能诊断），但被标注为只读
    const s = await client.callToolOk('knowledge_state');
    expect(s['readOnly']).toBe(true);
    expect(s['identityStatus']).toBe('mismatch');

    // 所有写工具必须被拒绝
    for (const [tool, args] of [
      ['knowledge_update_state', { scope: 'project', nextAction: 'x' }],
      ['knowledge_append_log', { type: 'WORK', summary: 'x' }],
      ['knowledge_create_decision', { title: 't', decision: 'd', why: 'w' }],
      ['knowledge_upsert_wiki', { type: 'concept', title: 't', content: 'c' }],
      ['knowledge_bootstrap', { dryRun: false }],
    ] as Array<[string, Record<string, unknown>]>) {
      const r = await client.callTool(tool, args);
      expect(r.isError, `${tool} 在身份不一致时必须拒绝写入`).toBe(true);
      expect(JSON.stringify(r.content)).toContain('identity_mismatch');
    }
  });

  it('VAULT_ID.md 的 project_id 与 project.id 不一致 → mismatch', async () => {
    fixture = createFixture({ vaultIdFileProjectId: 'legacy-other-name' });
    client = await connect(fixture);

    const h = await client.callToolOk('knowledge_health');
    expect(h['identityStatus']).toBe('mismatch');
    expect(h['canWrite']).toBe(false);
    expect((h['errors'] as string[]).join('\n')).toContain('legacy-other-name');
  });

  it('VAULT_ID.md 缺失 → missing，禁止写入，但允许初始化', async () => {
    fixture = createFixture({ withVaultIdFile: false });
    client = await connect(fixture);

    const h = await client.callToolOk('knowledge_health');
    expect(h['identityStatus']).toBe('missing');
    expect(h['canWrite']).toBe(false);

    const w = await client.callTool('knowledge_update_state', { scope: 'project', nextAction: 'x' });
    expect(w.isError).toBe(true);
    expect(JSON.stringify(w.content)).toContain('identity_mismatch');
  });

  it('VAULT_ID.md 缺失 → bootstrap 只创建身份文件（打破初始化死锁），随后写操作解锁', async () => {
    fixture = createFixture({ withVaultIdFile: false });
    client = await connect(fixture);

    // 第一次：只在 initialize-identity 模式下落一个文件
    const first = await client.callToolOk('knowledge_bootstrap', { dryRun: false });
    expect(first['mode']).toBe('initialize-identity');
    const createdPaths = (first['created'] as Array<Record<string, unknown>>).map((c) => String(c['path']));
    expect(createdPaths).toEqual(['VAULT_ID.md']);

    // 不得顺手创建任何其它基础设施
    const fs = await import('node:fs');
    const pathMod = await import('node:path');
    expect(fs.existsSync(pathMod.join(fixture.vaultDir, '04_Work/Active/demo-project'))).toBe(false);

    // 身份文件内容正确
    const idFile = readVault(fixture, 'VAULT_ID.md');
    expect(idFile).toContain('type: vault-identity');
    expect(idFile).toContain('project_id: "demo-project"');

    // 身份就位 → 写操作解锁
    const h = await client.callToolOk('knowledge_health');
    expect(h['identityStatus']).toBe('ok');
    expect(h['canWrite']).toBe(true);

    // 第二次：普通模式，补齐工作区
    const second = await client.callToolOk('knowledge_bootstrap', { dryRun: false });
    expect(second['mode']).toBe('normal');
    expect(existsVault(fixture, '04_Work/Active/demo-project/TASKS.md')).toBe(true);
  });

  it('VAULT_ID.md 缺失且配置两层 id 不一致 → 连身份文件都不许建', async () => {
    fixture = createFixture({ withVaultIdFile: false, vaultConfigId: 'someone-else' });
    client = await connect(fixture);

    const r = await client.callTool('knowledge_bootstrap', { dryRun: false });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toContain('identity_mismatch');
    expect(existsVault(fixture, 'VAULT_ID.md')).toBe(false);
  });

  it('VAULT_ID.md 格式非法（缺少 type） → mismatch，不静默接受', async () => {
    fixture = createFixture({ withVaultIdFile: false });
    const fs = await import('node:fs');
    const path = await import('node:path');
    fs.writeFileSync(
      path.join(fixture.vaultDir, 'VAULT_ID.md'),
      `---\nproject_id: "demo-project"\n---\n\n# 没有 type 字段\n`,
      'utf8',
    );
    client = await connect(fixture);

    const h = await client.callToolOk('knowledge_health');
    expect(h['identityStatus']).toBe('mismatch');
    expect(h['canWrite']).toBe(false);
    expect((h['errors'] as string[]).join('\n')).toContain('type: vault-identity');
  });

  it('project.id 含不安全字符 → 配置被判无效（它会被用作目录名）', async () => {
    fixture = createFixture({ projectId: 'bad/../id' });
    client = await connect(fixture);

    const r = await client.callTool('knowledge_resolve');
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toContain('project_config_invalid');
  });
});
