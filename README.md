# guarantee-ai-admin

智能电子保函运营管理平台。后端采用 Spring Boot 模块化单体，前端采用 Vue 3，内置 AI 对话、业务工具调用、RAG、提示词配置、评测与审计能力。

## 技术栈

- Java 21、Spring Boot 4.1.1、Spring AI 2.0.1
- MyBatis、MySQL 8、Redis
- Vue 3、TypeScript、Vite、Element Plus、ECharts、Pinia
- OpenAI-compatible API，默认使用 `deepseek-chat`

## 项目结构

```text
guarantee-common/       公共响应、异常、分页和用户上下文
guarantee-auth/         登录、JWT 和权限认证
guarantee-system/       机构、部门、用户、角色和险种
guarantee-order/        投标与履约订单
guarantee-analysis/     数据分析、项目和企业
guarantee-ai/           AI 对话、RAG、Tool Calling、MCP、评测与审计
guarantee-web/          应用入口、配置、建表与演示数据
frontend/               Vue 3 管理后台
```

## 快速开始

### 1. 启动 MySQL 和 Redis

```bash
docker compose up -d mysql redis
```

Windows 本机便携环境：

```powershell
pwsh -File scripts/start-local-env.ps1
```

### 2. 启动后端

```bash
mvn clean install -DskipTests
java -jar guarantee-web/target/guarantee-ai-admin.jar
```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问地址：

- 后端：<http://localhost:8081>
- 前端：<http://localhost:5273>
- 健康检查：<http://localhost:8081/actuator/health>

AI 对话需要配置模型 Key；未配置时不影响其他功能启动。

```powershell
$env:DEEPSEEK_API_KEY="sk-xxxxxxxx"
```

## 测试账号

| 账号 | 密码 | 角色 |
|---|---|---|
| `admin` | `Admin@123` | 管理员 |
| `operator` | `Operator@123` | 运营人员 |
| `analyst` | `Analyst@123` | 分析人员 |

## 常用命令

```bash
mvn test                                      # 单元测试
mvn verify                                    # 集成测试，需要 MySQL 和 Redis
cd frontend && npm run build                  # 前端构建
node scripts/ai-golden-questions.mjs --suite=deterministic
node scripts/single-source-of-truth.mjs --check
```

本机多个任务共用数据库，修改代码后建议通过构建锁运行 Maven：

```powershell
pwsh -File scripts/mvn-locked.ps1 test
```

## 文档

- [本地开发环境与演示数据](docs/本地开发环境与演示数据.md)
- [API 概览](docs/API-概览.md)
- [AI 能力与演示](docs/AI-能力与演示.md)
- [本地构建与锁](docs/本地构建与锁.md)
- [完整文档索引](docs/README.md)

数据库 DDL 位于 `guarantee-web/src/main/resources/db/schema.sql`，存量库迁移脚本位于 `guarantee-web/src/main/resources/db/migration/`。

## 许可

[Apache License 2.0](LICENSE) · Copyright 2026 MRZBC
