/**
 * pathguard.ts — Vault 内路径安全边界。
 *
 * 这是整个 MCP Server 最关键的防线。任何写操作都必须先过这里。
 *
 * 被显式拒绝的输入：
 *   ../            目录穿越
 *   /etc/passwd    POSIX 绝对路径
 *   C:\x           Windows 盘符绝对路径
 *   \\srv\share    UNC 路径
 *   \\?\C:\x       Windows 设备命名空间路径
 *   CON / NUL / COM1 等  Windows 保留设备名（会导致诡异 IO 行为）
 *   file.md:ads    NTFS 备用数据流
 *   符号链接逃逸    链接指向 Vault 之外
 *
 * 两台机器上的大小写差异也在此处理：Windows 文件系统大小写不敏感，
 * 因此包含性判断统一小写比较，避免 `VAULT/x` 绕过 `vault/` 前缀检查。
 */

import * as fss from 'node:fs';
import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { KnowledgeError } from './errors.js';

/** Windows 保留设备名（不区分大小写，且带扩展名同样被保留）。 */
const WINDOWS_RESERVED = new Set([
  'CON', 'PRN', 'AUX', 'NUL',
  'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9',
  'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9',
]);

export interface NormalizedRelativePath {
  /** 归一化后的相对路径，始终以 / 分隔，且不以 / 开头 */
  readonly relative: string;
  /** 各路径段 */
  readonly segments: readonly string[];
}

/**
 * 校验并归一化一个「Vault 内相对路径」。
 * 纯字符串检查，不接触文件系统 —— 因此可被单测穷举。
 */
export function normalizeRelativePath(input: unknown): NormalizedRelativePath {
  if (typeof input !== 'string') {
    throw new KnowledgeError('invalid_path', 'path 必须是字符串', { received: typeof input });
  }

  let p = input.trim();
  if (p === '') {
    throw new KnowledgeError('invalid_path', 'path 不能为空');
  }

  // 控制字符：既无意义，也可能被用来绕过后续检查。
  if (/[\u0000-\u001f]/.test(p)) {
    throw new KnowledgeError('invalid_path', 'path 含有控制字符', { path: input });
  }

  // --- 绝对路径 / UNC / 设备命名空间 -----------------------------------
  if (p.startsWith('\\\\') || p.startsWith('//')) {
    throw new KnowledgeError('invalid_path', '拒绝 UNC / 网络路径', { path: input });
  }
  if (/^[a-zA-Z]:/.test(p)) {
    throw new KnowledgeError('invalid_path', '拒绝盘符绝对路径（含 NTFS 数据流写法）', { path: input });
  }
  if (p.startsWith('/') || p.startsWith('\\')) {
    throw new KnowledgeError('invalid_path', '拒绝绝对路径，只接受 Vault 内相对路径', { path: input });
  }
  if (p.startsWith('~')) {
    throw new KnowledgeError('invalid_path', '拒绝 home 展开写法（~），只接受 Vault 内相对路径', { path: input });
  }

  // 反斜杠一律视为分隔符，避免 `a\..\..\b` 绕过只检查 `/` 的实现。
  p = p.replace(/\\/g, '/');

  const segments: string[] = [];
  for (const seg of p.split('/')) {
    if (seg === '' || seg === '.') continue; // 折叠 `//` 与 `.`
    if (seg === '..') {
      throw new KnowledgeError('invalid_path', '拒绝目录穿越（..）', { path: input });
    }
    if (seg.endsWith(' ') || seg.endsWith('.')) {
      // Windows 会静默去掉结尾的空格/点，导致预期路径与实际路径不一致。
      throw new KnowledgeError('invalid_path', '路径段不可以空格或点结尾（Windows 会静默改写）', {
        path: input,
        segment: seg,
      });
    }
    if (seg.includes(':')) {
      throw new KnowledgeError('invalid_path', '路径段不可包含 “:”', { path: input, segment: seg });
    }
    const stem = seg.split('.')[0]?.toUpperCase() ?? '';
    if (WINDOWS_RESERVED.has(stem)) {
      throw new KnowledgeError('invalid_path', `拒绝 Windows 保留设备名：${seg}`, { path: input });
    }
    segments.push(seg);
  }

  if (segments.length === 0) {
    throw new KnowledgeError('invalid_path', 'path 未指向任何文件', { path: input });
  }

  return { relative: segments.join('/'), segments };
}

/** 大小写策略：Windows / macOS 默认大小写不敏感。 */
function isCaseInsensitiveFs(): boolean {
  return process.platform === 'win32' || process.platform === 'darwin';
}

/** realpath，优先用 native（保留真实大小写），失败时退回非 native。 */
function realpathSafe(p: string): string {
  try {
    return fss.realpathSync.native(p);
  } catch {
    return fss.realpathSync(p);
  }
}

/** 判断 child 是否在 root 之内（含 root 自身）。 */
export function isInside(root: string, child: string): boolean {
  const a = path.resolve(root);
  const b = path.resolve(child);
  const [x, y] = isCaseInsensitiveFs() ? [a.toLowerCase(), b.toLowerCase()] : [a, b];
  if (x === y) return true;
  const prefix = x.endsWith(path.sep) ? x : x + path.sep;
  return y.startsWith(prefix);
}

/**
 * 找到从 candidate 起、向上最近的一个「已存在」路径。
 * 用于对「尚未创建的笔记」做符号链接逃逸检查。
 */
export function nearestExistingAncestor(candidate: string): string | null {
  let cur = path.resolve(candidate);
  for (;;) {
    if (fss.existsSync(cur)) return cur;
    const parent = path.dirname(cur);
    if (parent === cur) return null; // 到根了
    cur = parent;
  }
}

export interface VaultPathGuardOptions {
  /** Vault 根目录绝对路径 */
  vaultRoot: string;
}

/**
 * Vault 路径守卫。构造时解析 Vault 的真实路径（realpath），
 * 因为若 Vault 根自身就是符号链接，后续所有比较都必须以真实路径为基准。
 */
export class VaultPathGuard {
  readonly vaultRoot: string;
  readonly vaultRealRoot: string;

  constructor(options: VaultPathGuardOptions) {
    this.vaultRoot = path.resolve(options.vaultRoot);
    let real: string;
    try {
      real = realpathSafe(this.vaultRoot);
    } catch {
      real = this.vaultRoot;
    }
    this.vaultRealRoot = path.resolve(real);
  }

  /**
   * 把 Vault 内相对路径解析为绝对路径，并做全部安全检查。
   * 只做检查，不写任何东西 —— 因此可安全地用于读操作。
   */
  resolve(relativeInput: unknown): { relative: string; absolutePath: string } {
    const { relative } = normalizeRelativePath(relativeInput);
    const absolutePath = path.join(this.vaultRoot, ...relative.split('/'));

    // 词法层面必须在 Vault 内（normalizeRelativePath 已经挡掉 ..，
    // 这里作为兜底，防止未来有人改动归一化逻辑）。
    if (!isInside(this.vaultRoot, absolutePath) || path.resolve(absolutePath) === this.vaultRoot) {
      throw new KnowledgeError('path_escape', '解析后的路径不在 Vault 内', { path: relativeInput });
    }

    // --- 符号链接逃逸检查 ------------------------------------------------
    // 对最近的存在祖先做 realpath，再确认它仍在 Vault 真实路径内。
    const ancestor = nearestExistingAncestor(absolutePath);
    if (ancestor) {
      let realAncestor: string;
      try {
        realAncestor = realpathSafe(ancestor);
      } catch (err) {
        throw new KnowledgeError('symlink_escape', `无法解析路径真实位置：${(err as Error).message}`, {
          path: relativeInput,
          ancestor,
        });
      }
      if (!isInside(this.vaultRealRoot, realAncestor)) {
        throw new KnowledgeError('symlink_escape', '路径经符号链接指向 Vault 之外', {
          path: relativeInput,
          ancestor,
          realAncestor,
          vaultRealRoot: this.vaultRealRoot,
        });
      }
    }

    return { relative, absolutePath };
  }

  /**
   * 解析「搜索/遍历起点」。
   * 与 `resolve()` 的区别：允许用 `''` / `'.'` 表示 Vault 根自身。
   * 只用于**只读遍历**，因此不需要（也不应该）指向单个文件。
   */
  resolveSearchRoot(relativeInput: unknown): { relative: string; absolutePath: string } {
    if (relativeInput === undefined || relativeInput === null) {
      return { relative: '', absolutePath: this.vaultRoot };
    }
    const raw = String(relativeInput).trim();
    if (raw === '' || raw === '.' || raw === './' || raw === '.\\') {
      return { relative: '', absolutePath: this.vaultRoot };
    }
    return this.resolve(raw);
  }

  /** 解析并确认目标存在且是普通文件；否则给出明确错误码。 */
  async statRelative(relativeInput: unknown): Promise<{ relative: string; absolutePath: string }> {
    const resolved = this.resolve(relativeInput);
    let st: fss.Stats;
    try {
      st = await fs.stat(resolved.absolutePath);
    } catch {
      throw new KnowledgeError('not_found', 'Vault 内不存在该文件', { path: resolved.relative });
    }
    if (!st.isFile()) {
      throw new KnowledgeError('not_a_file', '目标不是普通文件', { path: resolved.relative });
    }
    return resolved;
  }
}
