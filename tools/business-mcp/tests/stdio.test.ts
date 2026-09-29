/**
 * stdio.test.ts — TEST-MCP-05：**真实子进程 + 真实 stdio JSON-RPC** 的 MCP 协议测试。
 *
 * 被测对象是 `dist/index.js`（先用 `npm run build` 产出），后端是 `tests/helpers/stubBackend.ts`
 * 按冻结契约实现的假平台 —— 因此本测试**不需要**真平台、数据库、Redis 或真实 Token。
 *
 * 覆盖（对应任务接受标准"对 stub 后端完成 tools/list 与 tools/call"）：
 *   握手 / 清单与静态白名单一致 / 说明与 schema 来自后端 / 正常调用转发 /
 *   后端 4xx·5xx 的可读错误 / Token 缺失与无效 / 写工具与未知工具被本地拒绝 /
 *   写工具混入清单被丢弃 / 平台不可达时降级 / 超时 / truncated / stdout 纯净性 / 不泄漏 Token。
 */

import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { isolatedEnv, startMcpClient, type McpTestClient } from './helpers/mcpClient.js';
import {
  DEFAULT_TOOLS,
  contractHandler,
  reservedClosedPort,
  startStubBackend,
  type StubBackend,
} from './helpers/stubBackend.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PACKAGE_ROOT = path.resolve(HERE, '..');
const SERVER_ENTRY = path.join(PACKAGE_ROOT, 'dist', 'index.js');
const PREFIX = 'mcp__guarantee__';
const TOKEN = 'test-token-not-a-secret';

/** 只读白名单（硬编码，故意**不**从源码 import —— 否则断言就是同义反复）。 */
const EXPECTED_READONLY = [
  'getCurrentDate',
  'queryBusinessKnowledge',
  'queryDepartment',
  'queryInsuranceType',
  'queryMyProposals',
  'queryMyToolCalls',
  'queryOperationAudit',
  'queryOrderDistribution',
  'queryOrderSummary',
  'queryOrderTrend',
  'queryOrg',
  'queryRole',
  'queryUser',
].sort();

const EXPECTED_EXPOSED = EXPECTED_READONLY.map((name) => PREFIX + name).sort();

const clients: McpTestClient[] = [];
const stubs: StubBackend[] = [];

afterEach(async () => {
  for (const client of clients) await client.close().catch(() => undefined);
  for (const stub of stubs) await stub.close().catch(() => undefined);
  clients.length = 0;
  stubs.length = 0;
});

async function startGateway(options: {
  baseUrl: string;
  token?: string | null;
  enabled?: string;
  extraEnv?: Record<string, string>;
}): Promise<McpTestClient> {
  const overrides: Record<string, string> = {
    GUARANTEE_MCP_BASE_URL: options.baseUrl,
    ...(options.enabled === undefined ? { GUARANTEE_AI_MCP_ENABLED: 'true' } : { GUARANTEE_AI_MCP_ENABLED: options.enabled }),
    ...(options.token === null ? {} : { GUARANTEE_MCP_TOKEN: options.token ?? TOKEN }),
    ...(options.extraEnv ?? {}),
  };
  const client = await startMcpClient({
    serverEntry: SERVER_ENTRY,
    env: isolatedEnv(overrides),
    cwd: PACKAGE_ROOT,
  });
  clients.push(client);
  return client;
}

async function startStub(handler = contractHandler()): Promise<StubBackend> {
  const stub = await startStubBackend(handler);
  stubs.push(stub);
  return stub;
}

function resultText(result: { content: Array<{ text: string }> }): string {
  return result.content.map((c) => c.text).join('\n');
}

describe('业务 MCP 网关 · stdio 协议（TEST-MCP-05）', () => {
  it('initialize 握手：声明 guarantee-business 身份与 tools 能力', async () => {
    const stub = await startStub();
    const client = await startGateway({ baseUrl: stub.url });

    const result = (await client.initialize()) as {
      protocolVersion: string;
      serverInfo: { name: string; version: string };
      capabilities: { tools?: unknown; resources?: unknown; prompts?: unknown };
    };

    expect(result.serverInfo.name).toBe('guarantee-business');
    expect(result.serverInfo.version).toBe('0.1.0');
    expect(result.capabilities.tools).toBeDefined();
    // 第一版只做 tools：不对外声明 resources / prompts
    expect(result.capabilities.resources).toBeUndefined();
    expect(result.capabilities.prompts).toBeUndefined();
    expect(typeof result.protocolVersion).toBe('string');
  });

  it('tools/list 返回 13 个只读工具，与静态白名单逐名一致，且不含任何写工具', async () => {
    const stub = await startStub();
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();

    const tools = await client.listTools();

    expect(tools.map((t) => t.name).sort()).toEqual(EXPECTED_EXPOSED);
    expect(tools).toHaveLength(13);

    const forbidden = /(propose|create|update|delete|remove|disable|enable|execute|shell|command|raw_write|import|export)/i;
    for (const tool of tools) {
      expect(tool.name, `${tool.name} 命中写能力特征`).not.toMatch(forbidden);
      expect(tool.description, `${tool.name} 缺少 description`).toBeTruthy();
      expect(tool.annotations?.readOnlyHint).toBe(true);
    }
  });

  it('tools/list 的说明与 inputSchema 来自后端（不落回静态兜底描述）', async () => {
    const stub = await startStub();
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();

    const tools = await client.listTools();
    const summary = tools.find((t) => t.name === `${PREFIX}queryOrderSummary`);
    const currentDate = tools.find((t) => t.name === `${PREFIX}getCurrentDate`);

    expect(summary?.description).toBe('订单汇总（后端说明）');
    expect(summary?.inputSchema).toEqual({
      type: 'object',
      properties: { orderType: { type: 'string' } },
      required: [],
    });
    // 后端没给 schema 的工具用宽松兜底，而不是猜测参数
    expect(currentDate?.inputSchema).toEqual({ type: 'object', properties: {}, additionalProperties: true });
  });

  it('tools/call 正常路径：转发到裸工具名并带上 Bearer Token，返回 result/dataSource/truncated 与口径行', async () => {
    const stub = await startStub();
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();

    const result = await client.callTool(`${PREFIX}queryOrderSummary`, { orderType: 'TENDER' });

    expect(result.isError).toBeFalsy();
    expect(result.structuredContent).toEqual({
      result: 'stub 结果：queryOrderSummary 被调用',
      dataSource: '平台口径 · stub · queryOrderSummary',
      truncated: false,
    });

    const text = resultText(result);
    expect(text).toContain('stub 结果：queryOrderSummary 被调用');
    expect(text).toContain('口径：平台口径 · stub · queryOrderSummary');

    const post = stub.requests.find((r) => r.method === 'POST');
    expect(post?.path).toBe('/api/ai/mcp/tools/queryOrderSummary');
    expect(post?.authorization).toBe(`Bearer ${TOKEN}`);
    expect(post?.body).toEqual({ orderType: 'TENDER' });
  });

  it('tools/call 接受带前缀与裸名两种写法（映射到同一后端工具）', async () => {
    const stub = await startStub();
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();

    const prefixed = await client.callTool(`${PREFIX}getCurrentDate`, {});
    const bare = await client.callTool('getCurrentDate', {});

    expect(prefixed.isError).toBeFalsy();
    expect(bare.isError).toBeFalsy();
    expect(stub.requests.filter((r) => r.method === 'POST').map((r) => r.path)).toEqual([
      '/api/ai/mcp/tools/getCurrentDate',
      '/api/ai/mcp/tools/getCurrentDate',
    ]);
  });

  it('后端 400 → isError 且给出可读错误（状态码 + 平台 message）', async () => {
    const stub = await startStub(
      contractHandler({ onCall: () => ({ status: 400, body: { message: '参数 orderType 不合法' } }) }),
    );
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();

    const result = await client.callTool(`${PREFIX}queryOrderSummary`, { orderType: 'X' });
    const text = resultText(result);

    expect(result.isError).toBe(true);
    expect(text).toContain('BAD_REQUEST');
    expect(text).toContain('HTTP 400');
    expect(text).toContain('参数 orderType 不合法');
  });

  it('后端 500 → isError 且明确是平台内部错误（不是参数问题）', async () => {
    const stub = await startStub(
      contractHandler({ onCall: () => ({ status: 500, body: { message: 'internal boom' } }) }),
    );
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();

    const result = await client.callTool(`${PREFIX}queryOrderSummary`, {});
    const text = resultText(result);

    expect(result.isError).toBe(true);
    expect(text).toContain('HTTP 500');
    expect(text).toContain('平台内部错误');
  });

  it('Token 无效（后端 401）→ isError 且提示 Token 缺失/无效/已撤销', async () => {
    const stub = await startStub(
      contractHandler({ onCall: () => ({ status: 401, body: { message: 'Token 已撤销' } }) }),
    );
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();

    const result = await client.callTool(`${PREFIX}queryOrderSummary`, {});
    const text = resultText(result);

    expect(result.isError).toBe(true);
    expect(text).toContain('UNAUTHORIZED');
    expect(text).toContain('HTTP 401');
    expect(text).toContain('Token');
  });

  it('Token 无效时 tools/list 直接失败，**不**回退静态清单（撤销必须立刻可见）', async () => {
    const stub = await startStub(
      contractHandler({ onList: () => ({ status: 401, body: { message: 'Token 无效' } }) }),
    );
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();

    await expect(client.listTools()).rejects.toThrow(/Token|401/);
  });

  it('未设置 GUARANTEE_MCP_TOKEN → 网关拒绝启动（退出码非 0 + stderr 可读）', async () => {
    const client = await startGateway({ baseUrl: 'http://127.0.0.1:1', token: null });

    const { code } = await client.waitForExit();
    const stderr = client.stderr.join('');

    expect(code).not.toBe(0);
    expect(stderr).toContain('GUARANTEE_MCP_TOKEN');
    expect(stderr).toContain('拒绝启动');
  });

  it('默认关闭：GUARANTEE_AI_MCP_ENABLED=false → 网关拒绝启动并说明默认关闭', async () => {
    const client = await startGateway({ baseUrl: 'http://127.0.0.1:1', enabled: 'false' });

    const { code } = await client.waitForExit();
    const stderr = client.stderr.join('');

    expect(code).not.toBe(0);
    expect(stderr).toContain('默认关闭');
    expect(stderr).toContain('GUARANTEE_AI_MCP_ENABLED=true');
  });

  it('后端清单混入写工具 proposeOrgChange → 网关丢弃并写 stderr 告警', async () => {
    const stub = await startStub(
      contractHandler({ tools: [...DEFAULT_TOOLS, { name: 'proposeOrgChange', description: '生成写提案' }] }),
    );
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();

    const tools = await client.listTools();

    expect(tools.map((t) => t.name)).not.toContain(`${PREFIX}proposeOrgChange`);
    expect(tools).toHaveLength(13);
    expect(client.stderr.join('')).toContain('已丢弃');
  });

  it('未知工具 / 写工具名 → 本地拒绝，且不向后端发出任何请求', async () => {
    const stub = await startStub();
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();

    const writeAttempt = await client.callTool('proposeOrgChange', { orgId: 1, action: 'disable' });
    const unknownAttempt = await client.callTool('dropDatabase', {});
    const injectionAttempt = await client.callTool('/../../actuator/env', {});

    expect(writeAttempt.isError).toBe(true);
    expect(resultText(writeAttempt)).toMatch(/TOOL_NOT_ALLOWED|只读/);
    expect(unknownAttempt.isError).toBe(true);
    expect(resultText(unknownAttempt)).toMatch(/TOOL_UNKNOWN|未知工具/);
    expect(injectionAttempt.isError).toBe(true);

    // 没有万能透传：这些名字一个都不能到后端
    const calledPaths = stub.requests.map((r) => r.path);
    expect(calledPaths.some((p) => /propose|dropDatabase|actuator/.test(p))).toBe(false);
    expect(calledPaths.filter((p) => p.startsWith('/api/ai/mcp/tools/'))).toHaveLength(0);
  });

  it('平台不可达 → tools/list 降级为静态只读清单并写 stderr 告警', async () => {
    const port = await reservedClosedPort();
    const client = await startGateway({ baseUrl: `http://127.0.0.1:${port}` });
    await client.initialize();

    const tools = await client.listTools();

    expect(tools.map((t) => t.name).sort()).toEqual(EXPECTED_EXPOSED);
    expect(client.stderr.join('')).toContain('降级为静态只读清单');
  });

  it('truncated=true → 文本带截断标记且 structuredContent.truncated 为 true', async () => {
    const stub = await startStub(
      contractHandler({
        onCall: () => ({
          status: 200,
          body: { data: { result: '部分明细', dataSource: '订单明细', truncated: true } },
        }),
      }),
    );
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();

    const result = await client.callTool(`${PREFIX}queryOrderSummary`, {});

    expect(result.isError).toBeFalsy();
    expect(result.structuredContent?.truncated).toBe(true);
    expect(resultText(result)).toContain('[结果已截断');
  });

  it('平台超时 → isError 且给出可读的超时提示', async () => {
    const stub = await startStub(
      contractHandler({
        onCall: () => ({
          status: 200,
          delayMs: 1500,
          body: { data: { result: '迟到的结果', dataSource: null, truncated: false } },
        }),
      }),
    );
    const client = await startGateway({ baseUrl: stub.url, extraEnv: { GUARANTEE_MCP_TIMEOUT_MS: '300' } });
    await client.initialize();

    const result = await client.callTool(`${PREFIX}queryOrderSummary`, {});
    const text = resultText(result);

    expect(result.isError).toBe(true);
    expect(text).toMatch(/TIMEOUT|超时/);
    expect(text).toContain('300ms');
  });

  it('stdout 只有 JSON-RPC 帧，日志全走 stderr，且 stderr 不泄漏 Token', async () => {
    const stub = await startStub();
    const client = await startGateway({ baseUrl: stub.url });
    await client.initialize();
    await client.listTools();
    await client.callTool(`${PREFIX}getCurrentDate`, {});

    // 客户端解析到非 JSON 行就会抛错，所以能走到这里说明 stdout 是干净的
    expect(client.stdoutLines.length).toBeGreaterThan(0);
    for (const line of client.stdoutLines) {
      expect(() => JSON.parse(line)).not.toThrow();
    }

    const stderr = client.stderr.join('');
    expect(stderr).toContain('[business-mcp]');
    expect(stderr).toContain('startup audit');
    expect(stderr).toContain('server ready');
    expect(stderr).not.toContain(TOKEN);
  });
});
