/**
 * state.ts — STATE（现在在哪、下一步做什么）。
 *
 * STATE 是「指针」，不是日记：
 *   历史进 LOG.md，完整任务地图进 TASKS.md，为什么这么做进 Decision。
 *
 * 三种 scope（由 Server 决定写哪个文件，模型不能指定路径）：
 *   global  → <Vault>/STATE.md
 *   project → <Vault>/04_Work/Active/<project-id>/STATE.md
 *   project-definition → <Vault>/04_Work/Active/<project-id>/PROJECT.md
 *
 * 本模块的字段别名表同时兼容两套标题惯例：
 *   - 既有 Vault 的 `## Current Objective 当前目标` 风格（00_System/templates/state.md.template）
 *   - 仓库规范里的 `## Objective 目标` 风格
 * 于是同一个工具既能更新既有文件，也能给新文件写出规范要求的标题。
 */

import { KnowledgeError } from './errors.js';
import { atomicWrite, tryReadSnapshot, type FileSnapshot } from './fsx.js';
import {
  parseMarkdown,
  replaceSection,
  readSection,
  locateSection,
  SECTION_ANCHORS,
  toPlainText,
  upsertFrontmatter,
  type ParsedMarkdown,
  type SectionSpec,
} from './markdown.js';
import { projectWorkspacePath, requireWritable, resolveKnownProjectFile, type KnownProjectFile, type VaultContext } from './vault.js';

export type StateScope = 'global' | 'project' | 'project-definition';

export const STATE_SCOPES: readonly StateScope[] = ['global', 'project', 'project-definition'];

/** 语义字段 → 类型。用 never 技巧保证 switch 穷尽。 */
export type StateField =
  | 'activeProject'
  | 'objective'
  | 'currentMilestone'
  | 'completed'
  | 'inProgress'
  | 'nextAction'
  | 'blockers'
  | 'verification'
  | 'handoffNotes'
  | 'importantDecisions';

const f = (
  field: string,
  aliases: string[],
  canonicalHeading: string,
  before?: string | null,
): SectionSpec => ({ field, aliases, canonicalHeading, before: before ?? null });

/**
 * 三套标题别名表。
 * 「英文标题」用于新文件（贴合规范），「中文标题」用于既有 Vault 文件。
 */
export const GLOBAL_SECTIONS: Record<StateField, SectionSpec> = {
  activeProject: f('activeProject', ['Active Project 活动项目', 'active project', '活动项目', 'Active Project'], 'Active Project 活动项目'),
  objective: f('objective', ['Objective 目标', 'objective', '目标', 'Objective', 'Current Objective 当前目标', '当前目标'], 'Objective 目标'),
  currentMilestone: f('currentMilestone', ['Current Phase 当前阶段', 'current phase', '当前阶段', 'Current Milestone 当前里程碑', '当前里程碑', 'Current Milestone', 'currentMilestone'], 'Current Milestone 当前里程碑', 'completed'),
  completed: f('completed', ['Completed 已完成', 'completed', '已完成', 'Completed'], 'Completed 已完成'),
  inProgress: f('inProgress', ['In Progress 进行中', 'in progress', '进行中', 'In Progress'], 'In Progress 进行中'),
  nextAction: f('nextAction', ['Next Action 下一步行动', 'next action', '下一步行动', '下一步', 'Next Action'], 'Next Action 下一步行动'),
  blockers: f('blockers', ['Blockers 阻塞项', 'blockers', '阻塞项', 'Blockers', 'Blockers 阻塞'], 'Blockers 阻塞项', 'handoffNotes'),
  verification: f('verification', ['Verification 验证', 'verification', '验证', 'Verification'], 'Verification 验证', 'handoffNotes'),
  handoffNotes: f('handoffNotes', ['Handoff Notes 交接说明', 'handoff notes', '交接说明', '交接', 'Handoff Notes', 'Recent Changes 近期变更', '近期变更'], 'Handoff Notes 交接说明'),
  importantDecisions: f('importantDecisions', ['Important Decisions 重要决策', 'important decisions', '重要决策', '近期决策', 'Recent Decisions 近期决策', 'Recent Decisions'], 'Important Decisions 重要决策'),
};

export const PROJECT_SECTIONS: Record<StateField, SectionSpec> = {
  activeProject: f('activeProject', ['Active Project 活动项目', '活动项目'], 'Active Project 活动项目'),
  objective: f('objective', ['Current Objective 当前目标', 'current objective', '当前目标', 'Objective 目标', '目标'], 'Current Objective 当前目标'),
  currentMilestone: f('currentMilestone', ['Current Milestone 当前里程碑', 'current milestone', '当前里程碑', 'Current Phase 当前阶段', '当前阶段'], 'Current Milestone 当前里程碑', 'completed'),
  completed: f('completed', ['Completed 已完成', 'completed', '已完成'], 'Completed 已完成'),
  inProgress: f('inProgress', ['In Progress 进行中', 'in progress', '进行中'], 'In Progress 进行中'),
  nextAction: f('nextAction', ['Next Action 下一步行动', 'next action', '下一步行动'], 'Next Action 下一步行动'),
  blockers: f('blockers', ['Blockers 阻塞项', 'blockers', '阻塞项', 'Blockers 阻塞'], 'Blockers 阻塞项'),
  verification: f('verification', ['Verification 验证', 'verification', '验证'], 'Verification 验证'),
  handoffNotes: f('handoffNotes', ['Handoff Notes 交接说明', 'handoff notes', '交接说明', 'Current Risks 当前风险', 'Current Discoveries 近期发现'], 'Handoff Notes 交接说明'),
  importantDecisions: f('importantDecisions', ['Recent Decisions 近期决策', 'recent decisions', '重要决策', 'Important Decisions 重要决策'], 'Important Decisions 重要决策'),
};

/** PROJECT.md 允许的语义字段（project-definition scope）。 */
export type ProjectDefinitionField =
  | 'objective'
  | 'background'
  | 'scope'
  | 'successCriteria'
  | 'nonGoals'
  | 'constraints'
  | 'architecture'
  | 'keyComponents'
  | 'currentStrategy'
  | 'risks'
  | 'openQuestions'
  | 'relatedKnowledge'
  | 'relatedDecisions'
  | 'relatedArtifacts';

/** PROJECT.md 的完整小节集（项目定义 = 为什么存在、边界在哪）。 */
export const PROJECT_DEFINITION_SECTIONS: Record<ProjectDefinitionField, SectionSpec> = {
  objective: f('objective', ['Objective 目标', 'objective', '目标', 'Current Objective 当前目标', '当前目标'], 'Objective 目标'),
  background: f('background', ['Background 背景', 'background', '背景'], 'Background 背景'),
  scope: f('scope', ['Scope 范围', 'scope', '范围'], 'Scope 范围'),
  successCriteria: f('successCriteria', ['Success Criteria 成功标准', 'success criteria', '成功标准'], 'Success Criteria 成功标准'),
  nonGoals: f('nonGoals', ['Non-Goals 非目标', 'non-goals', 'non goals', '非目标'], 'Non-Goals 非目标'),
  constraints: f('constraints', ['Constraints 约束', 'constraints', '约束'], 'Constraints 约束'),
  architecture: f('architecture', ['Architecture 架构', 'architecture', '架构'], 'Architecture 架构'),
  keyComponents: f('keyComponents', ['Key Components 关键组件', 'key components', '关键组件'], 'Key Components 关键组件'),
  currentStrategy: f('currentStrategy', ['Current Strategy 当前策略', 'current strategy', '当前策略'], 'Current Strategy 当前策略'),
  risks: f('risks', ['Risks 风险', 'risks', '风险'], 'Risks 风险'),
  openQuestions: f('openQuestions', ['Open Questions 待解决问题', 'open questions', '待解决问题'], 'Open Questions 待解决问题'),
  relatedKnowledge: f('relatedKnowledge', ['Related Knowledge 相关知识', 'related knowledge', '相关知识'], 'Related Knowledge 相关知识'),
  relatedDecisions: f('relatedDecisions', ['Related Decisions 相关决策', 'related decisions', '相关决策'], 'Related Decisions 相关决策'),
  relatedArtifacts: f('relatedArtifacts', ['Related Artifacts 相关产物', 'related artifacts', '相关产物'], 'Related Artifacts 相关产物'),
};

export const PROJECT_DEFINITION_FIELDS = Object.keys(PROJECT_DEFINITION_SECTIONS) as ProjectDefinitionField[];

// 注册锚点，供「新建小节时插到谁前面」使用。
for (const table of [GLOBAL_SECTIONS, PROJECT_SECTIONS, PROJECT_DEFINITION_SECTIONS]) {
  for (const spec of Object.values(table)) {
    if (spec) SECTION_ANCHORS[spec.field] = spec;
  }
}

/** 供工具入参校验使用的字段清单。 */
export const STATE_FIELDS: readonly StateField[] = [
  'activeProject',
  'objective',
  'currentMilestone',
  'completed',
  'inProgress',
  'nextAction',
  'blockers',
  'verification',
  'handoffNotes',
  'importantDecisions',
];

/**
 * 「按标题直接写小节」的字段键前缀。
 *
 * 用途：PROJECT.md 这类长文档会有固定语义字段覆盖不到的小节。
 * 调用方给一个**标题**，Server 把它当作 `## <标题>` 的语义字段名 ——
 * 依然只影响该小节，调用方永远拿不到文件路径。
 */
export const CUSTOM_SECTION_PREFIX = 'custom:';

export function customSectionFieldKey(heading: string): string {
  return `${CUSTOM_SECTION_PREFIX}${heading.trim()}`;
}

/** 把 custom: 字段转换成 SectionSpec（标题本身即别名）。 */
export function sectionSpecFor(field: string): SectionSpec | null {
  if (!field.startsWith(CUSTOM_SECTION_PREFIX)) return null;
  const heading = field.slice(CUSTOM_SECTION_PREFIX.length).trim();
  if (heading === '') return null;
  return { field, aliases: [heading], canonicalHeading: heading, before: null };
}

export interface StateFileTarget {
  readonly scope: StateScope;
  /** Vault 内相对路径 */
  readonly relativePath: string;
  /**
   * 该 scope 允许的语义字段 → 小节定义。
   * key 的集合随 scope 不同（global/project 用 StateField，project-definition 用 ProjectDefinitionField），
   * 因此这里用宽松的 string 键；非法字段由 updateState / tools 层报错。
   */
  readonly sections: Readonly<Record<string, SectionSpec>>;
  readonly title: string;
}

/** 决定某个 scope 对应哪个文件 —— 模型不能自行指定路径。 */
export function resolveStateTarget(ctx: VaultContext, scope: StateScope): StateFileTarget {
  const projectId = ctx.identity.projectId;
  if (!projectId) {
    throw new KnowledgeError('project_config_missing', '无法决定 STATE 目标：project.id 未配置');
  }
  const workspace = projectWorkspacePath(ctx.layout, projectId);

  switch (scope) {
    case 'global':
      return {
        scope,
        relativePath: ctx.layout.state,
        sections: GLOBAL_SECTIONS,
        title: '全局状态',
      };
    case 'project':
      return {
        scope,
        relativePath: `${workspace}/${ctx.layout.stateFile}`,
        sections: PROJECT_SECTIONS,
        title: '项目状态',
      };
    case 'project-definition':
      return {
        scope,
        relativePath: `${workspace}/${ctx.layout.projectFile}`,
        sections: PROJECT_DEFINITION_SECTIONS,
        title: '项目定义',
      };
    default: {
      const never: never = scope;
      throw new KnowledgeError('invalid_argument', `未知 scope：${String(never)}`);
    }
  }
}

// ---------------------------------------------------------------------------
// 解析（读）
// ---------------------------------------------------------------------------

export interface StateSectionRead {
  /** 语义字段名（global/project 用 StateField，project-definition 用 ProjectDefinitionField） */
  readonly field: string;
  /** 文件里是否真实存在这个小节 */
  readonly present: boolean;
  /** 小节正文原文（保留 Markdown 列表符号等） */
  readonly raw: string | null;
  /** 正文的有序列表项（`- [x] ...` / `- ...`） */
  readonly items: readonly string[];
  /** 纯文本摘要 */
  readonly text: string;
}

export interface StateRead {
  readonly scope: StateScope;
  readonly path: string;
  readonly exists: boolean;
  readonly frontmatter: Record<string, unknown>;
  readonly hash: string | null;
  readonly updated: string | null;
  readonly sections: Record<string, StateSectionRead>;
  readonly missingSections: readonly string[];
  readonly warnings: readonly string[];
}

function splitItems(raw: string | null): string[] {
  if (!raw) return [];
  const out: string[] = [];
  for (const line of raw.split('\n')) {
    const m = /^\s*(?:[-*+]|\d+[.)])\s+(?:\[[ xX]\]\s*)?(.*)$/.exec(line);
    if (m && m[1]!.trim() !== '') out.push(m[1]!.trim());
  }
  return out;
}

/** 读取某个 scope 的状态文件。文件不存在时 exists: false，而不是抛错。 */
export async function readState(ctx: VaultContext, scope: StateScope): Promise<StateRead> {
  const target = resolveStateTarget(ctx, scope);
  const { absolutePath, relative } = ctx.guard.resolve(target.relativePath);
  const snapshot: FileSnapshot | null = await tryReadSnapshot(absolutePath);

  const sections: Record<string, StateSectionRead> = {};
  const missingSections: string[] = [];
  const warnings: string[] = [];

  const activeFields = Object.keys(target.sections);

  if (!snapshot) {
    for (const field of activeFields) {
      sections[field] = { field, present: false, raw: null, items: [], text: '' };
      missingSections.push(field);
    }
    warnings.push(`状态文件不存在：${relative}（写操作会自动创建）`);
    return {
      scope,
      path: relative,
      exists: false,
      frontmatter: {},
      hash: null,
      updated: null,
      sections,
      missingSections,
      warnings,
    };
  }

  const doc: ParsedMarkdown = parseMarkdown(snapshot.raw);
  for (const field of activeFields) {
    const raw = readSection(doc, target.sections[field]!);
    if (raw === null) {
      sections[field] = { field, present: false, raw: null, items: [], text: '' };
      missingSections.push(field);
    } else {
      sections[field] = {
        field,
        present: true,
        raw: raw.replace(/^\n+|\n+$/g, ''),
        items: splitItems(raw),
        text: toPlainText(raw, 400),
      };
    }
  }

  if (missingSections.length > 0) {
    warnings.push(
      `以下小节在 ${relative} 中缺失：${missingSections.join(', ')}。` +
        `调用 knowledge_update_state 时会自动补建，不会改动其它小节。`,
    );
  }

  const updatedRaw = doc.frontmatter['updated'];

  return {
    scope,
    path: relative,
    exists: true,
    frontmatter: doc.frontmatter,
    hash: snapshot.hash,
    updated: typeof updatedRaw === 'string' ? updatedRaw : null,
    sections,
    missingSections,
    warnings,
  };
}

// ---------------------------------------------------------------------------
// 更新（写）
// ---------------------------------------------------------------------------

/** 一个字段的新值。字符串按原样写入；数组渲染为 Markdown 列表项。 */
export type StateFieldValue = string | readonly string[] | { verified?: readonly string[]; notVerified?: readonly string[] };

export interface StateUpdateInput {
  readonly scope: StateScope;
  readonly fields: Partial<Record<string, StateFieldValue>>;
  /**
   * 更新 frontmatter 里的 `active_phase`（仅 global scope 有意义）。
   * 这是一个受控词表字段（机器读取），因此单独成参，不走 Markdown 小节。
   */
  readonly phase?: string;
  /**
   * `sections` 写到哪个**已知文件**（逻辑名，不是路径）。
   * 缺省与 scope 指向同一个文件；例如 `state.md` 里写 TASKS.md 时传 `'tasks'`。
   * 只接受枚举名 —— 模型依然无法指定任意路径。
   */
  readonly sectionsFile?: KnownProjectFile;
  /** 乐观并发锁：调用方读到的 hash。不一致则 conflict。 */
  readonly expectedHash?: string;
  /** 是否更新 frontmatter 里的 updated 字段（默认 true） */
  readonly touchUpdated?: boolean;
  /** 时间戳（测试可注入） */
  readonly now?: Date;
  /** 逐字段报告 */
  readonly note?: string;
}

export interface StateUpdateReport {
  readonly scope: StateScope;
  readonly path: string;
  /** 语义字段写到了哪个文件（scope 决定） */
  readonly fieldTargetPath: string;
  /** sections 写到了哪个文件（默认与 fieldTargetPath 相同） */
  readonly sectionsTargetPath: string;
  readonly created: boolean;
  readonly changedFields: readonly string[];
  readonly skippedFields: readonly string[];
  readonly createdSections: readonly string[];
  readonly bytesWritten: number;
  readonly newHash: string;
  readonly warnings: readonly string[];
}

function renderFieldValue(field: string, value: StateFieldValue): string {
  if (typeof value === 'string') return value.trim();

  if (Array.isArray(value)) {
    const items = value.map((v) => String(v).trim()).filter((v) => v !== '');
    if (items.length === 0) return '- 无。';
    return items.map((v) => `- ${v}`).join('\n');
  }

  // verification 对象
  const obj = value as { verified?: readonly string[]; notVerified?: readonly string[] };
  // 子标题必须是 ###（与 STATE.md 模板一致）。用 #### 会落进「## Verification」的
  // 下一级之外，形成错误的层级结构。
  const lines: string[] = ['### Verified 已验证', ''];
  const verified = (obj.verified ?? []).map((v) => String(v).trim()).filter(Boolean);
  lines.push(...(verified.length ? verified.map((v) => `- ${v}`) : ['- 无。']));
  lines.push('', '### Not Yet Verified 尚未验证', '');
  const notVerified = (obj.notVerified ?? []).map((v) => String(v).trim()).filter(Boolean);
  lines.push(...(notVerified.length ? notVerified.map((v) => `- ${v}`) : ['- 无。']));
  void field;
  return lines.join('\n');
}

/** 比较新旧小节正文，判断是否真的变化（规范化空行后再比）。 */
function sameContent(a: string | null, b: string | null): boolean {
  const norm = (s: string | null) => (s ?? '').replace(/\r\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
  return norm(a) === norm(b);
}

/**
 * 「删除该小节」的哨兵值。
 *
 * 为什么需要它：模板骨架会留下占位小节（如 `## Phase 1 — <阶段名>`），
 * 一旦真实内容以别的标题写进去，占位就变成一条孤儿。
 * 但 MCP 刻意**不提供**通用删除能力（delete / arbitrary_write 都在禁止清单里），
 * 因此用这个**受限的语义化哨兵**表达「移除这一个小节」：
 *   - 只能删小节，不能删文件
 *   - 目标范围仍由 scope / sectionsFile 决定，调用方依然给不出路径
 */
export const SECTION_DELETE_SENTINEL = '__KOS_DELETE_SECTION__';

/** 删除整个小节（标题行 + 正文）。 */
function removeSection(doc: ParsedMarkdown, spec: SectionSpec): { text: string } | null {
  const located = locateSection(doc, spec);
  if (!located) return null;

  // 往前吞掉紧邻的空行，避免留下多余空行
  let from = located.heading.lineIndex;
  while (from > 0 && doc.lines[from - 1]!.trim() === '') from--;
  return { text: [...doc.lines.slice(0, from), ...doc.lines.slice(located.bodyEnd)].join('\n') };
}

/**
 * 语义化更新 STATE。只动传入的字段，其余小节逐字保留。
 * 文件不存在 → 用模板骨架创建。
 */
export async function updateState(ctx: VaultContext, input: StateUpdateInput): Promise<StateUpdateReport> {
  requireWritable(ctx);

  // active_phase 是受控词表字段，只属于全局状态
  if (input.phase !== undefined && input.scope !== 'global') {
    throw new KnowledgeError('invalid_argument', 'phase 只支持 scope="global"（项目阶段请用 currentMilestone）', {
      scope: input.scope,
    });
  }

  const target = resolveStateTarget(ctx, input.scope);

  // --- 决定「固定语义字段」与「按标题写的小节」各写到哪里 -----------------
  // 默认两者同文件；给了 sectionsFile 就分开（例如 STATE 字段写 STATE.md，
  // 同时把 TASKS.md 的任务小节写到 TASKS.md）。
  const fieldRel = target.relativePath;
  const sectionsRel = input.sectionsFile
    ? resolveKnownProjectFile(ctx, input.sectionsFile).relativePath
    : fieldRel;

  const changedFields: string[] = [];
  const skippedFields: string[] = [];
  const createdSections: string[] = [];
  const warnings: string[] = [];

  const plan = new Map<string, { spec: SectionSpec; value: StateFieldValue }[]>();
  const push = (rel: string, spec: SectionSpec, value: StateFieldValue) => {
    const list = plan.get(rel) ?? [];
    list.push({ spec, value });
    plan.set(rel, list);
  };

  // 目标文件始终入计划：即使没有任何小节要改，`phase` / frontmatter 自愈仍需要它
  plan.set(fieldRel, plan.get(fieldRel) ?? []);

  for (const [field, value] of Object.entries(input.fields)) {
    if (value === undefined) continue;
    // 固定语义字段优先；否则尝试按标题解析（custom: 前缀）
    const spec = target.sections[field] ?? sectionSpecFor(field);
    if (!spec) {
      throw new KnowledgeError('invalid_argument', `scope "${input.scope}" 不支持字段 "${field}"`, {
        scope: input.scope,
        field,
        supported: Object.keys(target.sections),
      });
    }
    const isCustom = Boolean(sectionSpecFor(field));
    if (isCustom && input.sectionsFile && sectionsRel === fieldRel) {
      throw new KnowledgeError('invalid_argument', 'sectionsFile 与 scope 指向同一个文件，属重复指定', {
        sectionsFile: input.sectionsFile,
        path: sectionsRel,
      });
    }
    push(isCustom ? sectionsRel : fieldRel, spec, value);
  }

  // 固定字段所在的文件必须带骨架 frontmatter（type 等结构键）。
  // 纯 sections 目标文件不注入骨架 —— TASKS.md 已有自己的结构，不能覆盖。
  const resolved = new Map<
    string,
    { absolutePath: string; relative: string; existing: FileSnapshot | null; frontmatter: Record<string, string | number>; phase?: string }
  >();

  /** 文件是否已经有 frontmatter 块（`---` 开头）。 */
  const hasFrontmatterBlock = (text: string | null): boolean =>
    text !== null && /^\uFEFF?---\r?\n/.test(text);

  const skeleton = skeletonFor(input.scope, ctx, target);

  for (const rel of plan.keys()) {
    const { absolutePath, relative } = ctx.guard.resolve(rel);
    const existing = await tryReadSnapshot(absolutePath);
    if (existing && input.expectedHash !== undefined && rel === fieldRel && existing.hash !== input.expectedHash) {
      throw new KnowledgeError('conflict', `${relative} 已被其他 Agent 修改，拒绝覆盖`, {
        path: relative,
        expectedHash: input.expectedHash,
        actualHash: existing.hash,
      });
    }
    const isFieldTarget = rel === fieldRel;
    resolved.set(rel, {
      absolutePath,
      relative,
      existing,
      // 只有语义字段所在的文件注入结构键；sections 目标文件（TASKS.md）保持自己的结构。
      frontmatter: isFieldTarget ? skeleton.frontmatter : {},
      phase: isFieldTarget ? input.phase : undefined,
    });
  }

  const now = input.now ?? new Date();
  const isEmptyFmValue = (v: unknown): boolean => v === null || v === undefined || v === '';
  let primaryPath = fieldRel;
  let primaryCreated = false;
  let totalBytes = 0;
  let primaryHash: string | null = null;

  /** 只取骨架的 frontmatter 块（含闭合的 `---` 与一个空行）。 */
  const skeletonFrontmatterBlock = (): string => {
    const fmLines: string[] = [];
    const text = skeleton.text.replace(/\r\n/g, '\n');
    let started = false;
    for (const line of text.split('\n')) {
      if (!started) {
        if (line.trim() !== '---') continue;
        started = true;
        fmLines.push('---');
        continue;
      }
      fmLines.push(line);
      if (line.trim() === '---') break;
    }
    return fmLines.join('\n') + '\n\n';
  };

  for (const [rel, items] of plan) {
    const entry = resolved.get(rel)!;
    const isFieldTarget = rel === fieldRel;

    /**
     * 基础文本的选择：
     *   - 文件不存在                      → 用骨架
     *   - 文件存在但没有 frontmatter 块   → 在原文之前补上骨架的 frontmatter
     *     （已有但缺结构键的文件必须能被自愈，否则 type 等键永远补不上）
     *   - 文件存在且有 frontmatter        → 原样使用，只动传入的小节
     */
    let baseText: string;
    if (!entry.existing) {
      baseText = isFieldTarget ? skeleton.text : '';
    } else if (isFieldTarget && !hasFrontmatterBlock(entry.existing.raw)) {
      baseText = skeletonFrontmatterBlock() + entry.existing.raw;
    } else {
      baseText = entry.existing.raw;
    }

    let doc = parseMarkdown(baseText);

    for (const item of items) {
      // 删除哨兵：移除整个小节（仅一个小节，不是文件）
      if (item.value === SECTION_DELETE_SENTINEL) {
        const result = removeSection(doc, item.spec);
        if (!result) {
          skippedFields.push(item.spec.field);
          continue;
        }
        doc = parseMarkdown(result.text);
        changedFields.push(item.spec.field);
        continue;
      }

      const rendered = renderFieldValue(item.spec.field, item.value);
      const current = readSection(doc, item.spec);
      if (sameContent(current, rendered)) {
        skippedFields.push(item.spec.field);
        continue;
      }
      const result = replaceSection(doc, item.spec, rendered);
      doc = parseMarkdown(result.text);
      changedFields.push(item.spec.field);
      if (result.created) createdSections.push(item.spec.canonicalHeading);
    }

    /**
     * 只补齐**缺失或为空**的结构性 frontmatter 键。
     *
     * 为什么不无脑 upsert 全部结构性键：
     * 那会重写已存在键的原始写法（例如把用户写的 `active_project: "demo-project"`
     * 改写成不带引号的 `active_project: demo-project`）。规范要求「尽量保持原来的
     * Markdown 风格，不要为了格式统一而重写整个文件」，因此既有键一律不动。
     *
     * 但「键存在、值为空」不算已填写 —— 例如 `active_project:` 空着属于待填状态，
     * 必须能被填上，否则 active_project 会永远保持空值。
     */
    const mergedFrontmatter: Record<string, string | number> = {};
    for (const [key, value] of Object.entries(entry.frontmatter)) {
      if (!(key in doc.frontmatter) || isEmptyFmValue(doc.frontmatter[key])) {
        mergedFrontmatter[key] = value;
      }
    }
    if (input.touchUpdated !== false && Object.keys(entry.frontmatter).length > 0) {
      mergedFrontmatter['updated'] = formatDate(now);
    }

    // active_phase 是受控词表字段：总是以调用方给的最新值为准
    if (entry.phase !== undefined) {
      if (input.scope !== 'global') {
        throw new KnowledgeError('invalid_argument', 'phase 只支持 scope="global"（项目阶段请用 currentMilestone）', {
          scope: input.scope,
        });
      }
      mergedFrontmatter['active_phase'] = entry.phase;
    }

    let finalText = doc.lines.join('\n');
    if (Object.keys(mergedFrontmatter).length > 0) {
      finalText = upsertFrontmatter(finalText, mergedFrontmatter);
    }

    // 写不写的判据是**最终文本是否与磁盘一致**，而不是「有没有小节变化」，
    // 否则「小节没变、但 frontmatter 需要自愈」会被误判为无需写入。
    const nothingChanged = entry.existing !== null && finalText.trim() === entry.existing.raw.trim();
    if (nothingChanged) {
      warnings.push(`${entry.relative} 与磁盘内容一致，未写入。`);
      if (rel === fieldRel) primaryHash = entry.existing!.hash;
      continue;
    }

    const write = await atomicWrite(entry.absolutePath, {
      content: finalText,
      newline: entry.existing?.newline ?? '\n',
      bom: entry.existing?.bom ?? false,
      expectedHash: rel === fieldRel ? input.expectedHash : undefined,
      conflictLabel: `${entry.relative} 在写入前被其他 Agent 修改，已中止以保护对方修改`,
    });
    totalBytes += write.bytesWritten;
    if (!entry.existing) primaryCreated = true;
    if (rel === fieldRel) {
      primaryHash = write.hash;
      primaryPath = entry.relative;
    }
  }

  return {
    scope: input.scope,
    path: primaryPath,
    fieldTargetPath: fieldRel,
    sectionsTargetPath: sectionsRel,
    created: primaryCreated,
    changedFields,
    skippedFields,
    createdSections,
    bytesWritten: totalBytes,
    newHash: primaryHash ?? '',
    warnings,
  };
}

/** 生成新 STATE / PROJECT 文件的骨架（贴合规范标题 + 既有 Vault 的中文习惯）。 */
function skeletonFor(scope: StateScope, ctx: VaultContext, target: StateFileTarget): { text: string; frontmatter: Record<string, string | number> } {
  const today = formatDate(new Date());
  const projectId = ctx.identity.projectId ?? '';
  const projectName = ctx.identity.projectName ?? projectId;

  if (scope === 'project-definition') {
    return {
      frontmatter: { created: today, updated: today },
      text: `---
type: project
status: active
---

# ${projectName}

> 本文件回答：这个项目为什么存在、覆盖什么、明确不覆盖什么。
> 它不是任务清单（那是 TASKS.md），也不是进度指针（那是 STATE.md）。

**位置：** \`${target.relativePath}\`

## Objective 目标

（待补充：本项目的存在理由与要产出的结果。）

## Scope 范围

- （待补充）

## Success Criteria 成功标准

- [ ] （待补充）

## Non-Goals 非目标

- （待补充）

## Constraints 约束

- （待补充）

## Related Artifacts 相关产物

- \`${projectId}\` — 代码仓库
`,
    };
  }

  if (scope === 'project') {
    return {
      frontmatter: { project: projectName, phase: '' },
      text: `---
type: project-state
---

# 项目状态

> **接力棒。** 只回答「我们在哪、下一步做什么」。
> 可以随意覆盖。历史进 LOG.md，完整任务地图进 TASKS.md。

**位置：** \`${target.relativePath}\`

## Current Objective 当前目标

（待补充）

## Current Milestone 当前里程碑

（待补充）

## Completed 已完成

- 无。

## In Progress 进行中

- 无。

## Next Action 下一步行动

（待补充：必须具体到另一个 Agent 能不询问用户就直接开始。）

## Verification 验证

### Verified 已验证

- 无。

### Not Yet Verified 尚未验证

- 无。

## Blockers 阻塞项

- 无。

## Handoff Notes 交接说明

（待补充）

## Important Decisions 重要决策

- 无。
`,
    };
  }

  return {
    frontmatter: { active_project: projectId, active_phase: '' },
    text: `---
type: global-state
---

# 当前状态

> 本文件只回答一个问题：**这个 Vault 现在最重要的工作状态是什么？**
> 保持简短。细节属于项目自己的 STATE.md，历史属于 LOG.md。

## Active Project 活动项目

${projectId}

## Objective 目标

（待补充）

## Current Milestone 当前里程碑

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
`,
  };
}

export function formatDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export function formatTimestamp(d: Date): string {
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${formatDate(d)} ${hh}:${mm}`;
}
