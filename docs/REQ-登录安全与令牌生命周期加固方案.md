# 需求：登录安全与令牌生命周期加固

| 项目 | 内容 |
| --- | --- |
| 文档名称 | 登录安全与令牌生命周期加固方案 |
| 文档版本 | v1.1（**六项已全部实施并通过测试**；含 §17 实施记录与 4 处实现偏差说明） |
| 适用系统 | 智能电子保函运营管理平台（guarantee-ai-admin） |
| 涉及模块 | `guarantee-auth`、`guarantee-common`、`guarantee-system`、`guarantee-web`、`frontend` |
| 依据文档 | `docs/REQ-系统管理助手能力.md`（v1.4）、`docs/DEC-逻辑删除设计方案.md`、`docs/REQ-操作审计页面.md` |
| 关联需求 | 沿用 AC-22（统一审计）、SYS-P-12（写权限清单断言）、LD-T8（防账号枚举）；本文新增 AC-42~AC-56、TEST-24~TEST-35 |
| 现状结论 | 六项中 **3 项是真实安全缺口（可被利用）**、1 项是可用性缺口、1 项是功能缺失、1 项是注释与实现不符。**全部可在不更换鉴权框架的前提下补齐** |
| 前置决策 | **不引入 Sa-Token**（理由见 §1.2，结论已确认） |

---

## 0. 先看结论：需要你拍板的 6 件事

| 编号 | 决策项 | 建议 | 影响 |
| --- | --- | --- | --- |
| **D1** | 账号锁定阈值与时长 | **15 分钟内连续 5 次失败 → 锁 15 分钟** | 阈值过低会误伤手滑用户；过高则失去防爆破意义 |
| **D2** | IP 维度是否启用 | **启用，阈值 20 次/15 分钟**，且默认**不信任** `X-Forwarded-For` | 阈值过低会在 NAT/办公网出口误伤整栋楼 |
| **D3** | 令牌撤销的 Redis 故障策略 | **默认改为 fail-closed**，保留 `fail-open` 作为需重启的应急开关 | 这是本方案唯一"用可用性换安全"的改动，**必须业务确认** |
| **D4** | 令牌有效期语义 | **拆分**为「空闲超时 240 分钟」+「绝对上限 720 分钟」 | 唯一的行为回退：放置不管的会话会比现在更早失效（设 720/720 可完全退化为现状） |
| **D5** | 管理员踢自己的当前会话 | **允许**，接口回传 `selfKicked=true`，前端提示并跳登录页 | 禁止会更"安全"但不符合"怀疑凭据泄漏、我要立刻退出"的真实诉求 |
| **D6** | 在线会话的入口形态 | **挂在「用户配置」页的抽屉**，不新增菜单与路由 | 省掉新菜单 + 路由守卫 + 权限联动的整套前端改造 |

> **D3 与 D4 是仅有的两项会改变现有外部可观察行为的改动**，其余四项是纯加固（对正常用户无感）。

---

## 1. 背景

### 1.1 来源

本方案承接一次针对鉴权链路的代码复核。触发问题是「是否引入 Sa-Token」，复核结论是**不引入**（§1.2），但复核过程中确认了六项与框架选型无关的真实缺口，单独成文。

### 1.2 为什么不引入 Sa-Token（结论已确认，此处仅存档理由）

现状并非"缺框架"，而是"已按另一条路线实现完毕"：

| Sa-Token 能力 | 本系统现状 | 判定 |
| --- | --- | --- |
| 登录 / 登出 / 会话 | `AuthService` + `JwtTokenProvider`，已实现且已测 | 重复 |
| 权限校验 | 约 60 处 `@PreAuthorize("hasAuthority(...)")` + `Permissions` 编译期常量 | 等价，且是 Spring 标准 |
| 踢人 `kickout(userId)` | `UserTokenRevocation` 按 `iat` 与撤销时刻比较，**覆盖面比单个 jti 更准**（多设备/多标签页/重新登录前的旧令牌） | 已有的更强 |
| 分布式会话 | `TokenRevocationService`（jti 白名单）+ Redis | 重复 |
| SSO / OAuth2 / 网关鉴权 | 单体单端，无第二个应用、无网关 | **不需要** |
| 同账号互斥 / 在线用户列表 | **缺失** | 唯一真实缺口 → 本方案 AUTH-05 自研补齐 |

另外两条决定性事实：

1. **AI 工具链路有独立的第二套授权通路**。工具执行发生在 Reactor 线程，`SecurityContext` 与 `CurrentUser` ThreadLocal 均已失效，权限只能随 `ToolContext` 下传（`AiPermissionGuard` / `AiToolRegistry` / `AiDataScopeResolver`）。**Sa-Token 同样依赖当前线程上下文，换框架不会让这条通路变简单**。
2. **换框架的实际代价**是重做 60 处 `@PreAuthorize`、`GlobalExceptionHandler` 的 403 映射（`PermissionDeniedMappingIT` 正是为修这个缺陷而存在）、`SecurityConfig` 里的 CORS 与 `DispatcherType.ASYNC` 放行（SSE 流式响应命门）、`PermissionCatalog` 权限种子，以及至少 6 个直接操纵 `CurrentUser` 的测试类——用于换取上述表格里唯一一项真实缺口。性价比为负。

> **触发器**：若未来要做**单点登录 / 开放 API 多端接入 / 拆微服务加网关**，本结论应当重新评估。届时 Sa-Token 的 `sa-token-sso`、OAuth2、`sa-token-gateway` 的性价比会翻转。

### 1.3 目标

| 编号 | 目标 |
| --- | --- |
| G-1 | 关闭"无限次尝试密码"这个攻击面（当前完全敞开） |
| G-2 | 消除"缓存故障 = 静默降级为无鉴权"的隐性风险 |
| G-3 | 消除"生产环境用默认密钥也能启动"的配置陷阱 |
| G-4 | 给会话加上空闲边界，同时**不得**削弱既有的用户级撤销能力 |
| G-5 | 补齐管理员运维所需的"谁在线 / 把他踢下去"能力 |
| G-6 | 修正登出在令牌过期时返回 401 的言行不一 |

### 1.4 非目标（明确排除）

| 编号 | 排除项 | 理由 |
| --- | --- | --- |
| N-1 | 引入任何新的鉴权框架 | 见 §1.2 |
| N-2 | 密钥轮换（多密钥 / `kid`） | 需要双密钥并存 + 过渡期校验，独立立项 |
| N-3 | 刷新令牌（refresh token）双令牌体系 | 见 §4.4.4，本方案的空闲续期已覆盖需求，且双令牌会引入撤销绕过风险 |
| N-4 | 密码策略（复杂度 / 定期改密 / ~~首次登录强制改密~~） | 承接既有 D-2a。**⚠️ 2026-09-23 收窄**：「首次登录强制改密」已被 **P-10**（`docs/REQ-用户管理新增与修改.md`，D1=C）采纳并交付——固定默认密码 + `must_change_password` 列 + 服务端强制闸门 + 自助改密页。**仅"复杂度 / 定期改密 / 历史密码不可复用"仍留在 N-4**（P-10 的 D8=A 明确采用极简策略：8~64 位、不同于旧密码、不等于默认密码） |
| N-5 | 二次认证（敏感操作再验密码） | 独立立项 |
| N-6 | 登录日志独立落库 | 本期只落应用日志（WARN）；若需可检索的登录审计，另行立项 |
| N-7 | 踢出操作**中断进行中的 SSE 流** | 技术上需要在流内轮询撤销状态，收益不匹配成本（见 §4.5.6） |

---

## 2. 现状核对（代码级实测）

### 2.1 现状总表

| # | 项 | 现状（实测） | 证据位置 | 性质 |
| --- | --- | --- | --- | --- |
| 1 | 登录防爆破 | **完全没有**。密码错误只 `log.warn` 后抛 `LOGIN_FAILED`，无计数、无锁定、无限流 | `AuthService.login` L48-75 | 🔴 安全缺口 |
| 2 | 撤销的故障策略 | **fail-open**：Redis 异常时 `return true`（放行） | `TokenRevocationService.isActive` L45-52；`UserTokenRevocation.issuedAfterRevocation` L74-88 | 🔴 安全缺口 |
| 3 | JWT 密钥 | `application.yml` 带**可用默认密钥**；`JwtTokenProvider` 只校验长度 ≥32 字节 | `application.yml` L78；`JwtTokenProvider` L36-41 | 🔴 安全缺口 |
| 4 | 令牌有效期 | 固定 720 分钟（12h），无空闲边界、无续期 | `JwtProperties.expireMinutes` | 🟡 可用性缺口 |
| 5 | 在线会话 / 强制下线 | **缺失**。`UserTokenRevocation` 能做用户级撤销，但没有"列出会话""踢出单个会话"的能力，也没有运维入口 | 全仓无 `SessionRegistry` 类 | 🟡 功能缺失 |
| 6 | 登出幂等 | Javadoc 写"即使令牌已过期也返回成功"，但 `/api/auth/logout` **不在 `permitAll` 白名单**，走 `.anyRequest().authenticated()`，令牌失效时实际返回 401 | `SecurityConfig` L65-74 vs `AuthController.logout` L54 | 🔵 言行不一 |

### 2.2 三个必须在动手前知道的事实

**事实一：撤销白名单与 JWT 是两套独立的时间真相，但都挂在同一个 Redis key 上。**

`guarantee:auth:token:{jti}` 的 TTL 当前等于「JWT 剩余有效期」（`TokenRevocationService.register` L36 用 `expiration - now` 计算）。这意味着 **TTL 天然就是"空闲超时"的载体**——AUTH-04 不需要新建任何数据结构，只需要改变这个 TTL 的语义（从"剩余绝对时间"改为"空闲窗口"）。这是本方案后续多项设计得以低成本落地的基础。

**事实二：用户级撤销用 `iat` 比较，这给"续期"划下了一条不可越过的红线。**

`UserTokenRevocation` 的判定是 `issuedAt > 撤销时刻`。任何**重新签发**令牌的续期方案都会产生新的 `iat`，从而使"已被撤销用户的旧令牌"重新变为有效。因此 AUTH-04 必须采用**原地延长 TTL、绝不重签 JWT** 的形态（§4.4.3）。这不是风格偏好，是安全约束。

**事实三：`guarantee-system` 不能依赖 `guarantee-auth`，但会话管理接口天然属于系统管理域。**

依赖方向是 `guarantee-auth → guarantee-system`。既有代码已经用「**接口放 `guarantee-common`、实现放 `guarantee-auth`**」解决过同类问题：`UserTokenRevoker`（common 接口）+ `UserTokenRevocation`（auth 实现）。AUTH-05 严格沿用这个模式（§4.5.4）。

---

## 3. 需求编号与范围

| 编号 | 名称 | 优先级 | 依赖 | 建议批次 |
| --- | --- | --- | --- | --- |
| **AUTH-01** | 登录失败防爆破 | P0 | 无 | 批次 2 |
| **AUTH-02** | 令牌撤销的 Redis 故障策略（默认 fail-closed） | P0 | 无 | 批次 3 |
| **AUTH-03** | JWT 密钥生产环境强校验 | P0 | 无 | 批次 1 |
| **AUTH-04** | 令牌空闲超时与活动续期 | P1 | 无（但与 AUTH-05 联动） | 批次 4 |
| **AUTH-05** | 在线会话列表与强制下线 | P1 | AUTH-04（复用 TTL 反推最后活动时间） | 批次 5 |
| **AUTH-06** | 登出幂等修正 | P2 | AUTH-05（需取过期令牌的 claims 以清索引） | 批次 1（接口部分）/ 批次 5（索引部分） |

**批次排序理由**：AUTH-03 / AUTH-06 改动面最小、无行为回退，先落地建立信心；AUTH-01 独立且收益最高，紧随其后；AUTH-02 有行为回退（需 D3 确认）；AUTH-04 改变有效期语义（需 D4 确认）；AUTH-05 依赖 AUTH-04 的数据形态。

---

## 4. 详细需求

### 4.1 AUTH-01 登录失败防爆破

#### 4.1.1 需求描述

对登录接口施加**双维度**（账号 + 客户端 IP）的失败计数与临时锁定，且**不得破坏既有的防账号枚举性质**。

#### 4.1.2 参数（对应 D1 / D2）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `guarantee.auth.login-guard.enabled` | `true` | 总开关 |
| `...max-failures-per-user` | `5` | 账号维度失败阈值 |
| `...max-failures-per-ip` | `20` | IP 维度失败阈值（更高，避免 NAT 误伤） |
| `...failure-window-minutes` | `15` | 失败计数窗口 |
| `...user-lock-minutes` | `15` | 账号锁定时长 |
| `...ip-lock-minutes` | `15` | IP 锁定时长 |
| `...trust-forwarded-header` | `false` | 是否信任 `X-Forwarded-For` 取真实客户端 IP |

#### 4.1.3 执行流程（`AuthService.login`）

```
1. LoginAttemptGuard.assertNotLocked(username, ip)
     ├─ 命中锁定 → 抛 BizException(LOGIN_LOCKED, "…请 N 分钟后重试")   ← 必须在密码校验之前
     └─ 未命中   → 继续
2. 既有校验（用户查询 → 密码 → 逻辑删除 → 停用），逻辑与顺序**保持不变**
     └─ 任一步失败 → guard.recordFailure(username, ip) → 抛原有异常
3. 全部通过 → 签发令牌（含 AUTH-05 会话登记）
4. guard.recordSuccess(username, ip)                                  ← 清账号计数
```

#### 4.1.4 四条硬性约束（缺任何一条都会引入新缺陷）

| 编号 | 约束 | 违反后果 |
| --- | --- | --- |
| **AUTH-C-01** | **计数器只依赖请求参数，不依赖数据库查询结果**：用户名不存在时也必须递增计数 | 若只有"存在的用户名"会被锁定，攻击者就能通过"这个账号会不会被锁"判断账号是否存在——直接击穿 LD-T8 建立的防枚举性质 |
| **AUTH-C-02** | **锁定判定必须早于 `passwordEncoder.matches`** | 否则攻击者仍能强制服务端为每次尝试执行一次 BCrypt（成本因子 10），形成 CPU 耗尽型拒绝服务；锁定的意义被削掉一半 |
| **AUTH-C-03** | **计数用 Lua 脚本原子完成 `INCR` + 首次 `EXPIRE`** | 若 `INCR` 成功而 `EXPIRE` 失败（进程被杀 / 连接中断），会留下**永不过期的计数器**，账号被**永久锁定**且无法自愈 |
| **AUTH-C-04** | **Redis 不可用时 fail-open**：记 ERROR 日志后放行 | 与既有 `TokenRevocationService` 的降级策略保持一致。登录是唯一的入口，缓存故障不应导致全站无法登录 |

> **AUTH-C-03 的 Lua 形态**（`LoginAttemptGuard` 内）：
> ```lua
> -- KEYS[1] = 计数器 key；ARGV[1] = 窗口秒数
> local n = redis.call('INCR', KEYS[1])
> if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
> return n
> ```

#### 4.1.5 客户端 IP 解析（`trust-forwarded-header` 为 false 的原因）

开发环境经 Vite 代理、生产经 Nginx 时，`request.getRemoteAddr()` 恒为 `127.0.0.1`，IP 维度会退化为**全局共享一个计数器**——只要总失败数达到 20 次，**所有人**都被锁定 15 分钟。这是引入本项时最容易踩的坑。

因此：

- 默认 `false`：直接用 `getRemoteAddr()`。此时 IP 维度在反向代理后**失效但不误伤**（退化为全局计数，阈值 20 仍高于账号维度 5，不会先于账号锁定触发，但足以拦截单机扫号）。
- 置为 `true` 时：取 `X-Forwarded-For` 的**第一个**非私有地址；**仅在应用确实部署在可信反向代理之后时才可开启**，否则该头可被客户端任意伪造，攻击者每次换一个假 IP 即可完全绕过 IP 维度。
- `RemoteIpValve` 之类的容器级方案不在本期范围。

#### 4.1.6 响应

| 项 | 值 |
| --- | --- |
| 新增错误码 | `ResultCode.LOGIN_LOCKED(1004, "登录失败次数过多，账号已被临时锁定")` |
| 动态文案 | `BizException` 携带剩余分钟：「登录失败次数过多，请 12 分钟后重试」 |
| HTTP 状态 | 与既有 `BizException` 一致，由 `GlobalExceptionHandler.handleBizException` 返回 HTTP 200 + 业务码 |
| 与 `LOGIN_FAILED` 的关系 | **必须可区分**。若仍返回 `LOGIN_FAILED`，用户会认为"密码一直错"，反复重试并持续续期锁定窗口，形成自我锁定死循环 |

#### 4.1.7 日志

- 失败：`WARN`，字段含 `username`、`ip`、`failCount`、`remaining`。**禁止记录密码**（包括不记录长度）。
- 触发锁定：`WARN`，含 `username`、`ip`、`lockMinutes`、`reason=USER|IP`。
- 成功：沿用既有 `log.info("登录成功 …")`。
- 计数兜底（Redis 故障）：`ERROR`，提示"登录失败计数不可用，已放行"。

---

### 4.2 AUTH-02 令牌撤销的 Redis 故障策略

#### 4.2.1 需求描述

把「Redis 不可用」时的行为从**静默放行**改为**默认拒绝**，并让这个降级状态**不可能静默存在**。

#### 4.2.2 现状风险说明

`TokenRevocationService.isActive` 与 `UserTokenRevocation.issuedAfterRevocation` 在捕获到 `RuntimeException` 时均 `return true`。后果是：**Redis 宕机 = 全站鉴权退化为"只验签名"**，此时所有已登出、已踢出、因角色变更而应失效的令牌全部重新生效。而对管理员来说，系统"看起来一切正常"。

这是一个典型的"故障态 = 最弱安全态 + 无任何信号"的组合，必须消除。

#### 4.2.3 目标行为

| Redis 状态 | 读路径（`isActive` / `issuedAfterRevocation`） | 写路径（`register` / `revoke`） |
| --- | --- | --- |
| 正常 | 按实际值判定 | 正常写入 |
| 故障 + `fail-closed`（默认） | **返回"无效"** → 401 | **抛异常**，由调用方决定（见 §4.2.4） |
| 故障 + `fail-open`（应急） | 放行 | 记 WARN 后继续 |

配置：

```yaml
guarantee:
  auth:
    revocation:
      # fail-closed：Redis 不可用时拒绝所有令牌校验（默认，安全优先）
      # fail-open  ：Redis 不可用时放行（可用性优先，仅限应急，改配置需重启）
      failure-mode: fail-closed
```

#### 4.2.4 【关键】fail-closed 下 `register` 失败必须让登录失败

这是一个不写进文档就一定会踩的坑：

> 登录时 `revocationService.register(...)` 把 jti 写入白名单。在 fail-closed 模式下，**写入失败的令牌立刻就是无效令牌**——用户会看到"登录成功"，随后**每一个请求都 401**，且刷新页面、重新登录都无法恢复（只要 Redis 仍然故障）。前端 `request.ts` 的 401 分支会不断 `redirectToLogin()` + `window.location.reload()`，表现为"登录页疯狂刷新"。

因此：

| 场景 | fail-closed 下的要求 |
| --- | --- |
| `register` 抛异常 | **登录整体失败**，返回 `ResultCode.AUTH_UNAVAILABLE(1005, "认证服务暂时不可用，请稍后重试")`，不得返回成功 |
| `revoke`（登出）抛异常 | 返回 `AUTH_UNAVAILABLE` 业务码；前端**照常清理本地登录态**（本地已无令牌可用），并提示"已退出登录，但服务端会话可能未完全失效" |

`register` 的异常必须从"仅记 WARN 后吞掉"改为**向上抛出**（或在 fail-closed 下抛出）。

#### 4.2.5 让降级状态可见：健康检查

新增 `RevocationHealthIndicator`（`guarantee-auth`）：

- `PING` Redis 并读取一个哨兵 key；失败时 `Health.down().withDetail("failureMode", ...)`。
- 结果体现在 `GET /actuator/health` 的 `authRevocation` 组件上（该端点已是 `permitAll`）。
- **必须保持** `management.endpoint.health.show-details` 为默认的 `never`，否则未认证的调用者能读到 Redis 连接信息。

#### 4.2.6 运维约定（写入文档，不写代码）

- Redis 从"可选缓存"升级为**鉴权链路的强依赖**：生产部署必须具备哨兵 / 集群，并与数据库同级纳入监控与告警。
- 应急流程：确认 Redis 长时间无法恢复 → 改 `failure-mode: fail-open` → 重启 → **恢复后必须立即改回并再次重启**。每次切换记入运维记录。
- **不提供**运行期动态切换开关：动态开关会让"当前是否处于降级状态"变成需要额外查询的隐式事实，与 §4.2.5 的"降级必须可见"相矛盾。

---

### 4.3 AUTH-03 JWT 密钥生产环境强校验

#### 4.3.1 现状

`application.yml` L78 提供**可用的**默认密钥 `guarantee-ai-admin-local-dev-secret-key-please-change-in-production`。注释写了"生产环境必须通过环境变量覆盖"，但**没有任何机制强制这一点**：忘记设置 `JWT_SECRET` 时应用照常启动，并且用一个公开在代码仓库里的密钥为所有令牌签名。攻击者可自行签发任意用户的令牌（含 ADMIN）。

#### 4.3.2 目标行为

| 场景 | 期望 |
| --- | --- |
| 本地开发（默认 profile） | 零配置启动，沿用内置默认密钥，**不报错** |
| `prod` profile 且未设置 `JWT_SECRET` | **启动失败**，错误信息含修复命令 |
| `prod` profile 且 `JWT_SECRET` = 内置默认值 | **启动失败**，明确指出"检测到内置默认密钥" |
| `prod` profile 且自定义密钥但长度 < 32 字节 | **启动失败**（既有校验，保留） |
| 任意 profile 下启动成功 | 日志打印密钥**指纹**（SHA-256 前 8 位十六进制 + 字节长度） |

#### 4.3.3 实现方式

1. 新增 `guarantee-web/src/main/resources/application-prod.yml`：

   ```yaml
   guarantee:
     auth:
       jwt:
         # 刻意不提供默认值：未设置 JWT_SECRET 时应用启动失败
         secret: ${JWT_SECRET}
   ```

   > **实测纠正（v1.1，重要）**：原以为"占位符解析失败 → 启动失败"。实际不是——
   > Spring Boot 的 Binder 用的是**忽略无法解析的占位符**的 `PropertyPlaceholderHelper`，
   > 它**不会**抛 `Could not resolve placeholder`，而是把字面量 `"${JWT_SECRET}"`
   > （恰好 13 个字符）原样绑定下去。于是：
   > - **最终结果仍然是启动失败**（安全性质成立）；
   > - 但拦截点变成了 `JwtTokenProvider` 的长度校验，报错是
   >   **"密钥长度不足 32 字节（当前 13）"** —— 运维会去找一个 13 位的密钥，
   >   而真正的原因是环境变量根本没注入。
   >
   > 因此实现中**显式识别"值仍是 `${...}` 占位符字面量"**并给出准确提示。
   > 这类"结果对、报错把人带偏"的缺陷只有真跑一次才会暴露，
   > `JwtTokenProviderSecretTest#rejectsUnresolvedPlaceholderWithAccurateMessage` 已覆盖。

2. `JwtProperties` 新增 `rejectKnownDefault`（默认 `true`）；`JwtTokenProvider` 构造函数中：
   - 若 `rejectKnownDefault` 且 `secret` 等于内置默认串 → 抛 `IllegalStateException`，消息给出：
     `export JWT_SECRET=$(openssl rand -base64 48)` 与"不要把密钥提交进仓库"。
   - 保留既有 `keyBytes.length < 32` 校验，错误信息同样补上生成命令。
   - 增加一个**极小**的弱密钥拒绝清单（内置默认串、`secret`、`changeme`、`123456`），仅作兜底，不做密码强度评分。
3. 启动时打印指纹：`log.info("JWT 密钥指纹={} 长度={} 字节", fingerprint, length)`。
   - **禁止打印密钥本身**（与 `SensitiveFieldMasker` 的既有约定一致）。
   - 指纹用途：多实例 / 多环境部署时核对"各实例是否用了同一把密钥"，这是密钥配置错误最常见的表现形式（部分实例用默认值 + 部分用环境变量 → 令牌随机失效，且极难定位）。

#### 4.3.4 边界

- **不实现密钥轮换**（N-2）。更换 `JWT_SECRET` 会使全部存量令牌签名校验失败 → 全员重新登录。这是可接受的（也可视为一次主动的全局登出），但**必须在发布说明中告知运维**。
- 本地开发的默认密钥保持不变，`mvn test` 与本地启动的零配置体验不受影响。

---

### 4.4 AUTH-04 令牌空闲超时与活动续期

#### 4.4.1 需求描述

把当前"登录后固定 12 小时有效"改为**双边界**：

| 边界 | 载体 | 语义 |
| --- | --- | --- |
| **空闲超时** | Redis `guarantee:auth:token:{jti}` 的 TTL | 连续无请求超过该时长 → 失效（防止"人走了、浏览器还开着"） |
| **绝对上限** | JWT `exp` claim | 自登录起算，无论多活跃都不超过（限缩凭据泄漏的窗口） |

#### 4.4.2 配置

| 原配置 | 新配置 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `guarantee.auth.jwt.expire-minutes` | `guarantee.auth.jwt.absolute-expire-minutes` | `720` | **改名**，语义不变（绝对上限） |
| — | `guarantee.auth.jwt.idle-timeout-minutes` | `240` | 新增，空闲超时 |

> **退化为现状的开关**：把 `idle-timeout-minutes` 设为 `720`（等于绝对上限）即回到当前行为。这是 D4 若被否决时的落地方式。

#### 4.4.3 【核心约束】续期只能延长 TTL，绝不允许重新签发 JWT

**红线**（对应 §2.2 事实二）：

> `UserTokenRevocation.issuedAfterRevocation` 的判定是 `令牌 iat > 撤销时刻`。任何"签发一个新令牌作为续期"的方案都会产生新的 `iat`，使**已被撤销用户的旧令牌重新生效**。攻击者只需持有任一旧令牌并在撤销发生后触发一次续期，即可恢复访问权（含其在撤销前的全部权限）。**这是一条真实的提权路径，不是理论风险。**

因此续期形态固定为：

```
校验通过后：
  remaining = TTL(guarantee:auth:token:{jti})
  if remaining < idleTimeout * 0.5:                    ← 半窗节流
      newTtl = min(idleTimeout, exp - now)             ← 不得超过绝对上限
      EXPIRE guarantee:auth:token:{jti} newTtl
      EXPIRE guarantee:auth:session:{jti} newTtl       ← AUTH-05 的会话详情同步
```

| 设计点 | 理由 |
| --- | --- |
| 原地 `EXPIRE`，不重签 JWT | 保持 `iat` 不变 → 用户级撤销仍然有效（§2.2 事实二） |
| 半窗节流（`remaining < idleTimeout/2`） | 避免每个请求都产生一次 Redis 写；按 240 分钟空闲窗计算，每个令牌每 2 小时才写一次 |
| `newTtl = min(idleTimeout, exp - now)` | 防止"活跃用户把 TTL 续到超过绝对上限"，否则绝对上限形同虚设 |
| TTL 读取返回 `-2`（key 不存在） | 视为已失效，不放行 |
| 续期失败（Redis 异常） | 按 AUTH-02 的 `failure-mode` 处理；`fail-open` 下不阻断请求，`fail-closed` 下 401 |

#### 4.4.4 为什么不采用双令牌（refresh token）方案

| 方案 | 问题 |
| --- | --- |
| 双令牌（access + refresh） | 需要新端点、新存储、前端重试逻辑；**且刷新本质是重新签发 access token → 产生新 `iat` → 直接撞上 §4.4.3 的红线** |
| 响应头下发新令牌（`X-Refreshed-Token`） | 同上撞红线；且 SSE 走 `fetch` 不走 axios（`frontend/src/utils/chatStream.ts`），需要在两条链路上各实现一次 |
| **Redis TTL 承载空闲超时（本方案）** | 无新端点、无前端改动、无重签、天然覆盖 SSE |

> 本方案能被选中，根本原因是 §2.2 事实一：**Redis 白名单的 TTL 本来就已经存在**，我们只是改变了它的语义。

#### 4.4.5 对既有接口的影响

| 项 | 处理 |
| --- | --- |
| `LoginResponse.expiresIn` | **语义不变**：继续返回绝对上限的剩余秒数（12h = 43200），避免破坏 `frontend/src/types/auth.ts` 的既有字段 |
| `LoginResponse` 新增字段 | `idleTimeoutSeconds`（默认 14400）；前端类型标注为**可选**（`idleTimeoutSeconds?: number`），保证向前兼容 |
| `JwtTokenProvider.getExpireSeconds()` | 改名/拆分为 `getAbsoluteExpireSeconds()` 与 `getIdleTimeoutSeconds()`，调用点仅 `AuthService.login` |
| `revocationService.register(...)` | TTL 计算从 `expiration - now` 改为 `min(idleTimeout, expiration - now)` |

#### 4.4.6 已知边界

| 边界 | 说明 |
| --- | --- |
| 长连接期间不续期 | SSE 流式对话（`/api/ai/chat`）只在**连接建立时**经过过滤器并续期一次。若单次流持续超过空闲窗（默认 240 分钟），流不会被打断（请求已认证），但流结束后的下一个请求会 401。实际单次对话远短于此值，不构成问题 |
| 前端"即将过期"提示 | 本期不做（非目标）。`expiresIn` 与 `idleTimeoutSeconds` 已具备，未来可加无感 |
| 行为回退 | **"登录后放置不管"的会话会比现在更早失效**（现在 12h 内必然有效，改后 4h 无请求即失效）。这是 D4 需要业务确认的唯一回退项 |
| 多标签页 | 共用 `localStorage` 中的同一令牌 → 同一 `jti` → 任一标签页有请求即全部续期。符合直觉 |

---

### 4.5 AUTH-05 在线会话列表与强制下线

#### 4.5.1 需求描述

为管理员提供：**看到"谁在线"**，并**把指定会话踢下去**。这是六项中唯一的纯新增能力，也是当初评估 Sa-Token 时唯一确认的真实缺口。

#### 4.5.2 数据结构

| Key | 类型 | 内容 | TTL |
| --- | --- | --- | --- |
| `guarantee:auth:session:{jti}` | HASH | `userId`、`username`、`realName`、`loginAt`、`absoluteExpiresAt`、`loginIp`、`userAgent` | 与 `token:{jti}` 同步（AUTH-04 续期时一并顺延） |
| `guarantee:auth:sessions` | ZSET | member = `jti`，score = **空闲到期时刻**（epoch 秒） | 不需 TTL：读时按 score 惰性剔除过期成员 |

> **实现偏差（v1.1）**：原设计为「每用户一个 SET（`user-sessions:{userId}`）+ 会话 HASH」两份索引。
> 实现时改为**单个以到期时刻为 score 的 ZSet**，收益有三：
> ① **过期自清理**——读时 `ZREMRANGEBYSCORE -inf now` 即可，不需要定时任务，也不必给索引设 TTL
> （"给集合设 TTL"与"成员各自过期"本身就是矛盾的）；
> ② **少一份索引就少一类不一致**——两份索引之间的漂移正是"幽灵会话"的根源（RK-21）；
> ③ 枚举全部在线会话只需一次 `ZRANGE`，不必 `KEYS` / `SCAN`。
> "某用户的全部会话"由 `listAll()` 过滤得出——在线会话是管理员后台量级（数十条），
> 不值得为它引入第二份索引。

> **不新增独立定时清理任务**：`listAll()` 读取时若某成员已过期、或其 HASH 已不存在，
> 即 `ZREM` 并从结果中剔除（**惰性自愈**）。这沿用了 `UserTokenRevocation` 用 TTL 代替清理任务的既有做法。

#### 4.5.3 索引维护点（漏一个就会出现幽灵会话或漏踢）

| 时点 | 操作 |
| --- | --- |
| 登录成功 | `HSET session:{jti}` + `EXPIRE` + `ZADD sessions <空闲到期时刻> {jti}` |
| 活动续期（AUTH-04） | 先 `EXPIRE session:{jti}` 探活（返回 false 说明记录不存在，直接跳过），成功后再 `ZADD` 更新 score |
| 登出 | `terminate(jti)`：**先删令牌白名单 key**（真正的鉴权依据）→ 再 `DEL session:{jti}` + `ZREM sessions {jti}` |
| **用户级撤销**（`UserTokenRevocation.revokeUsers`） | `terminateAllForUsers(userIds)`：遍历 `listAll()` 过滤出目标用户，逐个 `terminate` |
| 用户被停用 / 角色变更 | 走既有 `revokeUsers` 链路，自动覆盖上一行 |

> **顺序不是随意的**（登出行）：若先清会话记录再删白名单，中途失败会得到
> **"列表里已消失、但令牌仍然可用"** 的最坏组合——管理员以为踢掉了，实际没有。
> 反过来只会留下一条可被惰性清理的展示记录，而无任何安全后果。

> **第 4 行是必需项**：用户级撤销目前只写一个时间戳，不碰索引。若不显式清理，"用户已被强制下线、但在线列表里还在"会成为一个稳定的误导性现象。

#### 4.5.4 分层与依赖方向（严格沿用既有模式）

```
guarantee-common
  └─ com.guarantee.common.security.SessionRegistry          ← 接口 + SessionInfo record
       └─ (实现) guarantee-auth.security.RedisSessionRegistry  ← Redis 实现
guarantee-system
  └─ controller.SessionController                            ← 依赖 common 接口，@PreAuthorize
```

理由见 §2.2 事实三：`guarantee-system` 不能依赖 `guarantee-auth`，而既有 `UserTokenRevoker`（common 接口）/ `UserTokenRevocation`（auth 实现）已经是同一个问题的成熟解法。

#### 4.5.5 接口设计

| # | 方法与路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| 1 | `GET /api/system/sessions?userId=&username=&page=1&size=20` | `system:session:view` | 分页返回，按 `loginAt` 倒序；复用 `PageQuery` / `PageResult` |
| 2 | `DELETE /api/system/sessions/{jti}` | `system:session:kick` | 踢出单个会话 |
| 3 | `DELETE /api/system/sessions?userId={id}` | `system:session:kick` | 踢出该用户全部会话 |

**`SessionVO` 字段**：

| 字段 | 说明 |
| --- | --- |
| `jti` | 会话标识（踢出时使用）。**允许前端可见**：它本身不是凭据，无法用于构造令牌 |
| `userId` / `username` / `realName` | 会话归属人 |
| `loginAt` | 登录时间 |
| `idleExpiresAt` | **空闲到期时间**（有请求即顺延）。直接来自 ZSet 的 score，精确 |
| `absoluteExpiresAt` | **绝对上限到期时间**（自登录起算，不会延长）。登录时写入 HASH，会话内不变 |
| `loginIp` / `userAgent` | 来源信息，用于识别异常登录 |
| `current` | 是否为**当前请求**所用的会话（与请求头中的 `jti` 比较） |

> **实现偏差（v1.1）**：原设计只有 `lastActiveAt`（最后活动时间）与 `expiresAt`，并打算由
> `token:{jti}` 的剩余 TTL **反推**最后活动时间（`now - (idle - remaining)`）。
> 实现时改为直接给 `idleExpiresAt` + `absoluteExpiresAt` 两个**到期时间**，原因：
> ① 反推在"半窗节流"下误差可达半个空闲窗口（默认 2 小时）——续期只在剩余低于半窗时才发生，
> 于是 `expiresAt - idle` 会系统性低估真实活动时间，展示出来是错的；
> ② 对使用者而言"这个会话还有多久空闲超时"比"他上次动是什么时候"更可操作；
> ③ 两个到期时间都来自已经存在的数据（ZSet score + HASH 字段），不需要任何额外读取或写入。

**踢出响应**：`Result<{ "kicked": <数量>, "selfKicked": true|false }>`。

#### 4.5.6 明确的能力边界（必须写进文档，避免验收时被当成 bug）

| 边界 | 说明 |
| --- | --- |
| **踢出对下一个请求生效，不中断进行中的 SSE 流** | 被踢用户的当前流式对话会继续输出完（该请求已在过滤器阶段通过认证）。这是 N-7 明确接受的限制 |
| **踢出 ≠ 用户级撤销** | 踢单个会话只删一个 `jti`；若用户还有其它会话（换过浏览器 / 重新登录过），它们仍然有效。要全踢请用接口 3 |
| **多标签页只显示一条会话** | 无状态 JWT 下多标签页共用同一 `localStorage` 令牌 → 同一 `jti`。这是固有形态，不是缺陷 |
| **只显示"当前已签发且未失效"的会话** | 已登出 / 已过期 / 已踢出的会话不出现（靠惰性清理保证） |

#### 4.5.7 审计（沿用 AC-22）

踢出是系统管理域的写操作，**必须**落 `ai_operation_audit`（`source=WEB`），复用既有 `OperationAuditPortAdapter`。

| 字段 | 值 |
| --- | --- |
| 实体类型 | `SESSION` |
| 动作 | `KICK` |
| 目标 | 被踢会话的 `userId` / `jti` |
| 操作者 | 当前 `CurrentUser`（既有适配器已保证"缺少操作者即拒绝"） |

> 注意：审计在**服务端**记录当前操作者身份，与被踢者不是同一人。这与"提案执行"的场景不同，实现时不要套用提案上下文的写法。

---

### 4.6 AUTH-06 登出幂等修正

#### 4.6.1 现状与后果

`AuthController.logout` 的 Javadoc 声明"即使令牌已过期也返回成功"，但 `SecurityConfig` 未把 `/api/auth/logout` 放进 `permitAll` 白名单，该请求走 `.anyRequest().authenticated()`。令牌失效时：

```
请求 /api/auth/logout（过期令牌）
  → 过滤器判定匿名 → AuthorizationFilter 拒绝
  → RestAuthErrorHandlers.commence → HTTP 401
  → 前端 request.ts 的 401 分支：
       ElMessage.error('登录状态已失效，请重新登录')   ← 用户刚刚主动点了登出
       redirectToLogin() → window.location.reload()   ← 一次无意义的整页刷新
```

即"主动登出"这条最正常的路径，在令牌恰好过期时会给出一条错误提示并整页刷新。

#### 4.6.2 修正内容

| # | 改动 | 说明 |
| --- | --- | --- |
| 1 | `SecurityConfig` 的 `authorizeHttpRequests` 增加 `.requestMatchers("/api/auth/logout").permitAll()` | 与 `/api/auth/login` 同级 |
| 2 | `AuthController.logout` 捕获 `ExpiredJwtException` 并读取 `ex.getClaims()` | jjwt 的 `ExpiredJwtException` **携带已解析的 claims**。据此仍可取出 `jti` 与 `uid`，完成 AUTH-05 的索引清理 |
| 3 | 保持"任何情况下都返回 `Result.ok()`" | 与 Javadoc 的承诺一致 |

**安全性说明**：登出端点只删除**请求中呈现的令牌**所对应的 `jti`，不提供任何其它能力。要调用它必须先持有该令牌——此时调用者本来就已持有凭据，登出不会带来新的攻击面。

> 第 2 点是 AUTH-05 的配套要求：若过期令牌登出时拿不到 `jti`，就会在在线列表里留下幽灵会话。惰性清理能兜住，但那意味着管理员会短暂看到不存在的会话。

---

## 5. Redis Key 总览

| Key | 状态 | 类型 | TTL | 引入 |
| --- | --- | --- | --- | --- |
| `guarantee:auth:token:{jti}` | 既有，**语义变更** | String | AUTH-04 起为**空闲窗口**（原为剩余绝对时间） | — |
| `guarantee:auth:user-revoked:{userId}` | 既有 | String（时间戳） | 13h | — |
| `guarantee:auth:login-fail:user:{username}` | **新增** | String（计数） | 失败窗口 15m | AUTH-01 |
| `guarantee:auth:login-fail:ip:{ip}` | **新增** | String（计数） | 失败窗口 15m | AUTH-01 |
| `guarantee:auth:login-lock:user:{username}` | **新增** | String（解锁时刻） | 锁定时长 15m | AUTH-01 |
| `guarantee:auth:login-lock:ip:{ip}` | **新增** | String（解锁时刻） | 锁定时长 15m | AUTH-01 |
| `guarantee:auth:session:{jti}` | **新增** | Hash | 与 `token:{jti}` 同步 | AUTH-05 |
| `guarantee:auth:sessions` | **新增** | ZSet（score = 空闲到期时刻） | 无（读时惰性剔除） | AUTH-05 |
| `guarantee:auth:health-probe` | **新增** | —（**只读不写**） | — | AUTH-02 健康检查 |

**命名一致性**：全部沿用既有 `guarantee:auth:` 前缀，与 `TokenRevocationService.KEY_PREFIX`、`UserTokenRevocation.KEY_PREFIX` 保持同一命名空间。

**账号 / IP 标识的归一化**：进入 key 之前一律 `trim().toLowerCase()` 并截断到 128 字符。
归一化保证 `Admin` 与 `admin` 共用同一个计数器（否则改个大小写就能重置计数），
截断则避免超长用户名撑爆 key 空间。

**健康检查的探活 key 永不写入**：`hasKey` 本身就是一次真实往返，既能探活又不污染数据，
也避开了不同 Spring Data Redis 版本对 `ping()` 返回类型的差异。

---

## 6. 配置项总览（`application.yml` 增量）

```yaml
guarantee:
  auth:
    jwt:
      # 重命名：原 expire-minutes。绝对上限，自登录起算
      absolute-expire-minutes: 720
      # 新增：空闲超时。设为 720（= 绝对上限）即退化为原有行为
      idle-timeout-minutes: 240
      # 新增：拒绝内置默认密钥启动（本地开发在 application.yml 显式置 false）
      reject-known-default: false

    # 新增：登录防爆破
    login-guard:
      enabled: true
      max-failures-per-user: 5
      max-failures-per-ip: 20
      # 时长用 Duration 语法：15m / 30s 均可（也让集成测试能注入秒级窗口而不必真等十几分钟）
      failure-window: 15m
      user-lock: 15m
      ip-lock: 15m
      # 仅在可信反向代理之后才可置 true（见 §4.1.5）
      trust-forwarded-header: false

    # 新增：令牌撤销的故障策略
    revocation:
      failure-mode: fail-closed
```

> **实现偏差（v1.1）**：三个时长配置项由 `*-minutes` 改为 **`Duration` 类型**的
> `failure-window` / `user-lock` / `ip-lock`（配合 `@DurationUnit(MINUTES)`，仍可写裸数字 `15`）。
> 原因是集成测试必须验证"锁定会到期"，而把分钟硬编码进类型就只能真等 15 分钟——
> 见 `AuthLoginGuardIT` / `AuthIpLockIT`。

> `application-prod.yml` 覆盖 `jwt.secret: ${JWT_SECRET}`（无默认值 → 未设置即启动失败）
> 与 `reject-known-default: true`（显式重申，防止 `application.yml` 被误改）。

---

## 7. 权限与角色矩阵变更

| 权限码 | 名称 | ADMIN | OPERATOR | ANALYST | VIEWER |
| --- | --- | --- | --- | --- | --- |
| `system:session:view` | 在线会话查看 | ✅ | ❌ | ❌ | ❌ |
| `system:session:kick` | 在线会话踢出 | ✅ | ❌ | ❌ | ❌ |

两者**均仅授予 ADMIN**。理由：

- 会话列表包含全员登录 IP 与 User-Agent，属于运维级信息。
- 踢出是"影响他人"的写操作，风险等级与删除相当，不应下放给 OPERATOR。

**必须同步修改的四处**：

| # | 位置 | 改动 |
| --- | --- | --- |
| 1 | `guarantee-common/.../security/Permissions.java` | 新增两个常量 |
| 2 | `guarantee-web/.../init/PermissionCatalog.java` → `PERMISSIONS` | 新增两行（路由列填 `null`，为按钮级权限） |
| 3 | 同上 → `OPERATOR_PERMISSIONS` / `ANALYST_PERMISSIONS` / `VIEWER_PERMISSIONS` | **显式确认不包含**（ADMIN 走 `ADMIN_PERMISSIONS` 全量，无需改） |
| 4 | 同上 → `isWritePermission` | **必须补 `:kick`**。否则 `writePermissions(VIEWER_PERMISSIONS)` 的断言（SYS-P-12）不会把 `system:session:kick` 识别为写权限——一旦将来误授给 VIEWER，这套护栏会静默失效 |

新增权限码会自动被 `PermissionSyncInitializer` 幂等补入存量库（`PermissionCatalog` 的既有机制），无需迁移脚本。

---

## 8. 前端设计

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `frontend/src/types/system.ts` | 新增 `SessionVO` 接口 |
| 2 | `frontend/src/types/auth.ts` | `LoginResult` 增加 `idleTimeoutSeconds?: number`（可选，向后兼容） |
| 3 | `frontend/src/api/system.ts` | 新增 `listSessions` / `kickSession` / `kickUserSessions` |
| 4 | `frontend/src/views/system/Users.vue` | 操作列新增「在线会话」按钮（`v-if` 按 `system:session:view` 渲染），打开 `el-drawer`（宽 720px）列出该用户会话；每行「踢出」（按 `system:session:kick` 渲染）+ 顶部「全部踢出」 |

**交互要点**：

- 抽屉内展示：登录时间、最后活动（`null` 显示"未知"）、登录 IP、User-Agent（截断显示，悬浮看全文）、`current` 标记。
- 被踢会话若为 `current === true`，后端返回 `selfKicked=true`，前端提示「已退出当前会话」并跳登录页（遵循 D5）。
- 沿用既有约定：**前端按钮显隐不是安全边界**（SYS-NF-04），服务端 `@PreAuthorize` 是唯一权威。
- 不新增菜单、不新增路由、不新增路由守卫逻辑（对应 D6）。

---

## 9. 明确不做的事（边界）

| # | 不做 | 理由 |
| --- | --- | --- |
| 1 | 登录日志独立落库（可检索的登录审计表） | 本期只落应用日志；独立立项（N-6） |
| 2 | 密码策略 / 定期改密 / 强制首次改密 | 承接既有 D-2a（N-4） |
| 3 | 二次认证（敏感操作再验密码） | 独立立项（N-5） |
| 4 | 密钥轮换（多密钥 / `kid`） | N-2 |
| 5 | 刷新令牌（refresh token） | N-3，且撞 §4.4.3 红线 |
| 6 | 踢出时中断进行中的 SSE 流 | N-7 |
| 7 | 图形验证码 / 滑块 | 若 AUTH-01 上线后仍观测到持续的分布式爆破，再评估；届时 IP 维度已提供第一层缓冲 |
| 8 | 运行期动态切换 `failure-mode` | 见 §4.2.6 |
| 9 | 登录页"剩余锁定时间"倒计时 | 静态文案已足够；倒计时会诱导用户盯着刷新，不改善结果 |

---

## 10. 验收标准（AC-42~AC-56）

> 编号顺延既有 AC-41（`docs/REQ-操作审计页面.md`）。

| 编号 | 对应 | 验收内容 |
| --- | --- | --- |
| **AC-42** | AUTH-01 | 15 分钟内连续 5 次错误密码后，第 6 次返回业务码 `1004` 且文案含剩余分钟数；**文案与"用户名或密码错误"可区分**；等待锁定到期后可正常登录（TEST-28） |
| **AC-43** | AUTH-01 | 同一 IP 在窗口内累计 20 次失败后，该 IP 的登录请求被拒（即使换用户名）；阈值高于账号维度，不会先于账号锁定触发（TEST-24） |
| **AC-44** | AUTH-01 | **防枚举未被破坏**：对**不存在的用户名**连续失败 5 次，同样触发锁定，且返回的文案与"存在但不存在的账号被锁"完全一致（TEST-25） |
| **AC-45** | AUTH-01 | Redis 不可用时登录**不被阻断**（fail-open），日志出现 ERROR 级"计数不可用"记录（TEST-26） |
| **AC-46** | AUTH-01 | 计数器 key 在创建时**必定带有 TTL**（不是永久 key）；`INCR` 成功但服务被杀后 key 仍会过期（TEST-27） |
| **AC-47** | AUTH-02 | `failure-mode: fail-closed` 下 Redis 故障时，携带合法令牌的请求返回 401；切换为 `fail-open` 并重启后同一请求返回 200（TEST-29） |
| **AC-48** | AUTH-02 | fail-closed 且 Redis 故障时，登录**整体失败**并返回 `1005`；**不出现**"返回登录成功但随后所有请求 401"的状态（TEST-30） |
| **AC-49** | AUTH-02 | `GET /actuator/health` 在 Redis 故障时该组件为 `DOWN`；恢复后自动回到 `UP`；响应体中**不包含** Redis 连接串（TEST-29） |
| **AC-50** | AUTH-03 | `prod` profile 且未设置 `JWT_SECRET` → 应用**启动失败**，且错误信息明确指出"环境变量未设置"（而不是误导为"长度不足"）；设为内置默认串 → 启动失败且明确指出"内置默认密钥"；设为 ≥32 字节自定义值 → 正常启动（TEST-31 + §17.6 的实机验证） |
| **AC-51** | AUTH-04 | 空闲超过 `idle-timeout-minutes` 的令牌返回 401；持续活动可将会话延长至 `absolute-expire-minutes` 上限；到达绝对上限后必然 401（TEST-32） |
| **AC-52** | AUTH-04 | **续期不会复活已撤销令牌**：用户在 T1 被撤销后，其在 T0 签发的令牌即使触发续期仍返回 401（TEST-33） |
| **AC-53** | AUTH-05 | 登录后会话出现在列表中且字段完整；踢出后该 `jti` 的下一个请求 401；登出后会话从列表消失；用户级撤销后该用户的索引被清空（无幽灵会话）（TEST-34） |
| **AC-54** | AUTH-05 | 踢出操作在 `ai_operation_audit` 中留下 `source=WEB`、`entity=SESSION`、`action=KICK` 记录，操作者为**执行踢出的管理员**；VIEWER / ANALYST / OPERATOR 调用接口返回 403（TEST-35） |
| **AC-55** | AUTH-06 | 用**已过期**的令牌调用 `/api/auth/logout` 返回业务码 `0`（不是 401）；前端手动登出时不再出现"登录状态已失效"提示与整页刷新（TEST-35） |
| **AC-56** | 回归 | 既有全部验收项（AC-01~AC-41）、`mvn verify`、前端构建全绿；`LD-T8`（已删除用户登录提示与密码错误一致）仍通过 |

---

## 11. 测试要求（TEST-24~TEST-35）

| 编号 | 类型 | 覆盖 |
| --- | --- | --- |
| **TEST-24** | 单元（Mock Redis） | `LoginAttemptGuard`：达阈值锁定、窗口过期后计数重置、成功登录清账号计数（不清 IP 计数） |
| **TEST-25** | 单元 | **不存在的用户名同样累计并锁定**（AC-44 的核心，防枚举回归） |
| **TEST-26** | 单元 | Redis 抛 `RuntimeException` → 放行 + ERROR 日志；锁定判定不抛异常穿透到接口层 |
| **TEST-27** | 单元 | 计数 Lua 脚本：首次 `INCR` 后 `TTL > 0`，不产生永久 key |
| **TEST-28** | 集成（`guarantee-web`） | 端到端 5 次错误密码 → `1004`；文案与 `LOGIN_FAILED` 可区分；等待（或注入短窗口配置）后可恢复登录 |
| **TEST-29** | 集成 | fail-closed / fail-open 两种模式下的行为切换；`RevocationHealthIndicator` 的 UP/DOWN；健康响应体不含敏感连接信息 |
| **TEST-30** | 集成 | fail-closed 下 `register` 失败 → 登录返回 `1005`（**不得**返回成功） |
| **TEST-31** | 单元 | `JwtTokenProvider` 密钥守卫：默认串被拒、短密钥被拒、合法密钥通过；指纹日志不含密钥原文 |
| **TEST-32** | 集成 | 空闲超时（以短配置注入，如 `idle-timeout-minutes=1`）、活动续期、绝对上限三态 |
| **TEST-33** | 集成 | **撤销后不可复活**：`revokeUsers` 后旧令牌触发续期路径仍 401（对应 §4.4.3 红线，本项最重要） |
| **TEST-34** | 集成 | `SessionRegistry` 全生命周期：登录登记 / 列表 / 踢出 / 登出清理 / 用户级撤销清索引 / 惰性清理过期项 |
| **TEST-35** | 集成 + 单元 | 踢出落审计（source=WEB、操作者为踢出者）；四个角色的 403/200 权限矩阵；过期令牌登出返回 `0` |

**测试基础设施注意**：

- `TEST-28` / `TEST-32` 涉及等待，**不得**用 `Thread.sleep` 等真实时长；一律通过 `@SpringBootTest(properties = {...})` 注入极短窗口（毫秒级配置项需一并提供，例如 `login-guard` 支持分钟级配置即可满足，但建议同时支持秒级便于测试）。
- Redis 故障注入：既有测试均为 `@SpringBootTest` + 真实 MySQL/Redis（见 `LogicalDeleteWebIT`）。故障注入建议用 `@MockitoBean`（Spring Boot 3.4+）替换 `StringRedisTemplate`，而非停掉真实 Redis。
- 单元测试放在对应模块的 `src/test/java`（如 `guarantee-auth/src/test/java/com/guarantee/auth/security/`），端到端集成测试放 `guarantee-web/src/test/java/com/guarantee/web/`。

---

## 12. 涉及文件清单

### 12.1 新增（11 个）

| 模块 | 文件 |
| --- | --- |
| `guarantee-common` | `security/SessionRegistry.java`（端口接口） |
| `guarantee-common` | `security/SessionInfo.java`（会话 record） |
| `guarantee-common` | `security/SessionAttributes.java`（当前 jti 的请求属性名） |
| `guarantee-auth` | `config/LoginGuardProperties.java` |
| `guarantee-auth` | `config/RevocationProperties.java` |
| `guarantee-auth` | `security/LoginAttemptGuard.java` |
| `guarantee-auth` | `security/RedisSessionRegistry.java`（`SessionRegistry` 实现） |
| `guarantee-system` | `dto/SessionDto.java` |
| `guarantee-system` | `vo/SessionVO.java` |
| `guarantee-system` | `vo/SessionKickVO.java` |
| `guarantee-system` | `service/SessionService.java` |
| `guarantee-system` | `controller/SessionController.java` |
| `guarantee-web` | `health/AuthRevocationHealthIndicator.java`（**放在启动模块**：actuator 依赖只在这里） |
| `guarantee-web` | `src/main/resources/application-prod.yml` |
| `guarantee-web`（test） | `support/ApiClient.java`（集成测试用的 HTTP 客户端） |
| `docs` | 本文件 |

### 12.2 修改

| 模块 | 文件 | 改动 |
| --- | --- | --- |
| `guarantee-common` | `api/ResultCode.java` | +`LOGIN_LOCKED(1004)`、+`AUTH_UNAVAILABLE(1005)` |
| `guarantee-common` | `security/Permissions.java` | +`SESSION_VIEW`、+`SESSION_KICK` |
| `guarantee-auth` | `config/JwtProperties.java` | `expireMinutes` → `absoluteExpireMinutes`（+ 旧名 setter 抛异常）；+`idleTimeoutMinutes`；+`rejectKnownDefault` |
| `guarantee-auth` | `config/SecurityConfig.java` | logout 加入 `permitAll`；`@EnableConfigurationProperties` 增加两个新类；过滤器装配 `SessionRegistry` |
| `guarantee-auth` | `security/JwtTokenProvider.java` | 密钥守卫、指纹日志、`getAbsoluteExpireSeconds()` / `getIdleTimeoutSeconds()` |
| `guarantee-auth` | `security/TokenRevocationService.java` | fail-closed / fail-open；`validate()` 合并"校验 + 半窗续期"；`register` 在 fail-closed 下抛 `AUTH_UNAVAILABLE` |
| `guarantee-auth` | `security/UserTokenRevocation.java` | fail-closed；`revokeUsers` 同步清理会话记录 |
| `guarantee-auth` | `security/JwtAuthenticationFilter.java` | 调用 `validate()` 并按需 `touch()`；写入当前 `jti` 请求属性 |
| `guarantee-auth` | `service/AuthService.java` | 登录守卫（先判锁定，失败计数含不存在的用户名）；会话登记；`register` 失败处理；登出恒成功 |
| `guarantee-auth` | `controller/AuthController.java` | 解析客户端 IP 传入；捕获 `ExpiredJwtException` 读 claims；恒返回成功 |
| `guarantee-auth` | `vo/LoginResponse.java` | +`idleTimeoutSeconds` |
| `guarantee-web` | `init/PermissionCatalog.java` | +2 权限；`isWritePermission` 补 `:kick` |
| `guarantee-web` | `src/main/resources/application.yml` | 新增配置块；jwt 字段改名并 `reject-known-default: false` |
| `frontend` | `src/types/system.ts` | +`SessionItem` / `SessionQuery` / `SessionKickResult` |
| `frontend` | `src/types/auth.ts` | +`idleTimeoutSeconds?` |
| `frontend` | `src/api/system.ts` | +`pageSessions` / `kickSession` / `kickUserSessions` |
| `frontend` | `src/views/system/Users.vue` | +「在线会话」入口与抽屉（含自身会话提示） |
| `guarantee-web`（test） | `LogicalDeleteWebIT.java` | `authService.login(request)` → `login(request, TEST_CLIENT_IP)`（签名变更） |

---

## 13. 分期与工作量

> **实施状态（v1.1）**：五个批次**一次性全部完成**，未分批发布。实际改动见 §12 与 §17。

| 批次 | 内容 | 预估 | 可否独立发布 | 状态 |
| --- | --- | --- | --- | --- |
| **批次 1** | AUTH-03（密钥强校验）+ AUTH-06 接口部分（logout 白名单） | 0.5 人日 | ✅ 无行为回退 | ✅ 已完成 |
| **批次 2** | AUTH-01（登录防爆破）+ TEST-24~28 | 2 人日 | ✅ 需确认 D1/D2 | ✅ 已完成（按建议值 D1/D2） |
| **批次 3** | AUTH-02（fail-closed + 健康检查 + register 失败处理）+ TEST-29/30 | 1.5 人日 | ✅ **需确认 D3** | ✅ 已完成（按建议值 D3） |
| **批次 4** | AUTH-04（空闲超时 + 续期）+ TEST-32/33 | 1.5 人日 | ✅ **需确认 D4** | ✅ 已完成（按建议值 D4） |
| **批次 5** | AUTH-05（会话管理全链路：后端 + 权限 + 前端抽屉）+ AUTH-06 索引部分 + TEST-34/35 | 3 人日 | ✅ 需确认 D5/D6 | ✅ 已完成（按建议值 D5/D6） |
| | **合计** | **约 8.5 人日** | | |

**可回退方式**（若 D3 / D4 的取舍需要改变，无需改代码）：

| 想回到 | 改哪个配置 |
| --- | --- |
| 撤销链路恢复"Redis 故障时放行" | `guarantee.auth.revocation.failure-mode: fail-open`（需重启） |
| 令牌有效期恢复"登录后固定 12 小时" | `guarantee.auth.jwt.idle-timeout-minutes: 720`（= 绝对上限） |
| 登录防爆破整体关闭 | `guarantee.auth.login-guard.enabled: false` |

**可裁剪**：若排期紧张，批次 1 + 2 + 3 已关闭全部三项**安全缺口**（约 4 人日），批次 4/5 属于加固与能力补齐，可延后。

---

## 14. 风险与应对

| 编号 | 风险 | 概率 | 影响 | 应对 |
| --- | --- | --- | --- | --- |
| **RK-16** | **AUTH-01 误伤**：阈值过低导致正常用户（连续手滑 / 共用出口 IP）被锁 | 中 | 中 | 阈值可配置；IP 维度默认不信任 `X-Forwarded-For` 避免伪造绕过；锁定文案明确给出剩余时间，降低支持成本；上线后观察 WARN 日志频次 |
| **RK-17** | **AUTH-02 fail-closed 放大 Redis 故障**：Redis 短暂抖动 → 全站 401 | 中 | **高** | 这是 D3 的核心取舍，必须业务确认；配套 `RevocationHealthIndicator` + 告警；应急 `fail-open` 开关需重启，流程写入运维手册；生产 Redis 必须高可用 |
| **RK-18** | **AUTH-02 遗漏 register 失败处理**：出现"登录成功但所有请求 401"且前端疯狂刷新登录页 | 中 | 高 | §4.2.4 已列为硬性要求 + TEST-30 专项覆盖；这是本方案最容易被漏掉的一处 |
| **RK-19** | **AUTH-04 续期绕过用户级撤销**（提权） | 低 | **极高** | §4.4.3 已定为红线（禁止重签 JWT）+ TEST-33 专项覆盖。**任何实现若提出"续期时重签令牌"，必须直接拒绝** |
| **RK-20** | **AUTH-04 行为回退引发用户投诉**："以前登录一次能撑一天，现在动不动就要重登" | 中 | 中 | `idle-timeout-minutes` 默认取 240（4h）而非更短；提供 720/720 退化开关；D4 需业务确认；上线公告说明 |
| **RK-21** | **AUTH-05 幽灵会话**：索引维护点漏一处 → 列表显示已失效会话或漏踢 | 中 | 中 | §4.5.3 列出全部五个维护点；惰性清理兜底；TEST-34 覆盖全生命周期。**（已实施）** 实现时进一步把两份索引收敛为一份，从结构上消除了索引间漂移这一类风险 |
| **RK-22** | 遗留的 `expire-minutes` 配置项被静默忽略（运维仍在 `application.yml` 里写旧名字） | 低 | 中 | 该字段改名后，若仅删字段而不做校验，旧配置会被 Spring Boot 忽略且**不报错**。**（已实施）** 保留 `@Deprecated` 的 `expireMinutes` setter，被赋值时抛异常并提示新名字；`JwtTokenProviderSecretTest` 覆盖 |
| **RK-23** | 测试用 `Thread.sleep` 等待锁定/空闲超时 → 测试时长膨胀且不稳定 | 中 | 低 | §11 已明确要求用配置注入极短窗口，禁止真实等待。**（已实施）** 落定方式：登录锁用秒级 `Duration` 配置（真等 4.5 秒，验证的正是"时间到了会解锁"）；令牌空闲超时**完全不等**——直接改 Redis 的 TTL 与 key 存在性来制造目标状态，机制等价 |
| **RK-24** | **在 `guarantee-system` 里直接注入 `guarantee-auth` / `guarantee-ai` 的 Bean，会让该模块的切片集成测试上下文集体加载失败** | 中 | **高** | **本方案实施时真实踩到**：`SessionService` 直接注入 `SessionRegistry`，导致 `guarantee-system` 的 37 个无关用例全部报 "Failed to load ApplicationContext"。应对：一律用 `ObjectProvider<T>` 延迟解析（照抄 `WebAuditor` 的手法），缺失时显式失败而非静默返回空。详见 §17.2 |

---

## 15. 待确认事项

| # | 问题 | 影响 |
| --- | --- | --- |
| **Q1** | D3：令牌撤销改为默认 fail-closed，是否接受"Redis 故障期间全站不可用"？ | 决定 AUTH-02 是否落地 |
| **Q2** | D4：接受"空闲 4 小时自动失效"这一行为回退吗？若否，是否采用 720/720 退化配置？ | 决定 AUTH-04 的默认参数 |
| **Q3** | D1/D2：5 次/15 分钟、20 次/15 分钟是否合适？生产是否存在共用出口 IP 的大规模场景？ | 决定误伤概率 |
| **Q4** | AUTH-01 的锁定是否需要**管理员手工解锁**入口（当前只有等待自动解锁）？ | 若需要，批次 2 追加约 0.5 人日 + 一个新权限码 |
| **Q5** | AUTH-05 是否本期就要「全部用户的在线会话」总览视图？（当前只做用户配置页抽屉） | 影响前端工作量（+新菜单 +1 路由 +1 权限） |
| **Q6** | `LoginResponse.expiresIn` 前端是否已有展示（如"会话剩余时间"）？ | 影响是否需要同步调整文案 |
| **Q7** | 生产部署是否已有 `prod` profile 与 `JWT_SECRET` 注入机制？ | 影响 AUTH-03 的落地方式（是否需要同步改部署脚本） |

> **实施说明（v1.1）**：D1~D6 均按 §0 的**建议值**实施。Q1、Q2 属"取舍类"确认，
> 代码已按推荐值落地，若要改变只需调整 `application.yml` 中的
> `guarantee.auth.revocation.failure-mode` 与 `guarantee.auth.jwt.idle-timeout-minutes`
> （后者设为 `720` 即完全退化为 AUTH-04 之前的行为）。Q4/Q5/Q7 仍是开放项。

---

## 16. 变更记录

| 日期 | 版本 | 变更 | 评审 |
| --- | --- | --- | --- |
| （待填） | v1.0 | 初稿。承接鉴权链路复核结论（不引入 Sa-Token），登记六项缺口：AUTH-01 登录防爆破、AUTH-02 撤销 fail-closed、AUTH-03 密钥强校验、AUTH-04 空闲超时与续期、AUTH-05 在线会话与踢出、AUTH-06 登出幂等。含 D1~D6 决策项、AC-42~AC-56、TEST-24~TEST-35、17 项风险与 7 项待确认 | 待填 |
| （待填） | v1.1 | **六项全部实施完成**。回填 §17 实施记录（实际改动文件、验证证据、4 处实现偏差）；按实现修正 §4.5.2 / §4.5.3 / §4.5.5 / §5 / §6 / §12 | 待填 |
| 2026-09-23 | v1.2 | **修复 AUTH-04 令牌撤销的秒精度竞态（P-10 验证中发现）**。`UserTokenRevocation.issuedAfterRevocation` 比较"**毫秒**精度的撤销时刻"与"JWT `iat`（RFC 7519，**秒**精度）"，导致**撤销后同一秒内签发的新令牌被判为已撤销 → 401**；实测同秒 5/5 失败、跨秒 3/3 通过。影响**所有用户级撤销流程**（停用后启用、角色分配、管理侧重置密码、P-10 的自助改密）。修复：`JwtTokenProvider` 新增毫秒精度 claim `iatMs`，`issuedAtMillis()` 优先取它、缺失回退 `iat` 秒×1000——老令牌行为与修复前完全一致（仍 fail-closed），**不放松任何安全语义**，也不触碰 §4.4.3"不得靠重签续期"的红线。详见 `docs/REQ-用户管理新增与修改.md` §14.7 | 待填 |

---

## 17. 实施记录

### 17.1 结果

六项全部落地并通过测试。验证证据（可复核）：

| 命令 | 结果 |
| --- | --- |
| `mvn -B test-compile` | BUILD SUCCESS（8 个模块） |
| `mvn -B test`（新增单测） | `JwtTokenProviderSecretTest` 10 项、`TokenRevocationServiceFailureModeTest` 12 项，全绿 |
| `mvn -B verify`（新增 IT） | `AuthLoginGuardIT` 5 项、`AuthIpLockIT` 3 项、`TokenLifecycleIT` 6 项、`OnlineSessionIT` 9 项、`RevocationFailClosedIT` 3 项，全绿 |
| `npm run build`（frontend） | `vue-tsc --noEmit` + `vite build` 通过 |

新增测试合计 **48 项**（22 单测 + 26 集成测试）。

### 17.2 实施期间发现并修正的两个问题（都值得记住）

**问题一：`RestTemplate` 对 4xx/5xx 抛异常，让"断言 401"的用例以异常的面孔失败。**

`ApiClient` 最初直接用 `rest.exchange(...)`。但本项目**大量用例要断言的恰恰是 401/403/503 本身**
（认证失败与健康检查都不属于"业务错误走 HTTP 200 + code"那一类）。默认的
`DefaultResponseErrorHandler` 会把它们变成 `HttpClientErrorException`，测试报的是
"Unauthorized 401 on GET ..." 而不是断言失败，完全看不出测的是什么。修正：捕获
`RestClientResponseException` 并把响应还原成 `Exchange`。

**问题二（更重要）：跨模块端口直接注入，会让**不相关的**切片测试上下文集体崩掉。**

`SessionService` 最初直接注入 `SessionRegistry`。该接口的实现位于 `guarantee-auth`，
而 `guarantee-system` 自己的模块级集成测试用的是只扫描本模块的切片上下文
（`ItMybatisConfig`）——那里没有这个 Bean。结果是 `LogicalDeleteSchemaIntegrationTest`
等 **37 个与本改动毫无关系的用例全部报 "Failed to load ApplicationContext"**。

这与 `WebAuditor` 对 `OperationAuditPort` 的处理是同一类问题，修正方式也照抄既有做法：
改用 `ObjectProvider<SessionRegistry>` 延迟解析。差别在于 `WebAuditor` 缺失时静默跳过是安全的
（写操作另有留痕路径），而"查看在线会话"缺失时返回空列表会让管理员**误以为真的没人在线**，
因此这里选择**显式失败**并给出可读原因。

> **教训**：在 `guarantee-system` 里引用任何 `guarantee-auth` / `guarantee-ai` 的 Bean，
> 都必须走 `ObjectProvider`。这类错误的表现是"一堆不相关的测试突然挂掉"，
> 根因藏在 `Caused by` 的最后几行——只看测试名会完全找错方向。

### 17.3 四处实现偏差（均为有意，供复核）

| # | 原设计 | 实际实现 | 原因 |
| --- | --- | --- | --- |
| 1 | 两个登录时长配置为 `*-minutes`（long） | `Duration` 类型的 `failure-window` / `user-lock` / `ip-lock` | 集成测试必须验证"锁定会到期"；分钟硬编码就只能真等 15 分钟。见 §6 |
| 2 | 会话索引为「每用户一个 SET + 会话 HASH」 | 单个以**空闲到期时刻为 score 的 ZSet** + 会话 HASH | 过期自清理、少一份索引就少一类不一致、枚举只需一次 `ZRANGE`。见 §4.5.2 |
| 3 | `SessionVO` 暴露 `lastActiveAt`（由 TTL 反推） | 暴露 `idleExpiresAt` + `absoluteExpiresAt` | 半窗节流下反推误差可达半个空闲窗口（默认 2 小时），展示出来是错的；且两个到期时间都来自已有数据，零额外读写。见 §4.5.5 |
| 4 | 登出时 `revoke` 失败返回 `AUTH_UNAVAILABLE` | 登出**恒成功**，失败只记日志；**踢出**失败才向外抛 | 与 AC-55 直接冲突。且 Redis 故障时（fail-closed）令牌本就已无法通过校验，登出报错没有任何补救价值，只会给前端多加一个分支。踢出必须相反——静默失败会让管理员以为已经踢掉。见 §4.5.6 |

### 17.4 顺带落地的两处加固

| # | 内容 |
| --- | --- |
| 1 | **RK-22 的静默配置失效已被堵住**：`JwtProperties#setExpireMinutes` 保留为 `@Deprecated` 且**赋值即抛异常**。Spring Boot 对未知属性默认不报错，若不拦截，"运维把 `expire-minutes` 改成 120、实际仍按 720 生效"会是一个没有任何反馈的故障 |
| 2 | **会话索引被显式定性为"非鉴权输入"**：`SessionRegistry` 的 Javadoc 与 `TokenLifecycleIT#missingSessionRecordDoesNotBlockAuthentication` 一起把这条边界钉住——授权依据只有令牌白名单，索引写失败不得导致任何人无法登录 |

### 17.5 未做（对应 §9 的边界）

登录日志独立落库、密码策略、二次认证、密钥轮换、刷新令牌、图形验证码、
以及**踢出时中断进行中的 SSE 流**，均未实施。其中最后一项是本方案的已知能力边界：
踢出对**下一个请求**生效（`OnlineSessionIT` 已按此断言），文档与前端提示文案都如实说明。

### 17.6 实机验证：prod profile 缺密钥（AC-50）

单测只能证明"给定某个密钥会抛异常"，证明不了"**真实启动流程**里拿到的是什么"。
因此实际用 prod profile 启动了一次打包产物：

```powershell
$env:SPRING_PROFILES_ACTIVE='prod'      # 且确认 JWT_SECRET 在 进程/User/Machine 三个作用域都为空
java -jar guarantee-web/target/guarantee-ai-admin.jar --server.port=18081
```

结果：**启动失败，退出码 1**（AC-50 成立），但首轮暴露了一个真实缺陷：

```
Caused by: java.lang.IllegalStateException:
  guarantee.auth.jwt.secret 长度不足 32 字节（当前 13），无法用于 HS256 签名
```

`13` 正是 `"${JWT_SECRET}"` 这个**占位符字面量**的长度——Spring Boot 的 Binder
并不会因为占位符无法解析而报错（见 §4.3.3 的实测纠正）。安全性质虽然成立，
但报错把人引向"去找一个 13 位的密钥"，与真实原因（环境变量没注入）完全无关。

修正后：`validateSecret` 显式识别 `${...}` 字面量并给出可执行提示；新增
`JwtTokenProviderSecretTest#rejectsUnresolvedPlaceholderWithAccurateMessage` 钉住这条行为。

> **方法论**：这一处缺陷单测永远发现不了——它不在"给定输入 → 给定输出"的逻辑里，
> 而在"框架实际塞进来的输入是什么"这个假设里。凡是**依赖框架行为假设**的校验
> （占位符解析、配置绑定、默认值注入），都值得真机跑一次。

### 17.7 提交前复查发现：AUTH-01 的三个配置键从未生效

**这是同一类陷阱的第二次踩中，而且是我自己踩的**，因此单独记录。

§6 把三个时长配置从 `*-minutes` 改成了 `Duration` 类型的
`failure-window` / `user-lock` / `ip-lock`（实现偏差 1），但**忘记同步改 `application.yml`**——
yml 里仍写着 `failure-window-minutes` / `user-lock-minutes` / `ip-lock-minutes`，
而 `LoginGuardProperties` 绑的是 `failureWindow` / `userLock` / `ipLock`。

Spring Boot 默认**静默忽略**绑定不上的键，于是：

- 三个配置项**一个都没生效**；
- Java 默认值恰好也是 15 分钟，**行为与预期完全一致**，所以没有任何测试失败；
- 集成测试全都通过测试属性（`failure-window=30s` 等）覆盖，也发现不了。

也就是说：**改这三个值不会有任何效果，而系统不会有任何提示。**

**修复**（两层）：

| 层 | 做法 |
| --- | --- |
| 立即修 | `application.yml` 改为 `failure-window: 15m` / `user-lock: 15m` / `ip-lock: 15m`，与 §6 的文档一致 |
| 根治 | 三个安全配置类（`JwtProperties` / `LoginGuardProperties` / `RevocationProperties`）统一加 `@ConfigurationProperties(ignoreUnknownFields = false)`，**键名写错即启动失败** |

**实测证据**（用修复前的错误 yml + 新守卫启动打包产物）：

```
APPLICATION FAILED TO START
Property: guarantee.auth.login-guard.failure-window-minutes
Reason: The elements [guarantee.auth.login-guard.failure-window-minutes,
        guarantee.auth.login-guard.ip-lock-minutes,
        guarantee.auth.login-guard.user-lock-minutes] were left unbound.
```

这条输出同时证明了两件事：① 三个键**确实一直在被忽略**（"left unbound"）；
② 新守卫把它变成了点名报错的启动失败。改回正确键名后同一产物正常启动。

> **教训（比这个 bug 本身更重要）**：我在 §17.2 刚写下"凡是依赖框架行为假设的校验，
> 都值得真机跑一次"，转头就在**配置绑定**这同一个假设上摔了第二次。
> 根因不是不知道这个陷阱，而是**"默认值恰好等于配置值"让所有验证手段同时失效**——
> 单测、集成测试、真机启动全都会通过。
> 因此真正的解法不是"更小心"，而是让拼写错误**在结构上不可能静默**：
> `ignoreUnknownFields = false` 把这类问题从"要靠人记得"变成"框架强制"。

