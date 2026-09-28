/**
 * config.ts — 定位项目根、读取 .agent/project.yaml 与 .agent/vault.local.yaml。
 *
 * 严格约束（对应规范第 12 节）：
 *   本模块**只**读 `.agent/vault.local.yaml` 给出的 Vault 路径。
 *   不扫描磁盘、不遍历 C:/ D:/、不搜索其它 Obsidian Vault、不猜测目录。
 *   配置缺失 → 抛明确错误，而不是猜一个能用的路径。
 *
 * 项目根解析顺序：
 *   1. 显式参数 projectRoot
 *   2. 环境变量 KNOWLEDGE_PROJECT_ROOT
 *   3. 从本模块目录逐级向上，找到第一个含 .agent/project.yaml 的目录
 *   4. 环境变量 KNOWLEDGE_VAULT_PATH（仅在没有 vault.local.yaml 时作为兜底，
 *      便于临时/容器环境；正常使用不应依赖它）
 */

import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { KnowledgeError } from './errors.js';
import { resolveLayout, type Layout } from './layout.js';
import { getString, parseYaml, type YamlObject } from './yaml.js';

export const AGENT_DIR = '.agent';
export const PROJECT_CONFIG_FILE = 'project.yaml';
export const VAULT_CONFIG_FILE = 'vault.local.yaml';
export const VAULT_CONFIG_EXAMPLE_FILE = 'vault.local.yaml.example';

export interface ProjectIdentity {
  readonly id: string;
  readonly name: string;
}

export interface VaultBinding {
  readonly id: string;
  /** 绝对路径，来自 vault.local.yaml（或 KNOWLEDGE_VAULT_PATH 兜底） */
  readonly path: string;
  /** 路径来源，用于诊断：哪一条配置让 Server 找到了 Vault */
  readonly source: 'vault.local.yaml' | 'env:KNOWLEDGE_VAULT_PATH';
}

export interface KnowledgeConfig {
  readonly projectRoot: string;
  readonly agentDir: string;
  readonly projectConfigPath: string | null;
  readonly vaultConfigPath: string | null;
  readonly project: ProjectIdentity | null;
  readonly vault: VaultBinding | null;
  readonly layout: Layout;
  /** 非致命问题：例如覆盖了 layout、用了 env 兜底等 */
  readonly warnings: readonly string[];
  /** 致命问题导致 project/vault 为 null 时的说明 */
  readonly notes: readonly string[];
}

/** 从起始目录向上找项目根（含 .agent/project.yaml 的最近祖先）。 */
export function findProjectRoot(startDir: string): string | null {
  let cur = path.resolve(startDir);
  for (;;) {
    if (fs.existsSync(path.join(cur, AGENT_DIR, PROJECT_CONFIG_FILE))) return cur;
    const parent = path.dirname(cur);
    if (parent === cur) return null;
    cur = parent;
  }
}

/** 本模块自身位置 → 期望的默认项目根（tools/knowledge-os-mcp/dist|src → 仓库根）。 */
export function defaultProjectRootFromModule(): string | null {
  try {
    const here = path.dirname(fileURLToPath(import.meta.url));
    return findProjectRoot(here);
  } catch {
    return null;
  }
}

export interface LoadConfigOptions {
  /** 显式指定项目根（测试用） */
  projectRoot?: string;
  /** 显式指定 vault.local.yaml 路径（测试用） */
  vaultConfigPath?: string;
  /** 不读环境变量（测试用，避免宿主机环境干扰结论） */
  ignoreEnv?: boolean;
}

/** 把 Windows 风格路径统一为正斜杠，便于日志与展示（不改语义）。 */
export function displayPath(p: string): string {
  return process.platform === 'win32' ? p.replace(/\\/g, '/') : p;
}

export function loadConfig(options: LoadConfigOptions = {}): KnowledgeConfig {
  const warnings: string[] = [];
  const notes: string[] = [];

  // --- 1. 定位项目根 ----------------------------------------------------
  let projectRoot: string | null = null;
  if (options.projectRoot) {
    projectRoot = path.resolve(options.projectRoot);
  } else if (!options.ignoreEnv && process.env['KNOWLEDGE_PROJECT_ROOT']) {
    projectRoot = path.resolve(process.env['KNOWLEDGE_PROJECT_ROOT']!);
  } else {
    projectRoot = defaultProjectRootFromModule();
  }

  if (!projectRoot) {
    throw new KnowledgeError(
      'project_config_missing',
      '无法定位项目根：从 MCP Server 所在目录向上找不到含 .agent/project.yaml 的目录。' +
        '请确认 .agent/project.yaml 存在，或设置 KNOWLEDGE_PROJECT_ROOT。',
      { searchedFrom: displayPath(defaultProjectRootFromModule() ?? process.cwd()) },
    );
  }

  const agentDir = path.join(projectRoot, AGENT_DIR);
  const projectConfigPath = path.join(agentDir, PROJECT_CONFIG_FILE);
  const vaultConfigPath = options.vaultConfigPath
    ? path.resolve(options.vaultConfigPath)
    : path.join(agentDir, VAULT_CONFIG_FILE);

  // --- 2. project.yaml --------------------------------------------------
  let project: ProjectIdentity | null = null;
  if (!fs.existsSync(projectConfigPath)) {
    notes.push(
      `缺少 ${displayPath(projectConfigPath)}。项目身份无法确定，所有写操作都会被拒绝。` +
        `请从 tools/knowledge-os-mcp/README.md 的「项目身份」一节创建该文件。`,
    );
  } else {
    const doc = parseYaml(readText(projectConfigPath), displayPath(projectConfigPath));
    project = readProjectIdentity(doc, projectConfigPath, options);
  }

  // --- 3. vault.local.yaml ---------------------------------------------
  const vault = readVaultBinding({
    vaultConfigPath,
    projectRoot,
    warnings,
    notes,
    ignoreEnv: options.ignoreEnv === true,
  });

  // --- 4. layout 覆盖 ---------------------------------------------------
  let layout: Layout;
  const layoutOverrides = vault?.layoutOverrides;
  try {
    layout = resolveLayout(layoutOverrides);
    if (layoutOverrides && Object.keys(layoutOverrides).length > 0) {
      warnings.push(
        `vault.local.yaml 覆盖了 layout：${Object.keys(layoutOverrides).join(', ')}（非默认布局，已按覆盖值解析）`,
      );
    }
  } catch (err) {
    if (err instanceof KnowledgeError) {
      notes.push(err.message);
      layout = resolveLayout(undefined);
    } else {
      throw err;
    }
  }

  return {
    projectRoot,
    agentDir,
    projectConfigPath: fs.existsSync(projectConfigPath) ? projectConfigPath : null,
    vaultConfigPath: vault?.configPath ?? null,
    project,
    vault: vault?.binding ?? null,
    layout,
    warnings,
    notes,
  };
}

function readText(p: string): string {
  try {
    return fs.readFileSync(p, 'utf8');
  } catch (err) {
    throw new KnowledgeError('project_config_invalid', `读取失败 ${displayPath(p)}：${(err as Error).message}`, {
      path: displayPath(p),
    });
  }
}

function readProjectIdentity(
  doc: YamlObject,
  configPath: string,
  options: LoadConfigOptions,
): ProjectIdentity {
  const rawId = getString(doc, 'project.id');
  const rawName = getString(doc, 'project.name');

  const envId = options.ignoreEnv ? undefined : process.env['KNOWLEDGE_PROJECT_ID'];
  const envName = options.ignoreEnv ? undefined : process.env['KNOWLEDGE_PROJECT_NAME'];

  const id = (envId ?? rawId ?? '').trim();
  const name = (envName ?? rawName ?? '').trim();

  if (id === '') {
    throw new KnowledgeError(
      'project_config_invalid',
      `${displayPath(configPath)} 缺少 project.id。project.id 必须稳定且非空 —— 不要使用随机 UUID。`,
      { path: displayPath(configPath) },
    );
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(id)) {
    throw new KnowledgeError(
      'project_config_invalid',
      `project.id "${id}" 含不安全字符。只允许字母、数字、点、下划线、连字符，且以字母或数字开头` +
        '（它会被用作 04_Work/Active/<project-id>/ 目录名）。',
      { id },
    );
  }
  if (id === '.' || id === '..') {
    throw new KnowledgeError('project_config_invalid', `project.id 不可为 "${id}"`, { id });
  }

  return { id, name: name === '' ? id : name };
}

interface VaultReadResult {
  readonly binding: VaultBinding;
  readonly configPath: string | null;
  readonly layoutOverrides: Partial<Record<string, unknown>> | undefined;
}

function readVaultBinding(args: {
  vaultConfigPath: string;
  projectRoot: string;
  warnings: string[];
  notes: string[];
  ignoreEnv: boolean;
}): VaultReadResult | null {
  const { vaultConfigPath, projectRoot, warnings, notes, ignoreEnv } = args;

  if (fs.existsSync(vaultConfigPath)) {
    const doc = parseYaml(readText(vaultConfigPath), displayPath(vaultConfigPath));

    const id = (getString(doc, 'vault.id') ?? '').trim();
    let vaultPath = (getString(doc, 'vault.path') ?? '').trim();

    if (id === '') {
      throw new KnowledgeError(
        'vault_config_invalid',
        `${displayPath(vaultConfigPath)} 缺少 vault.id。它必须与 .agent/project.yaml 的 project.id 完全一致。`,
        { path: displayPath(vaultConfigPath) },
      );
    }
    if (vaultPath === '') {
      throw new KnowledgeError(
        'vault_config_invalid',
        `${displayPath(vaultConfigPath)} 缺少 vault.path。请填写 Vault 的**绝对路径**。`,
        { path: displayPath(vaultConfigPath) },
      );
    }
    if (!path.isAbsolute(vaultPath)) {
      throw new KnowledgeError(
        'vault_config_invalid',
        `vault.path "${vaultPath}" 不是绝对路径。规范要求使用绝对路径（Windows 建议 "D:/Obsidian/<project>"）。`,
        { vaultPath },
      );
    }

    vaultPath = path.normalize(vaultPath);

    if (!fs.existsSync(vaultPath)) {
      // 不抛错：让 knowledge_health / knowledge_resolve 能返回结构化诊断，
      // 但写操作会在 identity 校验阶段被拒绝。
      notes.push(`vault.path 指向的目录不存在：${displayPath(vaultPath)}`);
    }

    const layoutRaw = doc['layout'];
    const layoutOverrides =
      layoutRaw && typeof layoutRaw === 'object' && !Array.isArray(layoutRaw)
        ? (layoutRaw as Record<string, unknown>)
        : undefined;

    return {
      binding: { id, path: vaultPath, source: 'vault.local.yaml' },
      configPath: vaultConfigPath,
      layoutOverrides,
    };
  }

  // --- 兜底：环境变量 ---------------------------------------------------
  const envPath = ignoreEnv ? undefined : process.env['KNOWLEDGE_VAULT_PATH'];
  const envId = ignoreEnv ? undefined : process.env['KNOWLEDGE_VAULT_ID'];
  if (envPath && path.isAbsolute(envPath)) {
    warnings.push(
      '未找到 .agent/vault.local.yaml，改用了环境变量 KNOWLEDGE_VAULT_PATH。' +
        '这是临时/容器场景的兜底，正常使用请创建 vault.local.yaml 并复制 vault.local.yaml.example。',
    );
    return {
      binding: { id: envId ?? '', path: path.normalize(envPath), source: 'env:KNOWLEDGE_VAULT_PATH' },
      configPath: null,
      layoutOverrides: undefined,
    };
  }

  const examplePath = path.join(projectRoot, AGENT_DIR, VAULT_CONFIG_EXAMPLE_FILE);
  notes.push(
    `缺少 ${displayPath(vaultConfigPath)}。这是**本机私有**文件，故意不入库。` +
      `请复制示例：${displayPath(examplePath)} → ${displayPath(vaultConfigPath)}，并把 vault.path 改成本机真实 Vault 绝对路径。` +
      `本 Server 不会扫描磁盘去找 Vault。`,
  );
  return null;
}
