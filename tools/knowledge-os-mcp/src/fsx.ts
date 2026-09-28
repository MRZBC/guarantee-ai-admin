/**
 * fsx.ts — 文件系统原语：换行风格保持 + 原子写 + 并发保护。
 *
 * 本文件是「不破坏用户笔记」这一承诺的实现处。三条硬规则：
 *
 *  1. 换行风格保持：读写时不假设 LF 或 CRLF，先探测、再按原样写回。
 *     （本仓库同时存在 .gitattributes `* text=auto` 与 core.autocrlf=true，
 *       Vault 现有文件实测为 LF，但这里不把它写死。）
 *  2. 原子写：临时文件 → 落盘 → 改名替换。绝不半截覆盖原文件。
 *  3. 并发保护：写入前校验「我读到的版本」是否仍是磁盘上的版本。
 *     不一致 → conflict，拒绝静默覆盖另一个 Agent 的修改。
 */

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync } from 'node:fs';
import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { KnowledgeError, toKnowledgeError } from './errors.js';

/** 探测到的换行风格。 */
export type NewlineStyle = '\n' | '\r\n';

/** 读到的文件内容 + 其身份指纹，用于后续并发校验。 */
export interface FileSnapshot {
  /** 绝对路径 */
  readonly absolutePath: string;
  /** 原始文本（未做任何换行归一化） */
  readonly raw: string;
  readonly newline: NewlineStyle;
  /** 是否带 UTF-8 BOM */
  readonly bom: boolean;
  /** raw 的 sha256，作为「我读到的版本」的指纹 */
  readonly hash: string;
  readonly size: number;
  readonly mtimeMs: number;
}

const BOM = '\uFEFF';

/** 探测文本的主要换行风格：CRLF 占多数则 CRLF，否则 LF。 */
export function detectNewline(text: string): NewlineStyle {
  let crlf = 0;
  let lf = 0;
  for (let i = 0; i < text.length; i++) {
    if (text.charCodeAt(i) === 10) {
      if (i > 0 && text.charCodeAt(i - 1) === 13) crlf++;
      else lf++;
    }
  }
  return crlf > lf ? '\r\n' : '\n';
}

export function sha256(text: string): string {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

export async function fileExists(p: string): Promise<boolean> {
  try {
    await fs.access(p);
    return true;
  } catch {
    return false;
  }
}

export async function isFile(p: string): Promise<boolean> {
  try {
    return (await fs.stat(p)).isFile();
  } catch {
    return false;
  }
}

export async function isDirectory(p: string): Promise<boolean> {
  try {
    return (await fs.stat(p)).isDirectory();
  } catch {
    return false;
  }
}

/**
 * 读取文件并保留换行/BOM 信息。
 * 找不到文件时抛 KnowledgeError('not_found')。
 */
export async function readSnapshot(absolutePath: string): Promise<FileSnapshot> {
  let raw: string;
  try {
    raw = await fs.readFile(absolutePath, 'utf8');
  } catch (err) {
    throw toKnowledgeError(err);
  }

  let stat: Awaited<ReturnType<typeof fs.stat>>;
  try {
    stat = await fs.stat(absolutePath);
  } catch (err) {
    throw toKnowledgeError(err);
  }

  const bom = raw.startsWith(BOM);
  const body = bom ? raw.slice(1) : raw;

  return {
    absolutePath,
    raw: body,
    newline: detectNewline(body),
    bom,
    hash: sha256(body),
    size: stat.size,
    mtimeMs: stat.mtimeMs,
  };
}

/** 若文件不存在返回 null，而不是抛错。 */
export async function tryReadSnapshot(absolutePath: string): Promise<FileSnapshot | null> {
  if (!(await isFile(absolutePath))) return null;
  try {
    return await readSnapshot(absolutePath);
  } catch (err) {
    if (err instanceof KnowledgeError && err.code === 'not_found') return null;
    throw err;
  }
}

/** 把归一化文本按目标风格还原（内部一律用 \n 处理）。 */
export function applyNewline(text: string, newline: NewlineStyle): string {
  const normalized = text.replace(/\r\n/g, '\n');
  return newline === '\r\n' ? normalized.replace(/\n/g, '\r\n') : normalized;
}

export interface AtomicWriteOptions {
  /** 文本内容，内部按 \n 处理，写出时套用 newline */
  content: string;
  newline?: NewlineStyle;
  bom?: boolean;
  /**
   * 期望的「读取时指纹」。提供时会在写入前重新校验磁盘内容；
   * 不一致（说明别的 Agent 改过）则抛 conflict。
   */
  expectedHash?: string;
  /** 自定义冲突错误信息 */
  conflictLabel?: string;
}

export interface AtomicWriteResult {
  readonly absolutePath: string;
  readonly bytesWritten: number;
  readonly created: boolean;
  /** 写入后的新指纹，调用方可以回传给下一次写入做乐观锁 */
  readonly hash: string;
}

/**
 * 原子写：同目录临时文件 → fsync → 改名替换。
 *
 * 为什么同目录：跨卷 rename 会退化成复制，失去原子性。
 * 为什么 fsync：先落盘内容再改名，避免「文件已改名但内容还在页缓存里」的窗口。
 */
export async function atomicWrite(
  absolutePath: string,
  options: AtomicWriteOptions,
): Promise<AtomicWriteResult> {
  const newline = options.newline ?? '\n';
  const bom = options.bom ?? false;
  const existed = await fileExists(absolutePath);

  // --- 并发保护 ---------------------------------------------------------
  if (options.expectedHash !== undefined) {
    const current = await tryReadSnapshot(absolutePath);
    const actual = current?.hash ?? null;
    if (actual !== options.expectedHash) {
      throw new KnowledgeError('conflict', options.conflictLabel ?? '文件已被其他 Agent 修改，拒绝覆盖', {
        path: absolutePath,
        expectedHash: options.expectedHash,
        actualHash: actual,
      });
    }
  }

  const payload = applyNewline(options.content, newline);
  const bytes = Buffer.from(bom ? BOM + payload : payload, 'utf8');

  const dir = path.dirname(absolutePath);
  await fs.mkdir(dir, { recursive: true });
  const tmp = path.join(dir, `.${path.basename(absolutePath)}.kos-${process.pid}-${Date.now()}.tmp`);

  let handle: fs.FileHandle | undefined;
  try {
    handle = await fs.open(tmp, 'wx', 0o644);
    await handle.writeFile(bytes);
    await handle.sync();
    await handle.close();
    handle = undefined;
    await renameWithRetry(tmp, absolutePath);
  } catch (err) {
    if (handle) await handle.close().catch(() => undefined);
    await fs.rm(tmp, { force: true }).catch(() => undefined);
    if (err instanceof KnowledgeError) throw err;
    throw new KnowledgeError('write_failed', `原子写入失败：${(err as Error).message}`, {
      path: absolutePath,
    });
  }

  return {
    absolutePath,
    bytesWritten: bytes.byteLength,
    created: !existed,
    hash: sha256(payload),
  };
}

/**
 * Windows 下 rename 覆盖已存在目标会间歇性 EPERM/EBUSY（杀毒、索引器持有句柄）。
 * 短暂退避重试，仍失败才报错——而不是把原文件删掉再写（那会破坏原子性承诺）。
 */
async function renameWithRetry(from: string, to: string, attempts = 8): Promise<void> {
  let lastErr: unknown;
  for (let i = 0; i < attempts; i++) {
    try {
      await fs.rename(from, to);
      return;
    } catch (err) {
      lastErr = err;
      const code = (err as NodeJS.ErrnoException).code;
      if (code !== 'EPERM' && code !== 'EACCES' && code !== 'EBUSY') throw err;
      await sleep(15 * (i + 1));
    }
  }
  throw lastErr;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 追加写（append-only）。用于 LOG.md：永远不重写历史。
 * 追加不是「整文件替换」，因此不需要 expectedHash。
 */
export async function appendText(
  absolutePath: string,
  text: string,
  newline: NewlineStyle = '\n',
): Promise<void> {
  const dir = path.dirname(absolutePath);
  await fs.mkdir(dir, { recursive: true });
  try {
    const payload = applyNewline(text, newline);
    await fs.appendFile(absolutePath, Buffer.from(payload, 'utf8'));
  } catch (err) {
    throw new KnowledgeError('write_failed', `追加写入失败：${(err as Error).message}`, {
      path: absolutePath,
    });
  }
}

/** 同步版目录存在性检查，供不含 await 的路径校验使用。 */
export function ensureDirSync(dir: string): void {
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
}
