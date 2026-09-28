/**
 * layout.ts — Vault 逻辑布局 → 实际相对路径的映射。
 *
 * 为什么需要这一层：
 *   规范里的参考布局（`03_Wiki/Decisions/`、`STATE.md` …）是本项目的**默认约定**，
 *   不能硬编码成唯一真理。既有 Vault 可能用别的目录名，仓库里的
 *   `.agent/vault.local.yaml` 可以覆盖任意一项，而不用改代码。
 *
 * 所有路径都是「Vault 内相对路径」，最终仍要过 pathguard 才是绝对路径。
 */

import { KnowledgeError } from './errors.js';

/** Vault 默认布局。与 00_System/SCHEMA.md 描述的结构一致。 */
export const DEFAULT_LAYOUT = {
  /** Vault 身份文件 */
  vaultId: 'VAULT_ID.md',
  /** Agent 行为协议 */
  agents: 'AGENTS.md',
  /** 全局状态 */
  state: 'STATE.md',
  /** 导航入口 */
  index: 'INDEX.md',
  /** 追加式历史 */
  log: 'LOG.md',

  /** 系统目录（规则与模板） */
  system: '00_System',
  templates: '00_System/templates',

  /** 知识根 */
  wiki: '03_Wiki',
  /** 决策页目录 */
  decisions: '03_Wiki/Decisions',
  /** 项目工作区根 */
  work: '04_Work',
  /** 活动项目根 */
  active: '04_Work/Active',
  /** 归档项目根 */
  archive: '04_Work/Archive',

  /** 项目定义文件名（位于 04_Work/Active/<project-id>/ 内） */
  projectFile: 'PROJECT.md',
  /** 项目状态文件名 */
  stateFile: 'STATE.md',
  /** 任务地图文件名 */
  tasksFile: 'TASKS.md',
  /** 项目内决策目录名 */
  projectDecisionsDir: 'decisions',
  /** 项目内产物目录名 */
  projectArtifactsDir: 'artifacts',
} as const;

export type LayoutKey = keyof typeof DEFAULT_LAYOUT;
export type Layout = Record<LayoutKey, string>;

/** 用户可在 vault.local.yaml 的 `layout:` 段覆盖的键（白名单）。 */
export const OVERRIDABLE_LAYOUT_KEYS: readonly LayoutKey[] = Object.keys(DEFAULT_LAYOUT) as LayoutKey[];

export function resolveLayout(overrides?: Partial<Record<string, unknown>>): Layout {
  const layout: Layout = { ...DEFAULT_LAYOUT };
  if (!overrides) return layout;

  for (const [key, value] of Object.entries(overrides)) {
    if (!(OVERRIDABLE_LAYOUT_KEYS as readonly string[]).includes(key)) {
      throw new KnowledgeError('vault_config_invalid', `layout 中存在未知键：${key}`, {
        key,
        allowed: OVERRIDABLE_LAYOUT_KEYS,
      });
    }
    if (typeof value !== 'string' || value.trim() === '') {
      throw new KnowledgeError('vault_config_invalid', `layout.${key} 必须是非空字符串`, { key });
    }
    layout[key as LayoutKey] = value.trim().replace(/\\/g, '/');
  }
  return layout;
}

/** Wiki 页面 type → 子目录名。用于 knowledge_upsert_wiki 自动落位。 */
export const WIKI_TYPE_DIRS = {
  concept: 'Concepts',
  technology: 'Technologies',
  project: 'Projects',
  lesson: 'Lessons',
  source: 'Sources',
  synthesis: 'Syntheses',
  person: 'People',
  company: 'Companies',
  work: 'Works',
  decision: 'Decisions',
} as const;

export type WikiType = keyof typeof WIKI_TYPE_DIRS;

export const WIKI_TYPES: readonly WikiType[] = Object.keys(WIKI_TYPE_DIRS) as WikiType[];

/** 决策页文件名前缀（与既有 Vault 的命名惯例一致：`决策 - <标题>.md`）。 */
export const DECISION_PREFIX = '决策 - ';

/** 综合页文件名前缀。 */
export const SYNTHESIS_PREFIX = '综合 - ';

export function wikiDirForType(layout: Layout, type: WikiType): string {
  return `${layout.wiki}/${WIKI_TYPE_DIRS[type]}`;
}

/** 项目工作区目录：04_Work/Active/<project-id>/ */
export function activeProjectDir(layout: Layout, projectId: string): string {
  return `${layout.active}/${projectId}`;
}
