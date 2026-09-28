/**
 * pathguard.test.ts — 安全边界（规范第 48 节 / Security）。
 *
 * 这些是纯字符串 / 纯路径检查，不接触被测 Vault，因此可以穷举各种恶意输入。
 * 另外还有一组端到端测试：通过真实 MCP 工具调用确认这些守卫确实生效。
 */

import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { KnowledgeError } from '../src/errors.js';
import { isInside, normalizeRelativePath, VaultPathGuard } from '../src/pathguard.js';
import { startMcpClient, type McpTestClient } from './helpers/mcpClient.js';
import { createFixture, type Fixture } from './helpers/fixture.js';

describe('normalizeRelativePath — 接受合法相对路径', () => {
  it('保留正常路径', () => {
    expect(normalizeRelativePath('03_Wiki/Decisions/决策 - X.md').relative).toBe('03_Wiki/Decisions/决策 - X.md');
  });

  it('折叠 ./ 与重复分隔符', () => {
    expect(normalizeRelativePath('./03_Wiki//Concepts/a.md').relative).toBe('03_Wiki/Concepts/a.md');
    expect(normalizeRelativePath('a\\b\\c.md').relative).toBe('a/b/c.md');
  });
});

describe('normalizeRelativePath — 拒绝危险输入', () => {
  const cases: Array<[string, string]> = [
    ['..', '目录穿越'],
    ['../secret.md', '目录穿越'],
    ['a/../../b.md', '目录穿越'],
    ['a/..\\..\\b.md', '反斜杠形式的目录穿越'],
    ['/etc/passwd', 'POSIX 绝对路径'],
    ['/STATE.md', '以斜杠开头的绝对路径'],
    ['C:/Windows/win.ini', 'Windows 盘符绝对路径'],
    ['C:\\Windows\\win.ini', 'Windows 盘符绝对路径（反斜杠）'],
    ['c:relative.md', '盘符相对路径'],
    ['\\\\server\\share\\x.md', 'UNC 路径'],
    ['//server/share/x.md', 'UNC 路径（正斜杠）'],
    ['\\\\?\\C:\\x.md', 'Windows 设备命名空间路径'],
    ['~/notes.md', 'home 展开'],
    ['', '空字符串'],
    ['   ', '空白'],
    ['CON', 'Windows 保留设备名'],
    ['a/NUL.md', 'Windows 保留设备名（带扩展名）'],
    ['aux.md', 'Windows 保留设备名（小写）'],
    ['a/b:stream', 'NTFS 备用数据流'],
    ['a\u0000b.md', '控制字符'],
    ['a/b. /x.md', '段以点结尾（Windows 会静默改写）'],
  ];

  for (const [input, why] of cases) {
    it(`拒绝 ${JSON.stringify(input)}（${why}）`, () => {
      expect(() => normalizeRelativePath(input)).toThrow(KnowledgeError);
    });
  }

  it('拒绝非字符串输入', () => {
    for (const bad of [null, undefined, 42, {}, []]) {
      expect(() => normalizeRelativePath(bad)).toThrow(KnowledgeError);
    }
  });
});

describe('isInside', () => {
  it('正确判断包含关系（含边界情况）', () => {
    const root = path.resolve('/tmp/vault');
    expect(isInside(root, path.join(root, 'a.md'))).toBe(true);
    expect(isInside(root, path.join(root, 'sub', 'a.md'))).toBe(true);
    expect(isInside(root, root)).toBe(true);
    expect(isInside(root, path.resolve('/tmp/vault-evil/a.md'))).toBe(false);
    expect(isInside(root, path.resolve('/tmp/other/a.md'))).toBe(false);
  });
});

describe('VaultPathGuard — 符号链接逃逸', () => {
  let tmp: string;
  let vault: string;
  let outside: string;

  beforeAll(() => {
    tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kos-symlink-'));
    vault = path.join(tmp, 'vault');
    outside = path.join(tmp, 'outside');
    fs.mkdirSync(vault, { recursive: true });
    fs.mkdirSync(outside, { recursive: true });
    fs.writeFileSync(path.join(outside, 'secret.md'), 'TOP SECRET', 'utf8');
  });

  afterAll(() => {
    fs.rmSync(tmp, { recursive: true, force: true });
  });

  it('拒绝经符号链接指向 Vault 之外的路径', () => {
    const link = path.join(vault, 'escape');
    let created = false;
    try {
      fs.symlinkSync(outside, link, 'junction');
      created = true;
    } catch {
      // Windows 上若无创建符号链接权限（非管理员/未开开发者模式），跳过该断言
      created = false;
    }
    if (!created) {
      console.warn('[skip] 当前环境不允许创建符号链接，跳过 symlink escape 断言');
      return;
    }

    const guard = new VaultPathGuard({ vaultRoot: vault });
    expect(() => guard.resolve('escape/secret.md')).toThrow(KnowledgeError);
    try {
      guard.resolve('escape/secret.md');
    } catch (err) {
      expect((err as KnowledgeError).code).toBe('symlink_escape');
    }
  });

  it('拒绝把 Vault 根自身当作目标路径', () => {
    const guard = new VaultPathGuard({ vaultRoot: vault });
    expect(() => guard.resolve('.')).toThrow(KnowledgeError);
    expect(() => guard.resolve('')).toThrow(KnowledgeError);
  });

  it('允许把 Vault 根作为只读搜索起点', () => {
    const guard = new VaultPathGuard({ vaultRoot: vault });
    expect(guard.resolveSearchRoot('.')).toEqual({ relative: '', absolutePath: vault });
    expect(guard.resolveSearchRoot(undefined)).toEqual({ relative: '', absolutePath: vault });
  });
});

describe('端到端：工具层拒绝 Vault 外访问', () => {
  let fixture: Fixture;
  let client: McpTestClient;

  beforeAll(async () => {
    fixture = createFixture();
    client = await startMcpClient({ serverEntry: fixture.serverEntry, projectRoot: fixture.projectDir });
    await client.initialize();
  });

  afterAll(async () => {
    await client.close();
    fixture.cleanup();
  });

  const escaped: Array<[string, string]> = [
    ['../../etc/passwd', '相对目录穿越'],
    ['..\\..\\VAULT_ID.md', '反斜杠穿越'],
    ['/absolute/path.md', 'POSIX 绝对路径'],
    ['C:/Windows/win.ini', 'Windows 绝对路径'],
    ['//server/share/x.md', 'UNC'],
    ['\\\\?\\C:\\x.md', '设备命名空间'],
    ['03_Wiki/../../outside.md', '中途穿越'],
  ];

  for (const [p, why] of escaped) {
    it(`knowledge_read 拒绝 ${why}：${JSON.stringify(p)}`, async () => {
      const r = await client.callTool('knowledge_read', { path: p });
      expect(r.isError).toBe(true);
      const payload = JSON.stringify(r.content);
      expect(payload).toMatch(/invalid_path|path_escape|symlink_escape/);
      // 绝不能泄漏 Vault 外的内容
      expect(payload).not.toContain('root:');
    });
  }

  it('knowledge_search 的 paths 参数同样受路径守卫约束', async () => {
    const r = await client.callTool('knowledge_search', { query: 'x', paths: ['../../../etc'] });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toMatch(/invalid_path|path_escape/);
  });

  it('knowledge_read 对 Vault 内不存在的文件返回 not_found（而不是沉默返回空）', async () => {
    const r = await client.callTool('knowledge_read', { path: '03_Wiki/Concepts/不存在.md' });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toContain('not_found');
  });

  it('knowledge_read 可以读取 Vault 内真实文件（含 frontmatter 解析）', async () => {
    const r = await client.callToolOk('knowledge_read', { path: 'STATE.md' });
    expect(r['path']).toBe('STATE.md');
    expect(r['type']).toBe('global-state');
    expect((r['frontmatter'] as Record<string, unknown>)['type']).toBe('global-state');
    expect(String(r['content'])).toContain('当前状态');
  });
});
