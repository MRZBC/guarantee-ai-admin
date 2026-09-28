/**
 * fixture.ts — 跨目录集成测试夹具。
 *
 * 关键要求（规范第 49 节）：
 *   project 目录与 vault 目录必须是**完全不同的两个目录**。
 *
 * 结构：
 *   <tmp>/project/      ← 模拟代码仓库（含 .agent/project.yaml 与 vault.local.yaml）
 *   <tmp>/vault/        ← 模拟 Obsidian Vault（含 VAULT_ID.md 等）
 *
 * 每个用例都用新的 tmp 目录，互不干扰。
 */

import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

export interface FixtureOptions {
  projectId?: string;
  projectName?: string;
  /** 是否创建 .agent/project.yaml */
  withProjectConfig?: boolean;
  /** 是否创建 .agent/vault.local.yaml */
  withVaultConfig?: boolean;
  /** vault.local.yaml 里的 vault.id（用于构造不一致场景） */
  vaultConfigId?: string;
  /** 是否创建 Vault 目录 */
  withVaultDir?: boolean;
  /** 是否创建 VAULT_ID.md */
  withVaultIdFile?: boolean;
  /** VAULT_ID.md 内的 project_id（用于构造不一致场景） */
  vaultIdFileProjectId?: string;
  /** vault.local.yaml 里 vault.path 是否指向一个不存在的目录 */
  vaultPathOverride?: string;
  /** 指向真实 Vault 的绝对路径（默认自动创建） */
  vaultPath?: string;
  /** 写入 Vault 根 STATE.md 的内容（null 表示不创建） */
  globalState?: string | null;
  /** 写入 Vault LOG.md 的内容 */
  logContent?: string | null;
  /** 额外的 Vault 文件 { 相对路径: 内容 } */
  files?: Record<string, string>;
}

export interface Fixture {
  readonly root: string;
  readonly projectDir: string;
  readonly vaultDir: string;
  readonly projectId: string;
  readonly projectName: string;
  readonly serverEntry: string;
  cleanup(): void;
}

/** dist/index.js 的绝对路径（相对本测试文件定位，避免依赖 cwd）。 */
export function serverEntryPath(): string {
  const here = path.dirname(fileURLToPath(import.meta.url));
  return path.resolve(here, '..', '..', 'dist', 'index.js');
}

export function defaultGlobalState(projectId = ''): string {
  return `---
type: global-state
updated: 2026-01-01
active_project: "${projectId}"
active_phase: ""
---

# 当前状态

> 本文件只回答一个问题：**这个 Vault 现在最重要的工作状态是什么？**

## Active Project 活动项目

${projectId}

## Objective 目标

（待补充）

## Completed 已完成

- 无。

## In Progress 进行中

- 无。

## Next Action 下一步行动

（待补充）

## Blockers 阻塞项

- 无。

## Verification 验证

### Verified 已验证

- 无。

### Not Yet Verified 尚未验证

- 无。

## Handoff Notes 交接说明

（待补充）

## Important Decisions 重要决策

- 无。
`;
}

export function createFixture(options: FixtureOptions = {}): Fixture {
  const projectId = options.projectId ?? 'demo-project';
  const projectName = options.projectName ?? '演示项目';

  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'kos-fixture-'));
  const projectDir = path.join(root, 'project');
  const vaultDir = options.vaultPath ?? path.join(root, 'vault');

  fs.mkdirSync(path.join(projectDir, '.agent'), { recursive: true });

  // --- .agent/project.yaml ---------------------------------------------
  if (options.withProjectConfig !== false) {
    fs.writeFileSync(
      path.join(projectDir, '.agent', 'project.yaml'),
      `version: 1\n\nproject:\n  id: "${projectId}"\n  name: "${projectName}"\n`,
      'utf8',
    );
  }

  // --- Vault 目录 -------------------------------------------------------
  const vaultPathInConfig = options.vaultPathOverride ?? vaultDir;
  if (options.withVaultDir !== false && options.vaultPathOverride === undefined) {
    fs.mkdirSync(vaultDir, { recursive: true });
  }

  // --- .agent/vault.local.yaml -----------------------------------------
  if (options.withVaultConfig !== false) {
    fs.writeFileSync(
      path.join(projectDir, '.agent', 'vault.local.yaml'),
      `version: 1\n\nvault:\n  id: "${options.vaultConfigId ?? projectId}"\n  path: "${vaultPathInConfig.replace(/\\/g, '/')}"\n`,
      'utf8',
    );
  }

  // --- VAULT_ID.md ------------------------------------------------------
  if (options.withVaultIdFile !== false && fs.existsSync(vaultDir)) {
    const idInFile = options.vaultIdFileProjectId ?? projectId;
    fs.writeFileSync(
      path.join(vaultDir, 'VAULT_ID.md'),
      `---\ntype: vault-identity\nproject_id: "${idInFile}"\nproject_name: "${projectName}"\n---\n\n# Vault Identity\n\nProject ID: \`${idInFile}\`\n\nProject Name: \`${projectName}\`\n`,
      'utf8',
    );
  }

  // --- 基础 Vault 结构（模拟「既有 Vault」） ----------------------------
  if (fs.existsSync(vaultDir)) {
    for (const dir of [
      '00_System/templates',
      '01_Inbox',
      '02_Raw',
      '03_Wiki/Concepts',
      '03_Wiki/Technologies',
      '03_Wiki/Decisions',
      '03_Wiki/Lessons',
      '03_Wiki/Sources',
      '03_Wiki/Syntheses',
      '03_Wiki/Projects',
      '03_Wiki/People',
      '03_Wiki/Companies',
      '03_Wiki/Works',
      '04_Work/Active',
      '04_Work/Archive',
      '05_Output',
      '90_Archive',
    ]) {
      fs.mkdirSync(path.join(vaultDir, dir), { recursive: true });
    }

    // 一个真实的 Vault 会有协议与导航文件（health 会检查它们）
    const protocolPath = path.join(vaultDir, 'AGENTS.md');
    if (!fs.existsSync(protocolPath)) {
      fs.writeFileSync(
        protocolPath,
        '---\ntype: agent-protocol\nstatus: canonical\nversion: 1\n---\n\n# AGENTS.md — Agent 行为协议\n\n> 测试夹具中的最小协议文件。\n',
        'utf8',
      );
    }
    const indexPath = path.join(vaultDir, 'INDEX.md');
    if (!fs.existsSync(indexPath)) {
      fs.writeFileSync(indexPath, '---\ntype: index\n---\n\n# 知识索引\n\n- [[STATE]]\n- [[LOG]]\n', 'utf8');
    }

    if (options.globalState !== null) {
      fs.writeFileSync(
        path.join(vaultDir, 'STATE.md'),
        options.globalState ?? defaultGlobalState(projectId),
        'utf8',
      );
    }

    if (options.logContent !== null) {
      fs.writeFileSync(
        path.join(vaultDir, 'LOG.md'),
        options.logContent ??
          `---\ntype: activity-log\nupdated: 2026-01-01\n---\n\n# 活动日志\n\n> **只追加，不重写。**\n\n---\n\n## [2026-01-01] MIGRATION | Vault 初始化\n\n### Completed 已完成\n\n- 建立 Vault 骨架。\n`,
        'utf8',
      );
    }
  }

  // --- 额外文件 ---------------------------------------------------------
  for (const [rel, content] of Object.entries(options.files ?? {})) {
    const abs = path.join(vaultDir, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  }

  return {
    root,
    projectDir,
    vaultDir,
    projectId,
    projectName,
    serverEntry: serverEntryPath(),
    cleanup() {
      try {
        fs.rmSync(root, { recursive: true, force: true });
      } catch {
        /* Windows 上偶发占用，忽略 */
      }
    },
  };
}

/** 读取 Vault 内文件。 */
export function readVault(fixture: Fixture, rel: string): string {
  return fs.readFileSync(path.join(fixture.vaultDir, rel), 'utf8');
}

/** 直接写入 Vault 内文件（模拟「另一个 Agent 的修改」）。 */
export function writeVault(fixture: Fixture, rel: string, content: string): void {
  const abs = path.join(fixture.vaultDir, rel);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, content, 'utf8');
}

export function existsVault(fixture: Fixture, rel: string): boolean {
  return fs.existsSync(path.join(fixture.vaultDir, rel));
}
