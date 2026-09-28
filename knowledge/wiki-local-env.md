---
type: technology
title: 本地开发环境与端口
status: active
related:
  - 技术 - 平台技术栈与运行时差异
tags: [environment, ports, local-dev, windows]
---

> 本项目的本地环境有几个**不得已的偏离**（3307、5273），以及一批
> 「普通进程不会自启」「不要 mvn clean」这类会浪费半小时的坑。
> 换机器或新会话时先看这一页。

## 是什么

| 组件 | 端口 | 说明 |
| --- | --- | --- |
| MySQL | **3307** | 3306 被本机已装的 MySQL 5.7 占用；开发用便携版 8.0.29 |
| Redis | 6379 | 便携版 |
| 后端 | 8080 / 8081 | `application.yml` 的 `server.port`；README 示例中出现 8081 |
| 前端（Vite dev） | **5273** | **不是** Vite 默认的 5173，原因见下 |
| 后端承载前端 | 同后端端口 | `frontend/dist` 由后端挂载，支持单端口演示 |

数据库连接：

| 项 | 值 |
| --- | --- |
| Host | `127.0.0.1`（或 `localhost`） |
| Port | `3307` |
| Database | `guarantee_ai_admin` |
| User / Password | `guarantee` / `guarantee@2026` |
| root 密码 | 空 |

全部可用环境变量覆盖：`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD`
（`docker-compose.yml` 用标准 3306）。

模型：`DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`（默认 `https://api.deepseek.com`）、
`DEEPSEEK_MODEL`（默认 `deepseek-chat`）。

## 一键启停

```powershell
# 启动 MySQL(3307) + Redis(6379)
pwsh -File scripts/start-local-env.ps1

# 连后端一起启动
pwsh -File scripts/start-local-env.ps1 -WithBackend

# 停止
pwsh -File scripts/start-local-env.ps1 -Stop
```

## 为什么重要（几个不显然的偏离）

1. **MySQL 必须同时绑 `127.0.0.1` 与 `::1`。**
   Windows 上 `localhost` 会**优先解析到 IPv6 的 `::1`**；只绑 IPv4 时，
   JDBC 用 `localhost` 会直接连不上。两个回环都绑上即可，且**不会暴露到局域网**。
   验证：`Test-NetConnection -ComputerName ::1 -Port 3307 -InformationLevel Quiet`

2. **前端用 5273 而不是 5173。**
   部分 Windows 机器（启用 Hyper-V / WSL2 / Docker Desktop 后）会保留动态端口段
   5121–5220，5173 落在其中，绑定直接失败并报 `EACCES: permission denied`。
   查看本机保留段：`netsh int ipv4 show excludedportrange protocol=tcp`

3. **后端端口通过环境变量对齐，前端不用改代码。**
   Vite 代理目标由 `vite.config.ts` 的 `BACKEND_PORT` 决定，默认 `http://localhost:8081`：
   ```powershell
   $env:BACKEND_PORT=8082; npm run dev
   ```

4. **MySQL / Redis 是普通进程，不会随系统自启**（也没注册成 Windows 服务，注册需要管理员权限）。
   机器重启或终端关闭后就没了，需要手动拉起。

## 两种起前端的方式

| | 方式 A（后端承载 dist） | 方式 B（Vite dev） |
| --- | --- | --- |
| 访问地址 | 后端端口 | `http://localhost:5273` |
| 前端改动 | 需重新 `npm run build` | 热更新，即时生效 |
| 跨域 | 同源 | 由 Vite 代理转发 |
| 适合 | 演示 / 验收 / 单端口部署 | 前端开发 |

方式 A 下前端用 hash 路由，因此 `http://localhost:8080/#/dashboard` 这类深链接
也由同一个地址承载，静态部署不需要 history fallback。

局域网访问：dev server 默认监听 `0.0.0.0`，同事/手机可用本机 IPv4 访问
（Vite 启动时打印 `➜ Network:`）。前端接口走相对路径 `/api` 经代理转发，**不会跨域**。
只想本机访问：`$env:DEV_HOST='127.0.0.1'; npm run dev`。

## 坑 / 限制

- **`mvn clean` 会打断 IDEA 运行实例**。IDEA 从 `target/classes` 直接运行源码，
  `clean` 删掉它正在用的目录后进程不会立刻崩，但后续懒加载的类会抛
  `NoClassDefFoundError`。要重建就先停掉 IDEA 的运行实例。
  反过来，`application.yml` 的改动**不需要重新打包**就生效。
- **`frontend/dist` 不存在时首页 404**，但后端正常工作（启动日志有提示）。
- **首次启动会灌演示数据，约 30–60 秒**，不是卡死。
- **`/actuator/health` 免登录**，是判断「服务起来了没」最快的办法
  （应返回 `{"status":"UP"}`）。
- **直接访问 `/api/**` 得到 401/JSON 是正常的** —— 那不是网页。

## 打不开页面时的排查顺序

```text
1. 拼写          localhost，不是 loclhost / localhos
2. 端口          Get-NetTCPConnection -State Listen | Where-Object LocalPort -in 8080,8081
                 （注意 application.yml 的 server.port 只影响后端；前端 dev 在 5273）
3. 后端活着吗    http://localhost:<port>/actuator/health 应返回 {"status":"UP"}
4. 是不是只开了 API  业务接口都在 /api/**，不是网页
5. 前端能开但没数据  dev 模式下确认 Vite 代理目标与后端端口一致（默认 8081）
6. EACCES 绑不上端口 端口落在 Windows 保留段，换一个
```
