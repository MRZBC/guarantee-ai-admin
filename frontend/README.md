# 智能电子保函运营管理平台 · 前端（guarantee-ai-admin / frontend）

基于 **Vue 3 + TypeScript + Vite + Element Plus + ECharts + Pinia + Axios + vue-router 4** 的后台管理 SPA，
对接 Spring Boot 后端（默认 `http://localhost:8080`，接口前缀 `/api`）。

本目录是独立的顶层前端工程，**不参与任何 Maven 模块构建**，不影响仓库根目录的 `pom.xml`。

---

## 环境要求

| 依赖 | 版本 |
| --- | --- |
| Node.js | 24.x（已在本机验证 v24.13.0） |
| npm | 11.x（已验证 11.6.2） |

## 快速开始

```bash
cd frontend

# 1. 安装依赖
npm install

# 2. 启动开发服务器（http://localhost:5273）
npm run dev

# 3. 生产构建（先做 TypeScript 类型检查，再打包到 dist/）
npm run build

# 4. 本地预览构建产物
npm run preview
```

开发服务器监听 **5273** 端口，并把 `/api` 反向代理到后端（默认 `http://localhost:8081`，
见 `vite.config.ts` 的 `server.proxy`，`changeOrigin: true`）。
因此本地开发**无需配置 CORS**，只要后端已启动即可。

> 不用 Vite 默认的 5173，是因为部分 Windows 机器保留了动态端口段 5121–5220，
> 5173 在其中会导致绑定失败（`EACCES: permission denied`）。
> 查看本机保留段：`netsh int ipv4 show excludedportrange protocol=tcp`
>
> 后端换端口时无需改代码：
> ```powershell
> $env:BACKEND_PORT=8082; npm run dev
> ```

### ⚠️ 依赖版本：`vue-router` 必须钉死在 4.5.1（不要升到 4.6.x）

`package.json` 里写的是**精确版本** `"vue-router": "4.5.1"`，这是刻意的，不是随手一写：

- **症状**：`vue-router` ≥ 4.6（我们中过 4.6.4）配新版 Edge 时，只要**当前激活的标签页**是本应用，
  Edge 窗口就**无法最小化**——点最小化后窗口立刻弹回（或根本不动）；切到别的标签页、或换 Chrome 都正常。
  实测（10–25ms 精度采样窗口状态）：4.6.4 下 `ShowWindow(SW_MINIMIZE)` 完全不生效；
  换到 **4.5.1** 后同一页面最小化后**稳定保持**。
- **原因**：Edge 处理页面历史状态（`history.replaceState` + 可见性切换）的时序与 vue-router 4.6 的实现
  相互触发"页面被反复激活"；Chrome 不受影响，Edge 官方暂无修复计划。
- **为什么不能写 `^4.5.0`**：宽松范围会在 `npm install` 时自动升到 4.6.x —— 我们就是这样中的招。
- **改了依赖之后必须重启 dev server**：Vite 的预构建缓存（`node_modules/.vite`）不会自动换版本，
  否则页面仍然加载旧版。确认实际生效的版本：
  ```powershell
  # 输出里应出现 "vue-router v4.5.1"，出现 4.6.x 说明缓存/进程还是旧的 → 重启 npm run dev
  (Invoke-WebRequest http://localhost:5273/node_modules/.vite/deps/vue-router.js -UseBasicParsing).Content | Select-String 'vue-router v4'
  ```

> 参考：[Vue3 项目 Edge 无法最小化（CSDN）](https://blog.csdn.net/weixin_65879835/article/details/160858670)、
> [Microsoft Q&A 同类反馈](https://learn.microsoft.com/zh-cn/answers/questions/5789532/web-edge)。

### 演示账号

```
用户名：admin
密码：  Admin@123
```

登录页已内置提示，可点击「一键填充」自动填入。

### 演示 AI 提问（右下角「业务分析助手」）

登录后点击右下角悬浮按钮打开助手，试试这些问题：

```
今年投标订单的保费和担保金额趋势如何？
按区域统计担保金额排名前十的机构分别是哪些？
各险种的订单数量和担保金额分布情况怎样？
```

助手会以 SSE 流式输出回答，并把过程中调用的工具（工具名 / READ / WRITE 类型 / 状态 / 耗时 / 原始参数与结果）
以可折叠卡片展示，历史会话可随时回看。

---

## 目录结构

```
frontend/
├─ index.html
├─ vite.config.ts            # 端口 5273 + /api 代理（BACKEND_PORT 默认 8081）
├─ tsconfig.json             # 应用侧 TS 配置（strict）
├─ tsconfig.node.json        # 构建脚本侧 TS 配置
├─ .env.development          # VITE_API_BASE_URL=/api
└─ src/
   ├─ main.ts                # 全局注册 Element Plus、全部图标、中文语言包
   ├─ App.vue
   ├─ env.d.ts
   ├─ api/                   # axios 实例 + 分模块的类型化接口
   │  ├─ request.ts          # 拦截器：注入 Bearer、解包 code===0、401 跳登录
   │  ├─ auth.ts / orders.ts / analysis.ts
   │  ├─ project.ts / enterprise.ts / system.ts / ai.ts
   ├─ components/
   │  ├─ ChartPanel.vue      # ECharts 通用封装（init/setOption/resize/dispose）
   │  ├─ StatCards.vue       # 指标卡片
   │  └─ AiCopilot.vue       # AI 业务分析助手（全局挂载）
   ├─ layout/AppLayout.vue   # 侧边菜单 + 面包屑 + 用户下拉 + 路由出口
   ├─ router/index.ts        # Hash 路由 + 登录守卫
   ├─ stores/                # Pinia：user（持久化）/ app（侧边栏折叠）
   ├─ types/                 # 共享 TS 接口定义
   ├─ utils/
   │  ├─ format.ts           # formatAmount / formatDate / formatDateTime / formatPercent
   │  ├─ sse.ts              # text/event-stream 手工解析（POST + fetch）
   │  ├─ chatStream.ts       # /api/ai/chat 流式调用封装
   │  ├─ echarts.ts          # ECharts 按需注册
   │  └─ storage.ts          # token / 用户信息 localStorage 读写
   └─ views/
      ├─ Login.vue
      ├─ Dashboard.vue
      ├─ orders/{TenderOrders,PerformanceOrders,OrderTable}.vue
      ├─ analysis/Overview.vue
      ├─ Projects.vue / Enterprises.vue
      └─ system/{InsuranceTypes,Orgs,Departments,Users,Roles}.vue
```

## 功能页面

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/login` | 登录 | 表单校验、演示账号提示 |
| `/dashboard` | 首页 | 指标卡片 + 趋势折线图 + 区域占比图 + 区域对比柱状图 |
| `/orders/tender` | 投标订单 | 筛选（订单号/区域/机构/险种/状态/日期）+ 分页表格 + 详情弹窗 |
| `/orders/performance` | 履约订单 | 同上，额外展示合同编号 |
| `/analysis/overview` | 数据概览 | 12 项指标 + 趋势（日/月/年切换）+ 区域分布 + 险种分布 + 机构排行 |
| `/projects` | 项目管理 | 分页表格 + 筛选 + 详情（含订单数与金额统计） |
| `/enterprises` | 企业管理 | 分页表格 + 筛选 + 详情（含项目数、订单数、保费） |
| `/system/insurance-types` | 险种配置 | 分页表格 + 新增/编辑弹窗（表单校验，对接 POST/PUT） |
| `/system/orgs` | 机构配置 | 分页只读表格 + 筛选 |
| `/system/departments` | 部门配置 | 分页只读表格 + 机构筛选 |
| `/system/users` | 用户配置 | 分页只读表格，角色以标签展示 |
| `/system/roles` | 角色配置 | 分页只读表格，权限以标签展示 |

## 关键实现说明

### 1. 统一响应与错误处理

后端返回 `{ code, message, data, traceId }`。`src/api/request.ts` 的响应拦截器在 `code === 0` 时
**直接返回 `data`**，因此业务代码里 `await pageTenderOrders(...)` 拿到的就是 `PageResult<...>` 本体；
`code !== 0` 时用 `ElMessage.error(message)` 提示并 reject；HTTP 401 会清除本地登录态并跳转 `/login`。

### 2. 路由模式

使用 **Hash 路由**（`createWebHashHistory`）。这样打包后的 `dist/` 交给任意静态服务器即可访问，
不需要额外的 history fallback 配置；开发环境地址同样可以带 hash 直接访问。

### 3. SSE over POST（重点）

浏览器原生 `EventSource` 只支持 GET 且无法自定义请求头，因此 `/api/ai/chat`（POST + Bearer 头）
不能用 `EventSource`。实现方式：

- `src/utils/sse.ts`：`fetch` + `response.body.getReader()` + `TextDecoder`，按行缓冲，
  累积 `event:` 与多行 `data:`，遇空行派发事件并 `JSON.parse`；兼容 `\r\n`（含 `\r\n` 跨 chunk 的情况）、
  多行 data、行内注释，并在流结束时 flush 残留事件；`finally` 中始终释放 reader。
- `src/utils/chatStream.ts`：POST 请求 + `AbortSignal`（「停止」按钮），把 `meta / delta / tool_call / done / error`
  分发到 `AiCopilot.vue`；对后端返回 JSON 包装体（而非事件流）的情况做了兜底识别。

### 4. ECharts

未引入 `vue-echarts`。`src/components/ChartPanel.vue` 负责 `init / setOption / resize / dispose`，
同时监听 `window.resize` 与 `ResizeObserver`（侧边栏折叠、栅格变化也能自适应），
组件卸载时 `dispose()`。`src/utils/echarts.ts` 使用 `echarts/core` 按需注册用到的图表与组件。

## 与后端的契约约定

- 所有列表接口的查询参数中，空字符串/null 会被转换成 `undefined`（不发送该参数）。
- `premiumRate` 按百分数值展示（例如 `0.5` → `0.50%`），与险种配置中 `baseRate` 的录入口径一致。
- 金额统一按「元」展示，千分位 + 两位小数。
- 订单/项目/企业/用户的 `status`、项目的 `projectType`、企业的 `industry` 等字段，
  优先使用后端返回的 `xxxName`；后端未返回名称时，用前端字典兜底映射，兜底不到则原样展示。
