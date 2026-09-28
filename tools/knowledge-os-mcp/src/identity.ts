/**
 * identity.ts — 三层身份校验。
 *
 *   .agent/project.yaml      project.id
 *            │
 *   .agent/vault.local.yaml  vault.id
 *            │
 *   <Vault>/VAULT_ID.md      project_id
 *
 * 必须满足三者完全一致。任何不一致：
 *   - 允许读取诊断信息（Agent 需要知道出了什么事）
 *   - **禁止一切写操作**
 *
 * 这条规则的目的只有一个：绝对不允许静默写进错误的 Vault。
 * 两个项目共用路径、复制粘贴配置写错 id、旧 Vault 残留 —— 都会在这里被挡住。
 */

import * as path from 'node:path';
import { KnowledgeError } from './errors.js';
import { fileExists, readSnapshot } from './fsx.js';
import { parseFrontmatter } from './markdown.js';
import type { KnowledgeConfig } from './config.js';

export type IdentityStatus =
  /** 三层完全一致 —— 可读可写 */
  | 'ok'
  /** VAULT_ID.md 存在，但某一层 id 不一致 —— 只读，禁止写 */
  | 'mismatch'
  /** Vault 内没有 VAULT_ID.md（可能是全新 Vault）—— 只读，除非走初始化流程 */
  | 'missing'
  /** VAULT_ID.md 存在但不是合法身份文件 —— 只读，禁止写 */
  | 'invalid'
  /** 配置不完整（缺 project.yaml / vault.local.yaml）或 Vault 目录不存在 */
  | 'unresolved';

export interface IdentityCheck {
  readonly status: IdentityStatus;
  readonly projectId: string | null;
  readonly projectName: string | null;
  readonly vaultId: string | null;
  readonly vaultPath: string | null;
  /** VAULT_ID.md 的 Vault 内相对路径 */
  readonly identityFile: string;
  /** VAULT_ID.md 内记录的 project_id */
  readonly vaultFileProjectId: string | null;
  readonly vaultFileProjectName: string | null;
  /** 人类可读的不一致说明；status === 'ok' 时为空数组 */
  readonly mismatches: readonly string[];
  /** 是否可以执行写操作 */
  readonly canWrite: boolean;
  /** 是否可以在「初始化」流程中创建 VAULT_ID.md（仅全新 Vault） */
  readonly canInitialize: boolean;
  readonly notes: readonly string[];
}

/** 执行三层身份校验。不修改任何文件。 */
export async function checkIdentity(config: KnowledgeConfig): Promise<IdentityCheck> {
  const identityFile = config.layout.vaultId;
  const notes: string[] = [...config.notes];
  const mismatches: string[] = [];

  const projectId = config.project?.id ?? null;
  const projectName = config.project?.name ?? null;
  const vaultId = config.vault?.id ?? null;
  const vaultPath = config.vault?.path ?? null;

  const base = {
    projectId,
    projectName,
    vaultId,
    vaultPath,
    identityFile,
    mismatches,
    notes,
  };

  // --- 1. 配置层是否齐全 ------------------------------------------------
  if (!projectId) {
    return {
      ...base,
      status: 'unresolved',
      vaultFileProjectId: null,
      vaultFileProjectName: null,
      canWrite: false,
      canInitialize: false,
    };
  }
  if (!vaultPath || !vaultId) {
    return {
      ...base,
      status: 'unresolved',
      vaultFileProjectId: null,
      vaultFileProjectName: null,
      canWrite: false,
      canInitialize: false,
    };
  }

  // --- 2. Vault 目录是否存在 -------------------------------------------
  if (!(await fileExists(vaultPath))) {
    notes.push(`Vault 目录不存在：${vaultPath}`);
    return {
      ...base,
      status: 'unresolved',
      vaultFileProjectId: null,
      vaultFileProjectName: null,
      canWrite: false,
      canInitialize: false,
    };
  }

  // --- 3. project.id vs vault.id ---------------------------------------
  if (projectId !== vaultId) {
    mismatches.push(
      `project.yaml 的 project.id ("${projectId}") != vault.local.yaml 的 vault.id ("${vaultId}")`,
    );
  }

  // --- 4. VAULT_ID.md ---------------------------------------------------
  const identityAbs = path.join(vaultPath, identityFile);
  if (!(await fileExists(identityAbs))) {
    notes.push(
      `Vault 内没有 ${identityFile}。无法确认这个目录属于项目 "${projectId}"。` +
        `在补齐身份文件之前，禁止任何写操作。`,
    );
    return {
      ...base,
      status: 'missing',
      vaultFileProjectId: null,
      vaultFileProjectName: null,
      canWrite: false,
      // 只有「配置两层一致」时才允许初始化身份文件
      canInitialize: projectId === vaultId,
    };
  }

  let fm: Record<string, unknown>;
  try {
    const snapshot = await readSnapshot(identityAbs);
    fm = parseFrontmatter(snapshot.raw).frontmatter;
  } catch (err) {
    notes.push(`无法读取 ${identityFile}：${(err as Error).message}`);
    return {
      ...base,
      status: 'invalid',
      vaultFileProjectId: null,
      vaultFileProjectName: null,
      canWrite: false,
      canInitialize: false,
    };
  }

  const fileProjectId = typeof fm['project_id'] === 'string' ? fm['project_id'].trim() : null;
  const fileProjectName = typeof fm['project_name'] === 'string' ? fm['project_name'].trim() : null;

  if (fm['type'] !== 'vault-identity') {
    mismatches.push(`${identityFile} 的 frontmatter 缺少 type: vault-identity（实际：${String(fm['type']) }）`);
  }
  if (!fileProjectId) {
    mismatches.push(`${identityFile} 的 frontmatter 缺少 project_id`);
  } else if (fileProjectId !== projectId) {
    mismatches.push(
      `${identityFile} 的 project_id ("${fileProjectId}") != project.yaml 的 project.id ("${projectId}")`,
    );
  }

  const status: IdentityStatus = mismatches.length === 0 ? 'ok' : 'mismatch';

  return {
    ...base,
    status,
    vaultFileProjectId: fileProjectId,
    vaultFileProjectName: fileProjectName,
    canWrite: status === 'ok',
    canInitialize: false,
  };
}

/**
 * 写操作守卫：身份不通过就抛错。
 * 每个写工具的第一步都必须是它。
 */
export function assertWritable(identity: IdentityCheck): void {
  if (identity.canWrite) return;

  const reason =
    identity.status === 'mismatch'
      ? '三层身份校验不一致'
      : identity.status === 'missing'
        ? 'Vault 内缺少 VAULT_ID.md'
        : identity.status === 'invalid'
          ? 'VAULT_ID.md 不是合法的身份文件'
          : '配置不完整，无法解析出项目与 Vault';

  throw new KnowledgeError(
    'identity_mismatch',
    `拒绝写入：${reason}。允许读取诊断信息，但禁止任何写操作（防止写进错误的 Vault）。`,
    {
      status: identity.status,
      projectId: identity.projectId,
      vaultId: identity.vaultId,
      vaultFileProjectId: identity.vaultFileProjectId,
      vaultPath: identity.vaultPath,
      identityFile: identity.identityFile,
      mismatches: identity.mismatches,
      notes: identity.notes,
    },
  );
}

/** 生成 VAULT_ID.md 的内容（初始化与自愈都用它，保证格式唯一）。 */
export function renderVaultIdentityFile(projectId: string, projectName: string): string {
  return `---
type: vault-identity
project_id: "${projectId}"
project_name: "${projectName}"
---

# Vault Identity

Project ID: \`${projectId}\`

Project Name: \`${projectName}\`
`;
}
