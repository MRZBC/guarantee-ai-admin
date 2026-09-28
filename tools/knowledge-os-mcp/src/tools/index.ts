/**
 * tools/index.ts — MCP 工具注册。
 *
 * 第一版对外暴露 9 个工具：
 *   knowledge_health            只读诊断
 *   knowledge_resolve           解析 project / vault 身份
 *   knowledge_state             读取状态
 *   knowledge_search            本地检索
 *   knowledge_read              读取 Vault 内某一页
 *   knowledge_update_state      语义化更新状态
 *   knowledge_append_log        追加历史日志
 *   knowledge_create_decision   创建 / 合并决策
 *   knowledge_upsert_wiki       新建 / 增补长期知识
 *   knowledge_bootstrap         幂等补齐缺失的 Vault 基础设施（只创建缺失项）
 *
 * **刻意不提供**：delete / move / arbitrary_write / execute_command。
 * 模型永远拿不到「写任意路径」的能力，只能表达语义意图。
 */

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { KnowledgeError, toKnowledgeError } from '../errors.js';
import { healthCheck } from '../health.js';
import { openVault, bootstrapVaultInfrastructure } from '../vault.js';
import {
  readState,
  updateState,
  STATE_SCOPES,
  customSectionFieldKey,
  type StateField,
  type StateFieldValue,
} from '../state.js';
import { searchVault, SEARCH_TYPES, type SearchType } from '../search.js';
import { readVaultPage } from '../read.js';
import { appendLog, LOG_TYPES } from '../log.js';
import { createDecision } from '../decisions.js';
import { upsertWiki, WIKI_STATUS_VALUES } from '../wiki.js';
import { WIKI_TYPES, type WikiType } from '../layout.js';
import { loadConfig } from '../config.js';
import { checkIdentity } from '../identity.js';
import { displayPath } from '../config.js';
import { resolveStateTarget } from '../state.js';

const SERVER_VERSION = '1.0.0';

/** 统一的工具返回值。结构化内容优先，同时给一份人类可读文本。 */
interface ToolOutcome {
  readonly structured: Record<string, unknown>;
  readonly text?: string;
  readonly isError?: boolean;
}

function ok(structured: Record<string, unknown>, text?: string): ToolOutcome {
  return { structured, text: text ?? '```json\n' + JSON.stringify(structured, null, 2) + '\n```' };
}

function failure(err: unknown): ToolOutcome {
  const ke = toKnowledgeError(err);
  return {
    structured: ke.toJSON() as unknown as Record<string, unknown>,
    text: `❌ ${ke.code}: ${ke.message}\n\n\`\`\`json\n${JSON.stringify(ke.details, null, 2)}\n\`\`\``,
    isError: true,
  };
}

/** 把 ToolOutcome 转成 MCP CallToolResult。 */
function toCallToolResult(outcome: ToolOutcome) {
  return {
    content: [{ type: 'text' as const, text: outcome.text ?? JSON.stringify(outcome.structured) }],
    structuredContent: outcome.structured,
    ...(outcome.isError ? { isError: true } : {}),
  };
}

/** 每个工具都经过它：统一错误处理，保证异常不会以「半成功」的形式返回。 */
async function run(fn: () => Promise<ToolOutcome>) {
  try {
    return toCallToolResult(await fn());
  } catch (err) {
    return toCallToolResult(failure(err));
  }
}

/** 只读工具允许「身份不一致」时使用 —— 但会明确标注。 */
function withIdentityNotice(structured: Record<string, unknown>, identityStatus: string, extra: string[] = []): Record<string, unknown> {
  if (identityStatus === 'ok') return { ...structured, ...(extra.length ? { warnings: extra } : {}) };
  return {
    ...structured,
    identityStatus,
    readOnly: true,
    warnings: [
      ...extra,
      '三层身份校验未通过：本次只做了读取。所有写操作都会被拒绝（防止写进错误的 Vault）。',
    ],
  };
}

export function registerTools(server: McpServer): void {
  // -----------------------------------------------------------------------
  // 1. knowledge_health
  // -----------------------------------------------------------------------
  server.registerTool(
    'knowledge_health',
    {
      title: 'Knowledge OS 健康检查',
      description:
        '只读诊断：检查 project 配置、vault 配置、Vault 是否存在与可访问、三层 ID 是否匹配、State/Project State 与必要目录是否齐全，并返回 warnings 与下一步建议。不修改任何文件。任何其它操作之前应先调用它。',
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async () =>
      run(async () => {
        const report = await healthCheck();
        return ok(report as unknown as Record<string, unknown>);
      }),
  );

  // -----------------------------------------------------------------------
  // 2. knowledge_resolve
  // -----------------------------------------------------------------------
  server.registerTool(
    'knowledge_resolve',
    {
      title: '解析项目与 Vault 身份',
      description:
        '读取 .agent/project.yaml 与 .agent/vault.local.yaml，验证 VAULT_ID.md，返回 projectId / projectName / vaultId / vaultPath 与三层校验结果。Agent 不应自己拼接 Vault 路径 —— 用这个工具拿权威路径。',
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async () =>
      run(async () => {
        const config = loadConfig();
        const identity = await checkIdentity(config);
        return ok({
          ok: true,
          projectRoot: displayPath(config.projectRoot),
          projectConfigPath: config.projectConfigPath ? displayPath(config.projectConfigPath) : null,
          vaultConfigPath: config.vaultConfigPath ? displayPath(config.vaultConfigPath) : null,
          projectId: identity.projectId,
          projectName: identity.projectName,
          vaultId: identity.vaultId,
          vaultPath: identity.vaultPath ? displayPath(identity.vaultPath) : null,
          vaultPathSource: config.vault?.source ?? null,
          identity: {
            status: identity.status,
            canWrite: identity.canWrite,
            identityFile: identity.identityFile,
            vaultFileProjectId: identity.vaultFileProjectId,
            vaultFileProjectName: identity.vaultFileProjectName,
            mismatches: identity.mismatches,
          },
          layout: config.layout,
          projectWorkspace: identity.projectId
            ? `${config.layout.active}/${identity.projectId}`
            : null,
          warnings: [...config.warnings, ...identity.notes],
        });
      }),
  );

  // -----------------------------------------------------------------------
  // 3. knowledge_state
  // -----------------------------------------------------------------------
  server.registerTool(
    'knowledge_state',
    {
      title: '读取项目持久化状态',
      description:
        '读取 Knowledge OS 中的当前状态：全局 STATE.md 与项目 STATE.md（以及 PROJECT.md 的目标小节）。包含 Global State、Project Definition、Project State、Current Milestone、Completed、In Progress、Next Action、Blockers、Verification、Important Decisions、Handoff Notes。返回的 hash 可用于 knowledge_update_state 的乐观并发锁。',
      inputSchema: {
        scopes: z
          .array(z.enum(STATE_SCOPES as unknown as [string, ...string[]]))
          .optional()
          .describe('要读取的 scope，默认全部：global / project / project-definition'),
      },
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async ({ scopes }) =>
      run(async () => {
        const ctx = await openVault();
        const wanted = (scopes && scopes.length > 0 ? scopes : STATE_SCOPES) as unknown as typeof STATE_SCOPES;
        const reads = [];
        for (const scope of wanted) {
          reads.push(await readState(ctx, scope));
        }
        return ok(
          withIdentityNotice(
            {
              ok: true,
              projectId: ctx.identity.projectId,
              projectName: ctx.identity.projectName,
              vaultPath: displayPath(ctx.vaultPath),
              states: reads,
              identityStatus: ctx.identity.status,
            },
            ctx.identity.status,
            [...ctx.config.warnings, ...ctx.identity.notes],
          ),
        );
      }),
  );

  // -----------------------------------------------------------------------
  // 4. knowledge_search
  // -----------------------------------------------------------------------
  server.registerTool(
    'knowledge_search',
    {
      title: '检索 Vault 知识',
      description:
        '在当前项目的 Obsidian Vault 内做本地检索（文件名 / 标题 / heading / frontmatter / 正文 + 简单 relevance score）。不依赖 embedding、向量库或 RAG。先搜再读，不要一次加载整个 Vault。',
      inputSchema: {
        query: z.string().min(1).describe('查询词，例如 "Spring AI memory" 或 "会话记忆"'),
        types: z
          .array(z.enum(SEARCH_TYPES as unknown as [string, ...string[]]))
          .optional()
          .describe('限定结果类型：wiki / decision / project / state / source / raw / system / any'),
        limit: z.number().int().min(1).max(100).optional().describe('返回条数上限，默认 10'),
        paths: z
          .array(z.string())
          .optional()
          .describe('限定在若干 Vault 内相对目录里搜索，例如 ["03_Wiki/Decisions"]'),
      },
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async ({ query, types, limit, paths }) =>
      run(async () => {
        const ctx = await openVault();
        const report = await searchVault(ctx, {
          query,
          types: types as readonly SearchType[] | undefined,
          limit,
          paths,
        });
        return ok(
          withIdentityNotice(
            { ok: true, ...report, vaultPath: displayPath(ctx.vaultPath) },
            ctx.identity.status,
          ),
        );
      }),
  );

  // -----------------------------------------------------------------------
  // 5. knowledge_read
  // -----------------------------------------------------------------------
  server.registerTool(
    'knowledge_read',
    {
      title: '读取 Vault 内页面',
      description:
        '按 Vault 内**相对路径**读取一个 Markdown 页面，返回 frontmatter、正文、type 与 path。拒绝绝对路径、 ../ 目录穿越、UNC 路径与符号链接逃逸，禁止访问 Vault 之外。',
      inputSchema: {
        path: z.string().min(1).describe('Vault 内相对路径，例如 "03_Wiki/Technologies/Spring AI Memory.md"'),
      },
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async ({ path }) =>
      run(async () => {
        const ctx = await openVault();
        const page = await readVaultPage(ctx, path);
        return ok({ ok: true, ...page });
      }),
  );

  // -----------------------------------------------------------------------
  // 6. knowledge_update_state
  // -----------------------------------------------------------------------
  server.registerTool(
    'knowledge_update_state',
    {
      title: '更新状态（语义化）',
      description:
        '语义化更新 STATE：只需给出变化的字段，Server 自己决定写哪个文件（<Vault>/STATE.md 与 <Vault>/04_Work/Active/<project-id>/STATE.md），并**只替换对应小节**，其余内容逐字保留。未传入的字段绝不被触碰。文件不存在时按模板骨架创建。不提供任意路径写入能力。',
      inputSchema: {
        scope: z.enum(STATE_SCOPES as unknown as [string, ...string[]]).describe('global / project / project-definition'),
        currentObjective: z.string().optional().describe('当前目标'),
        currentMilestone: z.string().optional().describe('当前里程碑 / 阶段'),
        activeProject: z.string().optional().describe('活动项目（仅 global scope）'),
        completed: z.array(z.string()).optional().describe('已完成项（覆盖该小节，其余小节不受影响）'),
        inProgress: z.array(z.string()).optional().describe('进行中项'),
        nextAction: z.string().optional().describe('下一步行动：必须具体到另一个 Agent 能直接开始'),
        blockers: z.array(z.string()).optional().describe('阻塞项，空数组表示「无」'),
        phase: z
          .string()
          .optional()
          .describe(
            'global scope 的 active_phase（受控词表，前端可直接读）：idle / discovery / planning / implementation / verification / polishing / blocked / archived',
          ),
        verification: z
          .object({ verified: z.array(z.string()).optional(), notVerified: z.array(z.string()).optional() })
          .optional()
          .describe('验证情况：已验证 / 尚未验证'),
        handoffNotes: z.string().optional().describe('交接说明'),
        importantDecisions: z.array(z.string()).optional().describe('重要决策（通常写 [[决策 - 标题]] 链接）'),
        sections: z
          .record(z.string(), z.string())
          .optional()
          .describe(
            '按小节标题直接写入 Markdown（键=小节名，中英皆可，例如 "Phase 1 — 交付" / "Scope 范围" / "architecture"；值=Markdown 正文）。' +
              '只替换同名小节，绝不整文件重写，也**不接受任何文件路径** —— 写哪个文件仍由 scope 决定。',
          ),
        sectionsFile: z
          .enum(['tasks'])
          .optional()
          .describe(
            '仅当要用 sections 写「非 scope 所属文件」时指定（逻辑名，不是路径）。' +
              '目前只支持 "tasks"：把任务小节写进 04_Work/Active/<project-id>/TASKS.md，' +
              '而固定语义字段仍写 scope 所属的 STATE.md。',
          ),
        expectedHash: z
          .string()
          .optional()
          .describe('乐观并发锁：knowledge_state 返回的 hash。若磁盘已被其它 Agent 改动则返回 conflict，不会覆盖'),
      },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
    },
    async (args) =>
      run(async () => {
        const ctx = await openVault();
        const target = resolveStateTarget(ctx, args.scope as never);
        const fields: Partial<Record<string, StateFieldValue>> = {};
        const map: Array<[string, string]> = [
          ['activeProject', 'activeProject'],
          ['currentObjective', 'objective'],
          ['currentMilestone', 'currentMilestone'],
          ['completed', 'completed'],
          ['inProgress', 'inProgress'],
          ['nextAction', 'nextAction'],
          ['blockers', 'blockers'],
          ['verification', 'verification'],
          ['handoffNotes', 'handoffNotes'],
          ['importantDecisions', 'importantDecisions'],
        ];
        const skipped: string[] = [];
        for (const [inputKey, field] of map) {
          const value = (args as Record<string, unknown>)[inputKey];
          if (value === undefined) continue;
          if (!target.sections[field]) {
            skipped.push(`${inputKey}（scope "${args.scope}" 不支持该字段，已忽略）`);
            continue;
          }
          fields[field] = value as StateFieldValue;
        }

        // 按标题直接写小节（键只用于生成 `## <键>`，仍然不含路径）
        const extraSections = args.sections;
        if (extraSections !== undefined) {
          const entries = Object.entries(extraSections);
          if (entries.length > 20) {
            throw new KnowledgeError('invalid_argument', 'sections 一次最多 20 个小节', {
              count: entries.length,
            });
          }
          for (const [heading, body] of entries) {
            if (/[\r\n]/.test(heading)) {
              throw new KnowledgeError('invalid_argument', 'sections 的键不能包含换行（它会被当作 Markdown 标题）', {
                heading,
              });
            }
            const trimmed = heading.trim();
            if (trimmed === '') {
              throw new KnowledgeError('invalid_argument', 'sections 的键不能为空', {});
            }
            // 键只用于生成 `## <键>` 标题：
            //   - 不能含换行（否则能注入额外标题）
            //   - 不能以 # 开头（否则会变成带级别的标题）
            //   - 不能含反斜杠（\r / 转义类字符会破坏 Markdown）
            // 正弦杠是合法标题字符（例如「未计划 / 新发现」），因此允许。
            if (/[\\]/.test(trimmed) || trimmed.startsWith('#')) {
              throw new KnowledgeError('invalid_argument', 'sections 的键不能包含 "\\" 或以 "#" 开头', { heading });
            }
            fields[customSectionFieldKey(trimmed)] = body;
          }
        }

        if (Object.keys(fields).length === 0 && args.phase === undefined) {
          throw new KnowledgeError('invalid_argument', '没有提供任何要更新的字段', {
            supported: [
              'currentObjective', 'currentMilestone', 'activeProject', 'completed', 'inProgress',
              'nextAction', 'blockers', 'phase', 'verification', 'handoffNotes', 'importantDecisions',
              'sections（仅 project-definition）',
            ],
          });
        }

        const report = await updateState(ctx, {
          scope: args.scope as never,
          fields,
          phase: args.phase,
          sectionsFile: args.sectionsFile as never,
          expectedHash: args.expectedHash,
        });

        return ok({
          ok: true,
          ...report,
          skippedInputs: skipped,
          hint: '请在完成后调用 knowledge_append_log 记录历史。如果产生了长期决策或长期知识，再调用 knowledge_create_decision / knowledge_upsert_wiki。',
        });
      }),
  );

  // -----------------------------------------------------------------------
  // 7. knowledge_append_log
  // -----------------------------------------------------------------------
  server.registerTool(
    'knowledge_append_log',
    {
      title: '追加活动日志',
      description:
        '向 <Vault>/LOG.md **追加**一条历史记录（append-only，永不覆盖历史）。用于记录一次工作做完/做到哪里、验证了什么、下一步是什么。',
      inputSchema: {
        type: z.enum(LOG_TYPES as unknown as [string, ...string[]]).describe('WORK / RESEARCH / DECISION / MAINTENANCE / MIGRATION / HANDOFF'),
        project: z.string().optional().describe('项目标识，缺省用当前 project.id'),
        summary: z.string().min(1).describe('这次工作做了什么（一到三句）'),
        completed: z.array(z.string()).optional(),
        verification: z.array(z.string()).optional(),
        decisions: z.array(z.string()).optional(),
        stateChange: z
          .object({ before: z.string().optional(), after: z.string().optional() })
          .optional()
          .describe('状态变化：之前 / 之后'),
        blockers: z.array(z.string()).optional(),
        nextAction: z.string().optional(),
      },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
    },
    async (args) =>
      run(async () => {
        const ctx = await openVault();
        const report = await appendLog(ctx, {
          type: args.type as never,
          project: args.project,
          summary: args.summary,
          completed: args.completed,
          verification: args.verification,
          decisions: args.decisions,
          stateChange: args.stateChange,
          blockers: args.blockers,
          nextAction: args.nextAction,
        });
        return ok({ ok: true, ...report });
      }),
  );

  // -----------------------------------------------------------------------
  // 8. knowledge_create_decision
  // -----------------------------------------------------------------------
  server.registerTool(
    'knowledge_create_decision',
    {
      title: '创建 / 合并决策',
      description:
        '把具备长期影响的选择（架构 / 技术 / 数据模型 / 工作流 / 产品行为）持久化为 <Vault>/03_Wiki/Decisions/决策 - <标题>.md。路径由 Server 决定。若同名 Decision 已存在则**合并**：只填空白小节，绝不覆盖既有结论，冲突会被显式报告。',
      inputSchema: {
        title: z.string().min(1).describe('决策标题，例如 "采用 Redis-backed 会话记忆"'),
        decision: z.string().min(1).describe('决定了什么（一两句）'),
        why: z.string().min(1).describe('为什么这样决定'),
        context: z.string().optional().describe('当时的背景'),
        alternatives: z.array(z.string()).optional().describe('备选方案'),
        constraints: z.array(z.string()).optional().describe('约束'),
        consequences: z.array(z.string()).optional().describe('后果（正负都写，诚实写代价）'),
        revisitConditions: z.array(z.string()).optional().describe('什么条件下应重新评估'),
        related: z.array(z.string()).optional().describe('相关页面（写标题或 [[链接]]）'),
        status: z.enum(['proposed', 'active', 'superseded', 'revisit']).optional(),
      },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
    },
    async (args) =>
      run(async () => {
        const ctx = await openVault();
        const report = await createDecision(ctx, {
          title: args.title,
          decision: args.decision,
          why: args.why,
          context: args.context,
          alternatives: args.alternatives,
          constraints: args.constraints,
          consequences: args.consequences,
          revisitConditions: args.revisitConditions,
          related: args.related,
          status: args.status,
        });
        return ok({ ok: true, ...report });
      }),
  );

  // -----------------------------------------------------------------------
  // 9. knowledge_upsert_wiki
  // -----------------------------------------------------------------------
  server.registerTool(
    'knowledge_upsert_wiki',
    {
      title: '沉淀长期知识',
      description:
        '把具备长期价值的信息写入 03_Wiki/。按 type 自动决定目录：concept→Concepts、technology→Technologies、project→Projects、lesson→Lessons、source→Sources、synthesis→Syntheses、person→People、company→Companies、work→Works。禁止自行传路径。页面已存在时**追加**带日期的更新小节，绝不覆盖既有内容。不要把普通聊天过程倒进 Wiki。',
      inputSchema: {
        type: z.enum(WIKI_TYPES as unknown as [string, ...string[]]).describe('页面类型，决定存放目录'),
        title: z.string().min(1).describe('页面标题（也是文件名，需唯一）'),
        content: z.string().min(1).describe('Markdown 正文'),
        related: z.array(z.string()).optional().describe('相关页面'),
        tags: z.array(z.string()).optional(),
        status: z.enum(WIKI_STATUS_VALUES as unknown as [string, ...string[]]).optional(),
        updateHeading: z.string().optional().describe('已存在页面时，新增小节的标题'),
      },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
    },
    async (args) =>
      run(async () => {
        const ctx = await openVault();
        const report = await upsertWiki(ctx, {
          type: args.type as WikiType,
          title: args.title,
          content: args.content,
          related: args.related,
          tags: args.tags,
          status: args.status as never,
          updateHeading: args.updateHeading,
        });
        return ok({ ok: true, ...report });
      }),
  );

  // -----------------------------------------------------------------------
  // 10. knowledge_bootstrap
  // -----------------------------------------------------------------------
  server.registerTool(
    'knowledge_bootstrap',
    {
      title: '补齐 Vault 最小基础设施（幂等）',
      description:
        '幂等地创建 Vault 中**缺失**的最小基础设施：VAULT_ID.md、04_Work/Active/<project-id>/ 工作区与 decisions/artifacts 子目录、TASKS.md。绝不删除、迁移、重命名或覆盖任何既有文件；已存在的一律跳过并报告。默认 dryRun=true，只列出将要创建什么。要求三层身份校验通过；唯一的例外是 Vault 里还没有 VAULT_ID.md 且配置两层 id 一致时，允许只创建该身份文件（否则会出现「没有身份文件就不许写、不许写就永远建不出身份文件」的死锁）。',
      inputSchema: {
        dryRun: z.boolean().optional().describe('默认 true：只报告不写入。设为 false 才真正创建缺失项'),
      },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    async ({ dryRun }) =>
      run(async () => {
        const ctx = await openVault();
        const apply = dryRun === false;
        const result = await bootstrapVaultInfrastructure(ctx, { apply });
        return ok({
          ok: true,
          dryRun: !apply,
          ...result,
          hint:
            result.mode === 'initialize-identity'
              ? '身份文件已建立。请再调用一次 knowledge_bootstrap 补齐工作区与 TASKS.md，然后用 knowledge_update_state 写入 PROJECT.md 与 STATE.md。'
              : apply
                ? '已补齐缺失项。接下来用 knowledge_update_state 创建 / 更新 PROJECT.md 与 STATE.md。'
                : '这是预演结果，未写入任何文件。确认无误后传 dryRun=false 执行。',
        });
      }),
  );
}

export { SERVER_VERSION };
