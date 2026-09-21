# Git 分支与提交规范

> 适用范围：`guarantee-ai-admin` 全部模块与前端。
> 目标：主分支随时可发布；改动来源可追溯；提交历史可读、可自动生成变更日志。

---

## 一、分支模型

采用精简版 Git Flow：两条长期分支 + 三类短期分支。

```
                         ┌──────────────────────────────────────────┐
                         │  master（主分支 / 生产）                  │
                         │  只接受 release_* 与 hotfix/* 合入        │
                         └───────▲──────────────────────┬───────────┘
                                 │                      │
              release_v1.0.0 合入 │                      │ 从 master 拉出
                                 │                      ▼
                         ┌───────┴──────────┐    ┌──────────────────┐
                         │ release_v1.0.0   │    │ hotfix/xxx       │
                         │ （发布分支）      │    │ （热修复分支）    │
                         └───────▲──────────┘    └──────────────────┘
                                 │ 从 develop 拉出
                         ┌───────┴──────────────────────────────────┐
                         │  develop（开发分支 / 日常集成）            │
                         └───────▲──────────────────────────────────┘
                                 │ 从 develop 拉出，完成后回合 develop
                    ┌────────────┴─────────────┐
                    │  feature/<模块>-<简述>    │
                    │  （功能分支）             │
                    └──────────────────────────┘
```

### 1.1 集成分支（长期存在）

| 分支 | 角色 | 来源 | 合入目标 | 规则 |
|---|---|---|---|---|
| `master` | 主分支 / 生产分支 | 初始化 | — | **随时可发布**。禁止直接 push，只能由 `release_*` 或 `hotfix/*` 合并进入，合入即打 tag |
| `develop` | 开发分支 / 日常集成 | `master` | `release_*` | 日常开发的集成分支，feature 完成后回合到此。禁止直接 push 业务代码 |
| `release_v<版本>` | 发布分支 | `develop` | `master` + `develop` | 进入发布准备期：只接受 bugfix、版本号、文档改动，不再接受新功能 |

**版本分支命名**：`release_v` + 语义化版本号

```
release_v1.0.0      # 首个正式版
release_v1.1.0      # 新增功能
release_v1.1.1      # 补丁修复
release_v2.0.0-rc1  # 预发布（可选）
```

### 1.2 功能分支（短期，用完即删）

命名：`feature/<模块>-<简述>`

`<模块>` 建议使用本仓库的 scope（见 §2.2），用短横线连接小写英文/拼音。

```bash
feature/order-export                 # 订单导出
feature/ai-multi-turn-memory         # AI 多轮记忆（后续阶段）
feature/frontend-copilot-tool-card   # 前端 Copilot 工具卡片
feature/analysis-region-drilldown    # 区域分析下钻
```

### 1.3 热修复分支（短期，用完即删）

命名：`hotfix/<简述>`，**必须从 `master` 拉出**，修复线上问题。

```bash
hotfix/login-token-expired
hotfix/order-amount-precision
hotfix/sse-stream-cutoff
```

修复完成后需**同时合回 `master` 与 `develop`**，否则下一次发布会把 bug 带回来。

---

## 二、分支流转流程

### 2.1 开发新功能

```bash
git checkout develop
git pull --ff-only origin develop
git checkout -b feature/order-export

# ... 开发、提交 ...

git checkout develop
git pull --ff-only origin develop
git merge --no-ff feature/order-export      # --no-ff 保留分支历史
git push origin develop
git branch -d feature/order-export
```

### 2.2 发布版本

```bash
git checkout develop
git checkout -b release_v1.0.0
# 只允许：bugfix、版本号、CHANGELOG、文档
git commit -m "chore(release): 发布 v1.0.0"

git checkout master
git merge --no-ff release_v1.0.0
git tag -a v1.0.0 -m "v1.0.0"
git push origin master --follow-tags

# 把发布期的修复带回 develop
git checkout develop
git merge --no-ff release_v1.0.0
git branch -d release_v1.0.0
```

### 2.3 线上热修复

```bash
git checkout master
git checkout -b hotfix/order-amount-precision
git commit -m "fix(order): 修正保费金额四舍五入精度"

git checkout master
git merge --no-ff hotfix/order-amount-precision
git tag -a v1.0.1 -m "v1.0.1"

git checkout develop
git merge --no-ff hotfix/order-amount-precision   # 必须回合！
git branch -d hotfix/order-amount-precision
```

---

## 三、提交信息规范

采用 **Conventional Commits 1.0.0**，主题用中文描述。

### 3.1 格式

```
<type>(<scope>): <主题>

<正文：说明「为什么」这么改，而不是「改了什么」>

<页脚：关联 issue / 破坏性变更>
```

- **主题行**：≤ 72 字符，祈使句，结尾不加句号
- **正文**：每行 ≤ 72 字符，可多段，说明动机与影响
- **页脚**：`Closes #12`、`Refs #34`；破坏性变更写 `BREAKING CHANGE: ...`

### 3.2 type 取值

| type | 用途 |
|---|---|
| `feat` | 新增功能 |
| `fix` | 修复缺陷 |
| `docs` | 仅文档改动 |
| `style` | 不影响逻辑的格式调整（空格、分号、换行） |
| `refactor` | 重构（既非新增功能也非修缺陷） |
| `perf` | 性能优化 |
| `test` | 新增或修改测试 |
| `build` | 构建脚本、Maven/npm 依赖变更 |
| `ci` | CI 配置、流水线脚本 |
| `chore` | 杂项（版本号、脚手架、不影响源码的改动） |
| `revert` | 回滚某次提交 |

### 3.3 scope 取值（本项目）

| scope | 对应 |
|---|---|
| `common` | guarantee-common |
| `auth` | guarantee-auth |
| `system` | guarantee-system |
| `order` | guarantee-order |
| `analysis` | guarantee-analysis |
| `ai` | guarantee-ai |
| `web` | guarantee-web / 启动配置 |
| `frontend` | frontend 前端工程 |
| `db` | schema.sql / 数据库脚本 |
| `deps` | 依赖升级 |
| `git` | 分支/提交规范、Git 钩子与协作配置 |
| `release` | 版本发布 |

scope 可省略，跨模块改动也可写多个，如 `feat(order,analysis): ...`。

### 3.4 示例

```bash
# 新功能
feat(order): 新增投标订单导出接口

# 修缺陷（带 issue 号）
fix(ai): 修复 SSE 流结束后 tool_call 未落库的问题

Closes #42

# 重构
refactor(analysis): 抽出 UNION ALL 公共查询片段，消除重复 SQL

# 破坏性变更
feat(api)!: 统一响应结构改为 code/message/data/traceId

BREAKING CHANGE: 原 { success, data } 结构废弃，前端需同步调整拦截器。

# 依赖升级
build(deps): 升级 Spring Boot 至 4.1.1

# 文档
docs: 补充 Git 分支命名与提交信息规范

# 回滚
revert: feat(order): 新增投标订单导出接口

This reverts commit 1a2b3c4.
```

### 3.5 禁止的提交信息

```
❌ update            （无意义）
❌ 修改bug           （无 type、无范围）
❌ fix: 改了东西。    （描述含糊、句尾句号）
❌ feat(order): 新增投标订单导出接口，同时重构了 analysis 模块并升级了依赖
                     （一次提交混多个不相关改动，应拆分）
```

---

## 四、工具支持

仓库已内置以下配置，克隆后执行一次即可生效：

```bash
# 1) 启用版本化的 Git 钩子（校验提交信息）
git config core.hooksPath .githooks

# 2) 启用提交信息模板（git commit 不带 -m 时会带出模板）
git config commit.template .gitmessage
```

也可以一键执行（任选其一）：

```bash
# Windows / PowerShell
pwsh -File scripts/setup-git.ps1

# Linux / macOS / Git Bash
sh scripts/setup-git.sh
```

### 校验效果

```bash
$ git commit -m "update"
✖ 提交信息不符合规范：update

  正确格式：<type>(<scope>): <描述>
  允许的 type：feat fix docs style refactor perf test build ci chore revert
  示例：feat(order): 新增投标订单导出接口
  详见 docs/GIT_CONVENTION.md
```

如需临时跳过（不推荐）：`git commit --no-verify`。

---

## 五、常用命令速查

```bash
# 建功能分支
git checkout develop && git pull --ff-only && git checkout -b feature/<模块>-<简述>

# 看当前提交将生成什么历史
git log --oneline --graph --decorate -20

# 合并功能分支（保留分支痕迹）
git checkout develop && git merge --no-ff feature/<模块>-<简述>

# 发布
git checkout -b release_v1.0.0 develop
git checkout master && git merge --no-ff release_v1.0.0 && git tag -a v1.0.0 -m "v1.0.0"

# 按提交规范生成 CHANGELOG（示例，未内置工具）
git log --oneline --no-merges v1.0.0..HEAD
```
