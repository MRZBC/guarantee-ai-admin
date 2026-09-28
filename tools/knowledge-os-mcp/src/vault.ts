/**
 * vault.ts — Vault 上下文（Vault Repository 的入口）。
 *
 * 职责边界：
 *   本模块把「配置 + 身份校验 + 路径守卫 + 布局」装配成一个可用的上下文对象。
 *   所有领域操作（state / wiki / decisions / log / search）都只拿这个上下文，
 *   自己不再解析配置、不再拼路径、不再判断身份。
 *
 *   这样做的直接收益：写操作的安全顺序只能有一个实现，不可能被绕过：
 *     resolve project → resolve vault → validate identity
 *       → resolve semantic target → validate target path → write
 */

import * as fs from 'node:fs/promises';
import type { Dirent } from 'node:fs';
import * as path from 'node:path';
import { loadConfig, type KnowledgeConfig, type LoadConfigOptions } from './config.js';
import { KnowledgeError } from './errors.js';
import { atomicWrite, fileExists, isDirectory, isFile, readSnapshot } from './fsx.js';
import { assertWritable, checkIdentity, renderVaultIdentityFile, type IdentityCheck } from './identity.js';
import type { Layout } from './layout.js';
import { VaultPathGuard } from './pathguard.js';

export interface VaultContext {
  readonly config: KnowledgeConfig;
  readonly layout: Layout;
  readonly identity: IdentityCheck;
  readonly guard: VaultPathGuard;
  readonly vaultPath: string;
  /** 项目是否已完成三层身份校验（决定能不能写） */
  readonly writable: boolean;
}

/** 装配上下文。不做任何写操作。 */
export async function openVault(options: LoadConfigOptions = {}): Promise<VaultContext> {
  const config = loadConfig(options);
  const identity = await checkIdentity(config);
  const vaultPath = config.vault?.path ?? config.projectRoot;

  return {
    config,
    layout: config.layout,
    identity,
    guard: new VaultPathGuard({ vaultRoot: vaultPath }),
    vaultPath,
    writable: identity.canWrite,
  };
}

/** 所有写操作的第一步。 */
export function requireWritable(ctx: VaultContext): void {
  assertWritable(ctx.identity);
}

export interface BootstrapAction {
  readonly kind: 'create';
  readonly path: string;
  readonly reason: string;
}

/**
 * TASKS.md 骨架：完整任务地图（与 STATE.md 的「现在」严格区分）。
 * 结构与既有 Vault 的 `00_System/templates/tasks.md.template` 保持一致。
 */
function renderTasksSkeleton(projectId: string, projectName: string): string {
  // 用**本地**日期，不用 toISOString()（那是 UTC，在东八区会得到前一天）
  const d = new Date();
  const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  return `---
type: project-tasks
project: "${projectName}"
updated: ${today}
---

# 任务

> **本项目的完整任务地图**——所有已知的事，无论做没做。
> 它**不是**当前指针。此刻该做什么在 \`STATE.md\` 里。
>
> 完成一项就勾掉。新发现的活儿加到 **未计划 / 新发现** 一节。不要悄悄丢掉任务。

**位置：** \`04_Work/Active/${projectId}/TASKS.md\`

## Phase 1 — <阶段名>

- [ ] （待补充）

## 未计划 / 新发现

执行过程中发现、但不在原计划里的工作。放在这里，而不是就地扩大当前任务的范围。

- [ ] ...

## 已推迟 / 超出范围

- ...

## 已完成（汇总）

一段简短的汇总，避免文件长大之后难以阅读。细节留在 \`LOG.md\`。

- （暂无）
`;
}

export interface BootstrapResult {
  readonly applied: boolean;
  readonly vaultPath: string;
  /** 本次是在哪种授权模式下执行的 */
  readonly mode: 'normal' | 'initialize-identity';
  readonly created: readonly BootstrapAction[];
  readonly skipped: readonly BootstrapAction[];
  readonly warnings: readonly string[];
}

/**
 * 幂等地补齐 Vault 的**最小基础设施**，绝不覆盖任何既有文件。
 *
 * 严格遵循规范的「旧 Vault 处理原则」：
 *   - 只创建缺失项
 *   - 不删除、不迁移、不重命名、不批量重写
 *   - 已存在的一律跳过并报告
 *
 * 授权模式：
 *   normal               三层身份校验已通过 → 可补齐全部缺失项
 *   initialize-identity  Vault 里还没有 VAULT_ID.md，但配置两层 id 一致
 *                        → **只允许创建 VAULT_ID.md 这一个文件**
 *
 * 为什么需要 initialize-identity 模式：
 *   否则会出现死锁 —— 没有 VAULT_ID.md 就不许写，而不许写就永远建不出 VAULT_ID.md。
 *   这把「首次建立身份」变成一个显式、可审计、且**范围被限制到单个文件**的动作，
 *   其余基础设施要等身份校验通过后（再调一次）才会补齐。
 */
export async function bootstrapVaultInfrastructure(
  ctx: VaultContext,
  options: { apply: boolean },
): Promise<BootstrapResult> {
  const { layout } = ctx;
  const created: BootstrapAction[] = [];
  const skipped: BootstrapAction[] = [];
  const warnings: string[] = [];

  // --- 授权判定 ---------------------------------------------------------
  let mode: 'normal' | 'initialize-identity';
  if (ctx.identity.canWrite) {
    mode = 'normal';
  } else if (ctx.identity.canInitialize) {
    // 允许创建身份文件，但**仅此一项**（见下面的 planned 列表裁剪）
    mode = 'initialize-identity';
    warnings.push(
      `Vault 内还没有 ${layout.vaultId}。本次只创建这一个身份文件（配置两层 id 已一致：` +
        `project.id == vault.id == "${ctx.identity.projectId}"）。` +
        '身份文件就位后，请再调用一次 knowledge_bootstrap 补齐工作区与 TASKS.md。',
    );
  } else {
    // 复用统一的写守卫：它会抛出带完整诊断的 identity_mismatch
    assertWritable(ctx.identity);
    throw new Error('unreachable');
  }

  const projectId = ctx.identity.projectId!;
  const projectName = ctx.identity.projectName ?? projectId;

  type Planned =
    | { type: 'file'; rel: string; content: string; reason: string }
    | { type: 'dir'; rel: string; reason: string };

  const identityFilePlan: Planned = {
    type: 'file',
    rel: layout.vaultId,
    content: renderVaultIdentityFile(projectId, projectName),
    reason: 'Vault 身份文件（三层身份校验的第三层）',
  };

  // 注意：这里**不**创建 PROJECT.md 与 STATE.md。
  //   它们的语义内容只能由 Agent 通过 knowledge_update_state 提供，
  //   凭空生成一份没有信息的骨架会变成「看起来做完了其实没有」的假象。
  //   本函数只补齐真正的结构件：身份文件、任务地图、工作区目录。
  const infrastructurePlan: Planned[] = [
    {
      type: 'file',
      rel: `${layout.active}/${projectId}/${layout.tasksFile}`,
      content: renderTasksSkeleton(projectId, projectName),
      reason: '完整任务地图（TASKS.md）',
    },
    { type: 'dir', rel: layout.active, reason: '活动项目根目录' },
    { type: 'dir', rel: `${layout.active}/${projectId}`, reason: '本项目的工作区' },
    {
      type: 'dir',
      rel: `${layout.active}/${projectId}/${layout.projectDecisionsDir}`,
      reason: '项目级决策',
    },
    {
      type: 'dir',
      rel: `${layout.active}/${projectId}/${layout.projectArtifactsDir}`,
      reason: '项目产物',
    },
  ];

  const planned: Planned[] =
    mode === 'initialize-identity' ? [identityFilePlan] : [identityFilePlan, ...infrastructurePlan];

  for (const item of planned) {
    const { absolutePath, relative } = ctx.guard.resolve(item.rel);
    if (item.type === 'dir') {
      if (await isDirectory(absolutePath)) {
        skipped.push({ kind: 'create', path: relative, reason: '已存在' });
      } else if (await fileExists(absolutePath)) {
        warnings.push(`期望是目录但实际是文件，未处理：${relative}`);
        skipped.push({ kind: 'create', path: relative, reason: '同名文件已存在，需人工处理' });
      } else {
        created.push({ kind: 'create', path: relative, reason: item.reason });
        if (options.apply) await fs.mkdir(absolutePath, { recursive: true });
      }
      continue;
    }

    if (await fileExists(absolutePath)) {
      skipped.push({ kind: 'create', path: relative, reason: '已存在，按「不覆盖」原则跳过' });
      continue;
    }
    created.push({ kind: 'create', path: relative, reason: item.reason });
    if (options.apply) {
      await atomicWrite(absolutePath, { content: item.content, newline: '\n' });
    }
  }

  return {
    applied: options.apply,
    vaultPath: ctx.vaultPath,
    mode,
    created,
    skipped,
    warnings,
  };
}

/** 读取 Vault 内文件；不存在时返回 null。 */
export async function readVaultFile(ctx: VaultContext, relative: string) {
  const { absolutePath, relative: rel } = ctx.guard.resolve(relative);
  if (!(await isFile(absolutePath))) return null;
  const snapshot = await readSnapshot(absolutePath);
  return { relative: rel, snapshot };
}

/** 判断 Vault 内某相对路径是否存在。 */
export async function vaultEntryExists(ctx: VaultContext, relative: string): Promise<boolean> {
  const { absolutePath } = ctx.guard.resolve(relative);
  return fileExists(absolutePath);
}

/**
 * 探测 Vault 是否「已经有内容」——用于区分「全新 Vault」与「别人的既有 Vault」。
 * 排除工具目录（.obsidian/ 等），因为它们不构成知识内容。
 */
const TOOL_DIRS = new Set(['.obsidian', '.copilot', 'copilot', '.claude', '.git', '.trash']);

export async function vaultHasContent(vaultPath: string): Promise<boolean> {
  let entries: Dirent[];
  try {
    entries = await fs.readdir(vaultPath, { withFileTypes: true });
  } catch {
    return false;
  }
  for (const e of entries) {
    if (TOOL_DIRS.has(e.name)) continue;
    if (e.name === 'VAULT_ID.md') continue; // 身份文件本身不算内容
    return true;
  }
  return false;
}

/** 目录名安全校验：只允许单层、无路径分隔符。 */
export function assertSafeProjectId(projectId: string): void {
  if (projectId.includes('/') || projectId.includes('\\') || projectId === '.' || projectId === '..') {
    throw new Error(`不安全的 project.id：${projectId}`);
  }
}

export function projectWorkspacePath(layout: Layout, projectId: string): string {
  assertSafeProjectId(projectId);
  return path.posix.join(layout.active, projectId);
}

/**
 * 按**逻辑名称**（不是路径字符串）解析出 Vault 内某个已知文件。
 *
 * 为什么需要它：`knowledge_update_state` 的 scope 只能定位到「状态文件」，
 * 但同目录下的 `TASKS.md`（完整任务地图）也需要能被语义化更新。
 *
 * 为什么是白名单而不是接受路径：模型永远不应该能指定要写哪个文件。
 * 这里只接受一组**枚举名**，路径由 Server 自己拼 —— 安全性没有放松。
 */
export type KnownProjectFile = 'tasks';

export function resolveKnownProjectFile(
  ctx: VaultContext,
  file: KnownProjectFile,
): { relativePath: string; label: string } {
  const projectId = ctx.identity.projectId;
  if (!projectId) {
    throw new KnowledgeError('project_config_missing', '无法解析文件位置：project.id 未配置');
  }
  const workspace = projectWorkspacePath(ctx.layout, projectId);

  switch (file) {
    case 'tasks':
      return { relativePath: `${workspace}/${ctx.layout.tasksFile}`, label: '完整任务地图' };
    default: {
      const never: never = file;
      throw new KnowledgeError('invalid_argument', `未知的文件名：${String(never)}`, {
        allowed: ['tasks'],
      });
    }
  }
}
