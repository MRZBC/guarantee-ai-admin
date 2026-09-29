/**
 * stubBackend.ts — 按**冻结契约**实现的假平台后端（测试专用）。
 *
 * 它只做一件事：让协议测试完全不依赖真实平台（不需要 8081/8088、不需要数据库、不需要 Token 签发）。
 * 契约：
 *   GET  /api/ai/mcp/tools         → { data: [ { name, description, inputSchema } ] }
 *   POST /api/ai/mcp/tools/{name}  → { data: { result, dataSource, truncated } }
 */

import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';

export interface StubRequest {
  readonly method: string;
  readonly path: string;
  readonly authorization: string | null;
  readonly body: unknown;
  readonly rawBody: string;
}

export interface StubResponse {
  readonly status: number;
  readonly body: unknown;
  /** 人为延迟（测试超时路径用）。 */
  readonly delayMs?: number;
}

export type StubHandler = (request: StubRequest) => StubResponse | Promise<StubResponse>;

export interface StubTool {
  readonly name: string;
  readonly description?: string;
  readonly inputSchema?: Record<string, unknown>;
}

export interface StubBackend {
  readonly url: string;
  readonly requests: StubRequest[];
  setHandler(handler: StubHandler): void;
  close(): Promise<void>;
}

/** 与 Java 侧 12 个只读 `@Tool` 方法一一对应的假清单。 */
export const DEFAULT_TOOLS: readonly StubTool[] = [
  { name: 'queryOrderSummary', description: '订单汇总（后端说明）', inputSchema: { type: 'object', properties: { orderType: { type: 'string' } }, required: [] } },
  { name: 'getCurrentDate', description: '当前日期（后端说明）' },
  { name: 'queryOrderDistribution', description: '维度分布（后端说明）' },
  { name: 'queryOrderTrend', description: '时间趋势（后端说明）' },
  { name: 'queryOrg', description: '机构查询（后端说明）' },
  { name: 'queryDepartment', description: '部门查询（后端说明）' },
  { name: 'queryUser', description: '用户查询（后端说明）' },
  { name: 'queryRole', description: '角色查询（后端说明）' },
  { name: 'queryInsuranceType', description: '险种查询（后端说明）' },
  { name: 'queryOperationAudit', description: '操作审计（后端说明）' },
  { name: 'queryMyToolCalls', description: '我的工具调用（后端说明）' },
  { name: 'queryMyProposals', description: '我的提案（后端说明）' },
];

export interface ContractOptions {
  readonly tools?: readonly StubTool[];
  /** 覆盖 GET /api/ai/mcp/tools 的响应。 */
  readonly onList?: (request: StubRequest) => StubResponse;
  /** 覆盖 POST /api/ai/mcp/tools/{name} 的响应；返回值 undefined 表示走默认成功响应。 */
  readonly onCall?: (name: string, request: StubRequest) => StubResponse | undefined;
}

/** 默认契约处理器：可被用例局部覆盖。 */
export function contractHandler(options: ContractOptions = {}): StubHandler {
  const tools = options.tools ?? DEFAULT_TOOLS;
  return (request) => {
    if (request.method === 'GET' && request.path === '/api/ai/mcp/tools') {
      if (options.onList) return options.onList(request);
      return {
        status: 200,
        body: {
          data: tools.map((tool) => ({
            name: tool.name,
            description: tool.description ?? '（后端未提供说明）',
            inputSchema: tool.inputSchema ?? { type: 'object', properties: {}, additionalProperties: true },
          })),
        },
      };
    }

    const match = /^\/api\/ai\/mcp\/tools\/([^/]+)$/.exec(request.path);
    if (request.method === 'POST' && match) {
      const name = decodeURIComponent(match[1] ?? '');
      const override = options.onCall?.(name, request);
      if (override) return override;
      if (!tools.some((tool) => tool.name === name)) {
        return { status: 404, body: { message: `工具 ${name} 不存在` } };
      }
      return {
        status: 200,
        body: {
          data: {
            result: `stub 结果：${name} 被调用`,
            dataSource: `平台口径 · stub · ${name}`,
            truncated: false,
          },
        },
      };
    }

    return { status: 404, body: { message: `stub 未实现：${request.method} ${request.path}` } };
  };
}

async function readBody(req: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString('utf8');
}

export async function startStubBackend(handler: StubHandler = contractHandler()): Promise<StubBackend> {
  let currentHandler = handler;
  const requests: StubRequest[] = [];

  const server: Server = createServer((req: IncomingMessage, res: ServerResponse) => {
    void (async () => {
      const rawBody = await readBody(req);
      let body: unknown = rawBody;
      if (rawBody.trim() !== '') {
        try {
          body = JSON.parse(rawBody);
        } catch {
          body = rawBody;
        }
      }
      const request: StubRequest = {
        method: req.method ?? 'GET',
        path: req.url ?? '/',
        authorization: typeof req.headers.authorization === 'string' ? req.headers.authorization : null,
        body,
        rawBody,
      };
      requests.push(request);

      try {
        const response = await currentHandler(request);
        if (response.delayMs && response.delayMs > 0) {
          await new Promise((resolve) => setTimeout(resolve, response.delayMs));
        }
        res.statusCode = response.status;
        res.setHeader('Content-Type', 'application/json; charset=utf-8');
        res.end(typeof response.body === 'string' ? response.body : JSON.stringify(response.body));
      } catch (err) {
        res.statusCode = 500;
        res.setHeader('Content-Type', 'application/json; charset=utf-8');
        res.end(JSON.stringify({ message: err instanceof Error ? err.message : String(err) }));
      }
    })();
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as AddressInfo;

  return {
    url: `http://127.0.0.1:${address.port}`,
    requests,
    setHandler(next) {
      currentHandler = next;
    },
    close() {
      return new Promise<void>((resolve) => {
        server.close(() => resolve());
        server.closeAllConnections?.();
      });
    },
  };
}

/** 取一个确定没有服务在监听的端口（用于"平台不可达"用例）。 */
export async function reservedClosedPort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = (server.address() as AddressInfo).port;
  await new Promise<void>((resolve) => server.close(() => resolve()));
  return port;
}
