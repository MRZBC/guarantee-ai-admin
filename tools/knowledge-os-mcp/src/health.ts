/**
 * health.ts — HEALTH（只读诊断）。
 *
 * 这是 Agent 进入项目后应该调用的第一个工具：先知道「能不能用、能不能写」，
 * 再决定做什么。它**绝不修改任何文件**。
 */

import { loadConfig, displayPath, type KnowledgeConfig } from './config.js';
import { fileExists, isDirectory } from './fsx.js';
import { checkIdentity, type IdentityCheck } from './identity.js';
import { WIKI_TYPE_DIRS } from './layout.js';
import { VaultPathGuard } from './pathguard.js';
import { DEFAULT_LAYOUT } from './layout.js';

export type HealthStatus = 'healthy' | 'degraded' | 'unhealthy';

/** 某个 Vault 内相对路径是否存在（路径非法时视为不存在，不抛错）。 */
async function existsRelative(guard: VaultPathGuard, rel: string): Promise<boolean> {
  try {
    const { absolutePath } = guard.resolve(rel);
    return await fileExists(absolutePath);
  } catch {
    return false;
  }
}

export interface HealthCheck {
  readonly name: string;
  readonly ok: boolean;
  /** 是否致命：致命项不通过 → 不允许写操作 */
  readonly critical: boolean;
  readonly detail: string;
}

export interface HealthReport {
  readonly status: HealthStatus;
  readonly projectRoot: string;
  readonly projectConfigPath: string | null;
  readonly vaultConfigPath: string | null;
  readonly projectId: string | null;
  readonly projectName: string | null;
  readonly vaultId: string | null;
  readonly vaultPath: string | null;
  readonly vaultAccessible: boolean;
  readonly identityStatus: IdentityCheck['status'] | 'unknown';
  readonly canWrite: boolean;
  readonly checks: readonly HealthCheck[];
  readonly warnings: readonly string[];
  readonly errors: readonly string[];
  /** 给 Agent 的下一步建议（可执行、具体） */
  readonly recommendedActions: readonly string[];
  readonly layout: Record<string, string>;
}

export async function healthCheck(options: { projectRoot?: string; vaultConfigPath?: string; ignoreEnv?: boolean } = {}): Promise<HealthReport> {  const checks: HealthCheck[] = [];
  const warnings: string[] = [];
  const errors: string[] = [];
  const recommendedActions: string[] = [];

  let config: KnowledgeConfig | null = null;
  try {
    config = loadConfig(options);
  } catch (err) {
    errors.push((err as Error).message);
    checks.push({
      name: 'project_config',
      ok: false,
      critical: true,
      detail: (err as Error).message,
    });
    recommendedActions.push(
      '创建 .agent/project.yaml（参考 tools/knowledge-os-mcp/README.md 的「项目身份」一节），然后重新调用 knowledge_health。',
    );
    return {
      status: 'unhealthy',
      projectRoot: options.projectRoot ?? '',
      projectConfigPath: null,
      vaultConfigPath: null,
      projectId: null,
      projectName: null,
      vaultId: null,
      vaultPath: null,
      vaultAccessible: false,
      identityStatus: 'unknown',
      canWrite: false,
      checks,
      warnings,
      errors,
      recommendedActions,
      layout: { ...DEFAULT_LAYOUT },
    };
  }

  // --- 1. 项目配置 -----------------------------------------------------
  checks.push({
    name: 'project_config',
    ok: config.project !== null,
    critical: true,
    detail: config.project
      ? `project.id = "${config.project.id}"，project.name = "${config.project.name}"（${displayPath(config.projectConfigPath ?? '')}）`
      : '缺少 .agent/project.yaml',
  });
  if (!config.project) {
    recommendedActions.push('创建 .agent/project.yaml，写入稳定的 project.id 与 project.name。');
  }

  // --- 2. Vault 配置 ---------------------------------------------------
  checks.push({
    name: 'vault_config',
    ok: config.vault !== null,
    critical: true,
    detail: config.vault
      ? `vault.id = "${config.vault.id}"，vault.path = "${displayPath(config.vault.path)}"（来源：${config.vault.source}）`
      : '缺少 .agent/vault.local.yaml（本机私有文件，故意不入库）',
  });
  if (!config.vault) {
    recommendedActions.push(
      '复制 .agent/vault.local.yaml.example 为 .agent/vault.local.yaml，并把 vault.path 改成本机真实 Vault 绝对路径。' +
        '本 Server 不会扫描磁盘去找 Vault。',
    );
  }

  const identity: IdentityCheck = await checkIdentity(config);
  warnings.push(...config.warnings, ...identity.notes);
  errors.push(...identity.mismatches);

  // --- 3. Vault 目录存在 / 可访问 --------------------------------------
  const vaultPath = config.vault?.path ?? null;
  let vaultExists = false;
  let vaultReadable = false;
  let vaultWritable = false;

  if (vaultPath) {
    vaultExists = await fileExists(vaultPath);
    checks.push({
      name: 'vault_exists',
      ok: vaultExists,
      critical: true,
      detail: vaultExists ? `Vault 目录存在：${displayPath(vaultPath)}` : `Vault 目录不存在：${displayPath(vaultPath)}`,
    });
    if (!vaultExists) {
      recommendedActions.push(
        `确认 vault.path 是否正确（当前：${displayPath(vaultPath)}）；如果 Vault 尚未创建，先创建该目录与 VAULT_ID.md。`,
      );
    }

    if (vaultExists) {
      // 真实可读性：尝试列目录。
      // 注意：不要用 guard.resolve('.') —— 路径守卫刻意拒绝把 Vault 根当作目标路径
      // （所有工具都必须在 Vault **内部**操作）。这里直接对根目录做 IO 探测。
      try {
        const { readdir } = await import('node:fs/promises');
        await readdir(vaultPath);
        vaultReadable = true;
      } catch {
        vaultReadable = false;
      }
      checks.push({
        name: 'vault_readable',
        ok: vaultReadable,
        critical: true,
        detail: vaultReadable ? 'Vault 目录可读' : 'Vault 目录不可读（权限问题？）',
      });

      try {
        const { access, constants } = await import('node:fs/promises');
        await access(vaultPath, constants.W_OK);
        vaultWritable = true;
      } catch {
        vaultWritable = false;
      }
      checks.push({
        name: 'vault_writable',
        ok: vaultWritable,
        critical: false,
        detail: vaultWritable ? 'Vault 目录可写' : 'Vault 目录不可写（当前用户无写权限）',
      });
      if (!vaultWritable) {
        warnings.push('Vault 目录不可写：所有写工具都会失败，但读工具仍可用。');
      }
    }
  } else {
    checks.push({
      name: 'vault_exists',
      ok: false,
      critical: true,
      detail: '未配置 vault.path，无法检查',
    });
  }

  // --- 4. 三层身份校验 -------------------------------------------------
  checks.push({
    name: 'identity_match',
    ok: identity.status === 'ok',
    critical: true,
    detail:
      identity.status === 'ok'
        ? `三层一致：project.id == vault.id == VAULT_ID.md.project_id == "${identity.projectId}"`
        : `身份校验未通过（status=${identity.status}）：${identity.mismatches.join('；') || identity.notes.join('；')}`,
  });
  if (identity.status === 'missing') {
    recommendedActions.push(
      `在 Vault 根目录创建 ${identity.identityFile}（frontmatter 含 type: vault-identity 与 project_id: "${identity.projectId}"），` +
        '之后写操作才会被允许。',
    );
  } else if (identity.status === 'mismatch') {
    recommendedActions.push('修正 .agent/project.yaml / .agent/vault.local.yaml / VAULT_ID.md 三者中不一致的 id。禁止在修正前写入。');
  }

  // --- 5. 关键文件 -----------------------------------------------------
  if (vaultPath && vaultExists) {
    const guard = new VaultPathGuard({ vaultRoot: vaultPath });
    const fileChecks: Array<{ name: string; rel: string; label: string; critical: boolean }> = [
      { name: 'vault_identity_file', rel: config.layout.vaultId, label: 'Vault 身份文件', critical: true },
      { name: 'global_state', rel: config.layout.state, label: '全局 STATE.md', critical: false },
      { name: 'agent_protocol', rel: config.layout.agents, label: 'AGENTS.md 协议', critical: false },
      { name: 'index', rel: config.layout.index, label: 'INDEX.md 导航', critical: false },
      { name: 'log', rel: config.layout.log, label: 'LOG.md 历史', critical: false },
    ];

    for (const fc of fileChecks) {
      let ok = false;
      try {
        const { absolutePath } = guard.resolve(fc.rel);
        ok = await fileExists(absolutePath);
      } catch {
        ok = false;
      }
      checks.push({
        name: fc.name,
        ok,
        critical: fc.critical,
        detail: ok ? `${fc.label}存在：${fc.rel}` : `${fc.label}缺失：${fc.rel}`,
      });
      if (!ok && fc.name === 'log') {
        recommendedActions.push(`${config.layout.log} 缺失：knowledge_append_log 会自动创建它。`);
      }
      if (!ok && fc.name === 'global_state') {
        recommendedActions.push(
          `${config.layout.state} 缺失：knowledge_update_state(scope="global") 会自动创建它（不会覆盖任何既有文件）。`,
        );
      }
    }

    // --- 6. 必要目录 ---------------------------------------------------
    const dirRels = [
      config.layout.wiki,
      config.layout.decisions,
      config.layout.active,
      `${config.layout.active}/${identity.projectId ?? ''}`,
    ];
    for (const rel of dirRels) {
      if (!rel || rel.endsWith('/')) continue;
      let ok = false;
      try {
        const { absolutePath } = guard.resolve(rel);
        ok = await isDirectory(absolutePath);
      } catch {
        ok = false;
      }
      checks.push({
        name: `dir:${rel}`,
        ok,
        critical: false,
        detail: ok ? `目录存在：${rel}` : `目录缺失：${rel}`,
      });
      if (!ok) {
        recommendedActions.push(`目录缺失：${rel}（可用 knowledge_bootstrap 自动补齐，它只创建缺失项）。`);
      }
    }

    // --- 7. 项目工作区 -------------------------------------------------
    if (identity.projectId) {
      const workspace = `${config.layout.active}/${identity.projectId}`;
      const expectations: Array<[string, string]> = [
        [`${workspace}/${config.layout.projectFile}`, '项目定义 PROJECT.md'],
        [`${workspace}/${config.layout.stateFile}`, '项目状态 STATE.md'],
        [`${workspace}/${config.layout.tasksFile}`, '任务地图 TASKS.md'],
      ];
      for (const [rel, label] of expectations) {
        let ok = false;
        try {
          const { absolutePath } = guard.resolve(rel);
          ok = await fileExists(absolutePath);
        } catch {
          ok = false;
        }
        checks.push({
          name: `project:${rel}`,
          ok,
          critical: false,
          detail: ok ? `${label}存在：${rel}` : `${label}缺失：${rel}`,
        });
      }
      const pstateMissing = !(await existsRelative(guard, `${workspace}/${config.layout.stateFile}`));
      if (pstateMissing) {
        recommendedActions.push(
          '项目状态缺失：调用 knowledge_update_state(scope="project") 会自动从模板骨架创建 ' +
            `${workspace}/${config.layout.stateFile}，随后用 knowledge_update_state(scope="project-definition") 补 PROJECT.md。`,
        );
      }
    }

    // --- 8. Wiki 子目录（非致命，缺了写工具会自动创建） ---------------
    const missingWikiDirs: string[] = [];
    for (const [type, dir] of Object.entries(WIKI_TYPE_DIRS)) {
      const rel = `${config.layout.wiki}/${dir}`;
      try {
        const { absolutePath } = guard.resolve(rel);
        if (!(await isDirectory(absolutePath))) missingWikiDirs.push(`${dir}(${type})`);
      } catch {
        missingWikiDirs.push(`${dir}(${type})`);
      }
    }
    checks.push({
      name: 'wiki_subdirs',
      ok: missingWikiDirs.length === 0,
      critical: false,
      detail:
        missingWikiDirs.length === 0
          ? '全部 Wiki 子目录存在'
          : `缺失（写工具会自动创建）：${missingWikiDirs.join(', ')}`,
    });
  }

  // --- 9. 汇总 ---------------------------------------------------------
  const criticalFailures = checks.filter((c) => c.critical && !c.ok);
  const nonCriticalFailures = checks.filter((c) => !c.critical && !c.ok);

  const status: HealthStatus =
    criticalFailures.length > 0 ? 'unhealthy' : nonCriticalFailures.length > 0 ? 'degraded' : 'healthy';

  const canWrite =
    status !== 'unhealthy' && identity.canWrite && vaultExists && vaultReadable && vaultWritable;

  if (canWrite) {
    recommendedActions.push(
      '写操作可用。建议顺序：knowledge_state(scope="project") → 执行 Next Action → knowledge_update_state → knowledge_append_log。',
    );
  }

  return {
    status,
    projectRoot: displayPath(config.projectRoot),
    projectConfigPath: config.projectConfigPath ? displayPath(config.projectConfigPath) : null,
    vaultConfigPath: config.vaultConfigPath ? displayPath(config.vaultConfigPath) : null,
    projectId: identity.projectId,
    projectName: identity.projectName,
    vaultId: identity.vaultId,
    vaultPath: identity.vaultPath ? displayPath(identity.vaultPath) : null,
    vaultAccessible: vaultExists && vaultReadable,
    identityStatus: identity.status,
    canWrite,
    checks,
    warnings,
    errors,
    recommendedActions: [...new Set(recommendedActions)],
    layout: { ...config.layout },
  };
}
