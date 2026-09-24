# 需求：用户管理「新增」与「修改」（P-10 + P-04 落地）

| 项 | 值 |
| --- | --- |
| 状态 | **规格已定稿，待补 3 项决策**（D1=C、D2=A 已拍板；D3/D7/D8 见 §0） |
| 承接 | `docs/REQ-系统管理手动操作能力补齐方案.md` **§7（P-04：用户改资料 / 启停 / 角色分配）**；`docs/REQ-系统管理助手能力.md` **D-2 / D-2a**（新建账号与密码重置"确定单独立项"） |
| 触发 | 用户诉求：**「把用户管理的新增和修改规划一下」**；随后拍板 **D1=C（固定默认密码 + 强制首次改密）**、**D2=A（角色必填）** |
| 本文档职责 | ① **P-10（新增用户）是本仓库 D-2a 预留的独立立项**，本文档给出它的完整设计；② 把 P-04 中**仍未落地**的"改资料 / 角色分配"升级为施工级规格；③ 记录三处必须先处理的既有问题（§1.3），其中一处会让"恢复账号"直接 500；④ **D1=C 的连带影响单列 §5**——它强制引入"自助改密"能力与一道服务端强制闸门 |
| 现状核对时间 | 2026-09-23（逐文件核对源码、schema、鉴权链路） |
| 后端改动 | **有，且比原方案大**：新增 1 个权限码、1 个接口、1 个 DTO、1 个 mapper 方法、**1 个 schema 列 + 1 个手工迁移脚本**、**1 个 JWT claim + 1 个过滤器 + 1 个 ResultCode**、**1 个自助改密接口**；并需移动 `PasswordEncoder` 归属（§0-D5） |

---

## 0. 决策结论（5 项已拍板，D9 经评审撤销）

### 0.1 已拍板

| # | 决策项 | 结论 | 连带影响 |
| --- | --- | --- | --- |
| **D1** | 初始密码怎么来 | ✅ **C：固定默认密码 + 强制首次改密** | **这不是一个"更简单的选项"，它把需求扩大了一个维度**：必须新增 `must_change_password` 列、一道**服务端强制闸门**、以及一个**用户自助修改密码**的能力（否则标记永远清不掉、账号变砖）。完整设计见 **§5**。同时它**推翻了 D-2 的一部分**（见 §0.2） |
| **D2** | 创建时角色是否必填 | ✅ **A：必填至少 1 个角色** | 避免了"能登录但整站空白、每个接口都 403"的账号（`AuthService.login` 本身不校验角色非空） |
| **D3** | 是否一并做「管理员重置他人密码」 | ✅ **B：一并做** | D1=C 使"忘记改后的密码"变成**无恢复路径**（旧的"忘记密码"本来就不存在，现在连"用默认密码登回去"也没了）。机制已建好，复用它 + 1 个权限码即可。设计见 **§4.7** |
| **D7** | 固定默认密码从哪来 | ✅ **B：复用 `User@123`** | 采用者已知悉它是仓库公开常量。**实现上加一道零成本保险**：单一常量来源 + 配置可覆盖 + 启动时提示"仍在使用内置默认密码"（默认值即 `User@123`，行为与决策完全一致）。见 §5.6 |
| **D8** | 新密码的强度策略 | ✅ **A：极简**（8~64 位、不同于旧密码、不等于默认密码） | 复杂度 / 定期改密 / 历史密码仍留在 **N-4** |

### 0.2 D1=C 推翻了 D-2 的一部分，且必须改写 D-2a 的立项范围

`REQ-系统管理助手能力` 的原始决策是：

- **D-2**：用户写操作只做 UPDATE / ENABLE-DISABLE / ASSIGN_ROLES，**本期不做新建用户、不做密码重置**；
- **D-2a**：新建账号与密码重置**确定单独立项**（含初始密码生成与安全分发、密码类操作的高保障渠道与责任链）。

现在的实际状态：

| D-2 / D-2a 的原始结论 | 现状 | 处置 |
| --- | --- | --- |
| 不做新建用户 | **已被推翻**：业务上现在需要了 | 本文档 P-10 交付；D-2 的该项标注为"已由 P-10 取代" |
| 新建账号单独立项 | **正在履行**：P-10 就是那个独立项 | 保留，指向本文档 |
| 不做密码重置 | **已被推翻（D3=B）** | 必须收窄为：**做自助改密 + 做管理员重置他人密码；仍不做"用户自助找回/忘记密码"流程**（无邮件/短信通道） |
| 初始密码"生成+安全分发" | **改口径**：不再是"系统生成的随机密码线下分发"，而是"固定默认密码 + 首次登录强制改密" | §5 重写 |

> **这是决策变更，不是新增范围**——所以必须同步改上游文档（§8.3），否则 `REQ-系统管理助手能力` 的 D-2 会继续宣称"不做新建用户 / 不做密码重置"，与代码直接矛盾。

### 0.3 技术性决策（已按建议采纳）+ D9 已撤销

| # | 决策项 | 结论 |
| --- | --- | --- |
| **D4** | 助手侧是否同步支持新增用户 | **不做**，技术理由见 §1.3-①（密码过不去提案脱敏链路） |
| **D5** | `PasswordEncoder` 放哪 | **移到 `guarantee-common`**；不能在 `guarantee-system` 另定义同类型 bean（会 `NoUniqueBeanDefinitionException`） |
| **D6** | 手机号/邮箱是否顺手改掩码 | **不改**，维持 Q-15 明文口径 |
| ~~**D9**~~ | ~~是否禁止重置「最后一个启用 ADMIN」的密码~~ | ✅ **已撤销**（评审质疑成立）：默认配置下该守卫与「不得重置自己」**完全重复**，且它类比的"防锁定"理由不成立（重置不造成锁定）。完整推演与纠错见 **§4.7a**。真正的残余风险是"**冒用**"而非"提权"，改由审计可见性缓解 |

---

## 1. 背景与现状核对

### 1.1 诉求拆成两件事，性质完全不同

| 诉求 | 性质 | 原因 |
| --- | --- | --- |
| **修改**用户 | **需求已定，只是没落地** | P-04 §7 已写明页面设计与校验规则，§7.5 明确"后端无需改动"；但前端一处未接 |
| **新增**用户 | **真新需求** | D-2 明确把"新建账号"移出本期，D-2a 要求**单独立项**（含初始密码生成与线下安全分发）。后端连接口、DTO、权限码、mapper 方法都不存在 |

### 1.2 能力对账（实测 2026-09-23）

| 动作 | 后端接口 | 后端校验 | 前端 API 函数 | 页面入口 | 结论 |
| --- | --- | --- | --- | --- | --- |
| UPDATE（改资料） | ✅ `PUT /api/system/users/{id}` | ✅ `validateUpdateProfile` | ❌ 不存在 | ❌ 无 | **P-04 未落地** |
| ASSIGN_ROLES | ✅ `PUT /{id}/roles` | ✅ `validateAssignRoles` | ❌ 不存在 | ❌ 无 | **P-04 未落地** |
| ENABLE / DISABLE | ✅ `PATCH /{id}/status` | ✅ `validateStatusChange` | ✅ `changeUserStatus` | ✅ 停用/启用 | 可用 |
| DELETE | ✅ `DELETE /{id}` | ✅ `deleteBlockers` | ✅ `deleteUser` | ✅ 删除 | 可用（超出 P-04） |
| 在线会话 | ✅ `GET/DELETE /api/system/sessions` | ✅ | ✅ | ✅ 在线会话 | 可用（超出 P-04） |
| **CREATE** | ❌ **不存在** | ❌ | ❌ | ❌ | **P-10：本需求新建** |
| RESET_PASSWORD | ❌ 不存在 | ❌ | ❌ | ❌ | D3：不做 |

**所以 `Users.vue` 现在有 4 个按钮（停用/启用、在线会话、删除），缺的是「新增用户」「改资料」「角色」。**

### 1.3 三条必须先处理的问题

**① 助手侧不能承载"生成初始密码"这件事（D4 的技术依据）**

密码在本项目里**无法安全地穿过 AI 提案链路**，两段证据：

| 环节 | 代码 | 后果 |
| --- | --- | --- |
| 提案载荷落库 | `SensitiveFieldMasker.maskDeep()` 对敏感键名写 `CHANGED_PLACEHOLDER`；`password` 在 `SENSITIVE_FIELDS` 白名单内 | 提案里带的初始密码，**读到时就已是 `<changed>`**，执行期拿不到真实密码 |
| 提案结果回推 | `ChatStreamEvents.ProposalResult` → 前端 + 落 `ai_message` 作为助手回答（SYS-C-08 要求流已关闭也要能看到结果） | 若改由执行期生成再回传，**明文密码会被永久写进会话历史**，且 `ai_chat` 是长留存数据 |

结论：**页面新增走"固定默认密码 + 首次登录强制改密"，助手侧维持不支持**——这是设计约束，不是偷懒。
D1=C 让这个结论**更硬**了：助手侧不仅要生成/传递密码，还要承载"强制改密"的后续状态机，而那条链路上的任何一环（提案载荷、提案结果、会话消息）都会留存文本。
同时 `UserProposalTool.UNSUPPORTED_CREATE` 现有话术正是"请在「系统管理 → 用户配置」页面或联系管理员处理"，加了页面能力后这句话**从误导变成准确**，无需改动。
`UNSUPPORTED_RESET` 也仍然准确——因为本期不做管理员重置（D3 若选 B 才需要改这句话）。

**② `PasswordEncoder` 不在 `guarantee-system` 的可编译范围内（D5）**

```
guarantee-auth  ──依赖──▶  guarantee-system  ──依赖──▶  guarantee-common
   ↑ 定义 PasswordEncoder bean          ↑ 用户服务在这里，需要散列密码
   spring-boot-starter-security             只有 spring-security-core
```

- `PasswordEncoder` bean 在 `guarantee-auth/.../config/SecurityConfig`，实现是 `BCryptPasswordEncoder`；
- `BCryptPasswordEncoder` 属 `spring-security-crypto`，由 `guarantee-auth` 的 `spring-boot-starter-security` 传递引入；
- `guarantee-system` 的 pom **只有 `spring-security-core`**（其注释明确说明"刻意不用 starter，避免引入过滤器链"），因此编译期拿不到 `BCryptPasswordEncoder`；
- 依赖方向是 `auth → system`，**system 不能反向依赖 auth**（会成环）。

`guarantee-common` 已被两端依赖，且已有 `@Component`（`TraceIdFilter`）被组件扫描命中（扫描根为 `com.guarantee`），所以把 bean 放到 `com.guarantee.common.security` 可行。

**③ 加了这个能力，「恢复已删除账号」会从"不可达"变成"会 500"（必须一并修）**

- `sys_user` 唯一键是 `uk_sys_user_username (username, IFNULL(deleted_at,'1970-01-01...'))` → **已删除账号的登录名可被复用**；
- `UserService.restore` 的阻碍项（`restoreBlockers`）**只校验所属部门**，**没有**登录名冲突校验（对比：`RoleService.restore` 有"角色编码已被同名的有效角色占用"）；
- 于是在"新建同名账号 → 恢复旧账号"路径上，`restore` 的 UPDATE 会撞唯一键，抛 **SQL 异常 → 500**，而不是可读的业务提示。

**这个缺陷现在不可达**（因为根本没有创建入口，除了初始化器），所以此前没被发现；**P-10 一上线它就可达**。修法很小：给 `restoreBlockers` 补一条，与 `RoleService.restore` 对称。

> 这正是"加能力会激活存量缺陷"的典型：新功能的价值不只是新功能本身，还包括把原本够不到的死角照亮。

---

## 2. 既有设计沿用什么 / 本文档补充什么

| 既有设计（**直接沿用，不重复论证**） | 本文档补充 |
| --- | --- |
| P-04 §7.2 的改资料 / 角色分配 / 启停三对话框布局与字段 | 逐接口**字段表与错误文案目录**（§4）、`restoreBlockers` 的对称补丁（§4.6） |
| P-04 §7.3 手机号邮箱明文口径（Q-15） | 改资料对话框的"当前值掩码回显"具体口径（§6.3） |
| P-04 §7.2 末「对自己操作」四条前端限制 | 加上**禁用 + tooltip** 的落地形态（沿用角色页已确认的约定，§6.5） |
| `UserService` 已有的各级校验与令牌撤销（P-04 §7.5） | 新增用户侧的校验清单（§4.3）与**固定默认密码 / 强制改密 / 自助改密**的完整设计（§5） |
| D-2a 要求独立项覆盖"初始密码生成与安全分发" | **改口径**：不再是"生成随机密码线下分发"，而是"固定默认密码 + 首次登录强制改密"（D1=C，§5.6） |
| P-04 §7.6 的 P04-T1~T7 | **改写 P04-T6**（见 §2.1），并新增 P04-T8~T10、P10-T1~T14（§9） |

### 2.1 必须同步改写的既有断言与决策

| 位置 | 原文 | 问题 | 改为 |
| --- | --- | --- | --- |
| `REQ-系统管理手动操作能力补齐方案.md` §7.6 **P04-T6** | "页面上**不存在**「新增用户」「重置密码」按钮（D-2 下界）" | P-10 上线后**两个按钮都必须存在**，该断言**整体失效**（测试与需求直接对立） | **整条替换**为：① 页面**存在**「新增用户」按钮，仅 `system:user:create` 可见；② 页面**存在**「重置密码」按钮，仅 `system:user:reset-password` 可见、且对自己为禁用态 |
| 同文档 §7.1 的动作表 | CREATE / RESET_PASSWORD ❌ 不做 | 两者均已被 P-10 取代 | 分别标注"→ 见 `docs/REQ-用户管理新增与修改.md`（P-10）" |
| 同文档 §7.2「工具栏」 | "**仅**「查询」「重置」（无新增按钮，符合 D-2）" | 描述已失效 | 改为含「新增用户」按钮 |
| **`REQ-系统管理助手能力.md` D-2** | "用户写操作只做 UPDATE / ENABLE-DISABLE / ASSIGN_ROLES，**本期不做新建用户、不做密码重置**" | **"不做新建用户"已失效**；"不做密码重置"须收窄 | 标注"新建用户已由 P-10 交付（推翻本项）"；密码重置改为"**做自助改密，不做管理员重置他人密码**（除非 D3=B）"。**不改这条，文档会继续宣称一个与代码矛盾的事实** |
| **`REQ-登录安全与令牌生命周期加固方案.md` N-4** | "密码策略（复杂度 / 定期改密 / **首次登录强制改密**）｜承接既有 D-2a，本期不做" | D1=C **已采纳**"首次登录强制改密" | 收窄为"仅**复杂度 / 定期改密 / 历史密码**留在 N-4；首次登录强制改密已由 P-10 交付" |
| `Permissions.java` 注释 | "用户（D-2：不含 create / reset-password）" | 半句失效 | 改为"不含 reset-password（D-2）；create 见 P-10" |
| `auditDict.ts` `AUDIT_ACTION_LABELS` | 无 `CHANGE_PASSWORD` | 新动作码会在审计页显示英文码 | 增加 `CHANGE_PASSWORD: '修改密码'` |
| `UserController` / `UserDto` 类注释 | "本类**没有** `POST /api/system/users` …… 不是遗漏" | 将变成**错误注释** | 同步更新，否则下一个人会被注释误导 |

> **注释与决策记录也是需要维护的资产**：`UserController` / `UserDto` / `Permissions` 那几处注释当初是"决策的落点"（解释为什么**没有**），决策一变就必须改；否则它们会从"解释为什么没有"变成"断言错误的事实"。
>
> **上游文档的改写时机**：`REQ-系统管理助手能力.md` 的 D-2 改写**依赖 D3 的结论**（是否一并做管理员重置，决定"不做密码重置"这句话要收窄到什么程度），因此**等 D3/D7/D8 拍板后与代码一并提交**，本次先登记（§8.3 / §13）。

---

## 3. 范围界定

| 项 | 本期 | 说明 |
| --- | --- | --- |
| 新增用户 | ✅ | P-10；密码为部署配置的**固定默认密码**，并置 `must_change_password = 1` |
| **首次登录强制改密** | ✅ | **D1=C**；含 `must_change_password` 列、JWT claim、服务端强制闸门（§5） |
| **用户自助修改密码** | ✅ | **D1=C 的强制连带项**：没有它，强制改密标记永远清不掉、账号变砖（§5.1） |
| 修改资料 | ✅ | realName / phone / email / deptId |
| 角色分配 | ✅ | `el-select multiple` |
| **管理员重置他人密码** | ✅ | **D3=B**；重置为固定默认密码 + 置 `must_change_password=1` + 撤销该用户全部令牌（§4.7） |
| 助手侧新增 | ❌ | D4；技术理由见 §1.3-① |
| 手机号邮箱掩码 | ❌ | D6；维持 Q-15 |
| 「忘记密码」自助找回 | ❌ | 无邮件/短信通道；忘记密码走 **D3 的管理侧重置** |
| 密码复杂度策略 / 定期改密 / 历史密码 | ❌ | 仍属 **N-4**；D1=C 只采纳了 N-4 中的"首次登录强制改密"这一项（D8=A） |
| 恢复入口（显示已删除） | ❌ | 5 个系统管理页统一撤除（2026-09-22），本次不单独恢复 |

---

## 4. 接口契约

统一响应 `Result<T>` = `{code,message,data,traceId}`；前端 `request.ts` 已解包 `data`。

### 4.1 修改资料（**已存在，本期不改**）

```
PUT /api/system/users/{id}        权限：system:user:update
```

| 字段 | 必填 | 约束（`UserDto.UpdateRequest`） |
| --- | --- | --- |
| `realName` | 否 | ≤64 |
| `phone` | 否 | ≤20；Service 层再判 11 位大陆手机号 |
| `email` | 否 | ≤128；Service 层再判格式 |
| `deptId` | 否 | `null` = 不改 |

**三点必须写进前端注释：**

1. **`username` 不可改**（DTO 里根本没有该字段）；
2. **`deptId = null` 表示"不改"，不是"清空部门"**——"清空部门"能力已整体移除（`sys_user.dept_id NOT NULL`）；
3. **空请求会被拒**：`UpdateRequest.isEmpty()` 为 true 时返回「至少需要提供一个待修改字段（realName / phone / email / deptId）」。前端应在无实际改动时禁用保存，不要上送空请求。

**校验与错误文案（`validateUpdateProfile`，原文）：**

| 规则 | 文案 |
| --- | --- |
| 目标存在 | 「用户不存在或不在可见范围内」（404，`DataScopeService.OUT_OF_SCOPE_MESSAGE`） |
| 非空 | 「至少需要提供一个待修改字段（realName / phone / email / deptId）」 |
| 手机号格式 | 「手机号格式不正确，应为 11 位大陆手机号」 |
| 邮箱格式 | 「邮箱格式不正确」 |
| 不得改自己的部门 | 「不允许修改自己的所属部门」 |
| 部门必须存在且未删除 | 「部门不存在或已删除: {deptId}」 |

**审计**：`source=WEB`、`action=UPDATE`、`targetType=USER`，`before`/`after` 为 `realName/phone/email/deptId` 四字段快照。`phone`/`email` 传**原值**，由 `OperationAuditService` 统一脱敏（D-4）——**服务层不得自己拼掩码**（脱敏只允许一个实现，RK-12）。

### 4.2 角色分配（**已存在，本期不改**）

```
PUT /api/system/users/{id}/roles     权限：system:user:assign-role
```
请求体 `{ "roleCodes": ["ANALYST","VIEWER"] }`，**全量替换**语义。

| 规则 | 文案 |
| --- | --- |
| 角色列表非空 | 「角色列表不能为空」 |
| 角色必须存在且启用 | 见 `validateAssignRoles` 原文文案 |
| 不得给自己增删 ADMIN | 「不允许给自己增加或移除 ADMIN 角色」 |
| 不得移除最后一个启用 ADMIN 的 ADMIN | 「该用户是最后一个启用状态的超级管理员，不允许移除其 ADMIN 角色」 |

**副作用**：撤销该用户**全部令牌**（立即强制下线，权限随即生效）。影响必须在上屏文案里说明。

### 4.3 新增用户（**P-10：需新建**）

```
POST /api/system/users           权限：system:user:create（新权限码）
```

| 字段 | 必填 | 约束 | 来源 |
| --- | --- | --- | --- |
| `username` | 是 | `@NotBlank`、≤64；建议 `/^[A-Za-z0-9_.-]{4,64}$/` | schema `VARCHAR(64) NOT NULL` |
| `realName` | 是 | `@NotBlank`、≤64 | schema `VARCHAR(64) NOT NULL` |
| `deptId` | 是 | `@NotNull`；部门必须存在且未删除 | schema **`BIGINT NOT NULL`** |
| `phone` | 否 | ≤20；11 位大陆手机号 | |
| `email` | 否 | ≤128；邮箱格式 | |
| `roleCodes` | **是**（D2=A） | 至少 1 个；角色必须存在且启用 | |
| `initialPassword` | — | **D1=C 后不存在该字段**：密码由后端写入**部署配置的固定默认密码**（§5.6），管理员不指定密码 | |

**响应**：

```
Result<UserVO>
```

> **D1=C 让响应体变简单了**：不再需要 `UserCreateResponse(user, initialPassword)` 这种专用类型，因为明文密码既不由前端提供、也不由后端返回——它只是部署配置里的一个值，服务端编码后落库。**"明文密码永不经过 HTTP"** 是这条决策的附带收益。
>
> 但仍要守住一条既有约束：**`password` / `mustChangePassword` 之外的密码类字段绝不加到 `UserVO` 上**（`UserVO` 被列表、详情、AI 的 `queryUser` 工具共用）。

**业务规则（新增，`validateCreate`）：**

| 规则 | 错误文案（建议，与角色侧对称） |
| --- | --- |
| 登录名唯一（**仅比对未删除账号**） | 「登录账号已存在：{username}」 |
| 部门存在且未删除 | 「部门不存在或已删除: {deptId}」 |
| 手机号/邮箱格式 | 复用 §4.1 的两条文案 |
| 角色非空且可用 | 复用 §4.2 的文案 |
| 初始状态 | 固定 `status = 1`（启用），**不在创建表单里给状态字段** |
| 密码 | 固定为配置默认密码，编码后落库；**同时置 `must_change_password = 1`**（§5） |

**唯一性判定必须用 `selectByUsername`**：该语句已带 `is_deleted = 0`（LD-05b），与唯一键 `(username, IFNULL(deleted_at,…))` 的语义一致——**已删除账号的登录名可被复用**，若用"含已删除"的查询会把合法创建误判为冲突。

**副作用**：写 `ai_operation_audit`（`source=WEB`、`action=CREATE`、`targetType=USER`）。**审计快照里不得出现密码**（见 §5.5 第 6 条）。

**数据范围**：`DataScopeService.resolve()` 现阶段**恒返回全量**（阶段一 O3，用户/部门已不挂机构），因此新增用户**不需要**"部门是否在数据范围内"的校验。实现上仍建议透传 `DataScope` 参数并在方法上留注释——阶段二按权限码重建分级范围时，这里要补校验。

### 4.4 需新增的权限码

| 项 | 值 |
| --- | --- |
| 常量 | `Permissions.USER_CREATE = "system:user:create"`、`Permissions.USER_RESET_PASSWORD = "system:user:reset-password"` |
| 名称 | 用户新增 / 用户重置密码 |
| 路由 | `null`（按钮级权限） |
| 授予 | **仅 ADMIN**。与"用户与角色的写权限仅 ADMIN"的既有矩阵口径一致 |
| 连带 | `PermissionCatalog.PERMISSIONS` 由 40 项变 **42 项**；`ADMIN_PERMISSIONS` 自动包含（其定义就是"全部"）；`isWritePermission()` 的 `endsWith(":create")` 覆盖 create，**`:reset-password` 需确认是否要计入写权限**——建议计入（它是敏感写操作），需在 `isWritePermission` 里加一条 `endsWith(":reset-password")` |
| 生效方式 | `PermissionSyncInitializer` 幂等补数 → **需重启后端**，存量库才会插入权限码并授予 ADMIN |

### 4.5 需新增的 mapper 方法

`SysUserMapper` 现有方法里**没有 insert**（只有 `updateProfile` / `updateStatus` / 角色 UPSERT 系列）。需新增：

| 方法 | 说明 |
| --- | --- |
| `int insert(SysUser entity)` | 参照 `SysRoleMapper.insert` 的写法：`useGeneratedKeys="true" keyProperty="id"` |
| （复用）`upsertUserRoles(userId, roleIds)` | 已存在，创建后直接复用绑定角色，**不要**新写一套 |

### 4.6 必须一并修：`restoreBlockers` 补登录名冲突校验

```java
// UserService.restoreBlockers —— 与 RoleService.restore 对称
SysUser byName = sysUserMapper.selectByUsername(user.getUsername());
if (byName != null && !byName.getId().equals(user.getId())) {
    blockers.add("登录账号已被同名账号占用，无法恢复：" + user.getUsername());
}
```

不加这条，"新建同名账号 → 恢复旧账号"会撞唯一键抛 SQL 异常 → **500**（§1.3-③）。

### 4.7 管理员重置他人密码（**P-10：需新建**，D3=B）

```
POST /api/system/users/{id}/reset-password     权限：system:user:reset-password（新权限码）
```

**无请求体**。语义是"把该账号的密码重置回初始默认密码，并要求其下次登录必须修改"——**不是**让管理员设定一个指定密码（那会让密码经手管理员并进入请求体，正是 D1=C 要避免的）。

| 步骤 | 动作 | 理由 |
| --- | --- | --- |
| 1 | 取目标用户（`requireVisible`） | 与其它写操作同口径 |
| 2 | 跑**守卫表**（下表） | 危险动作保护 |
| 3 | `password = encode(默认密码)`、`must_change_password = 1` | 与创建路径**同一段逻辑** |
| 4 | **撤销该用户全部令牌** | 两个理由：① 旧会话继续有效等于重置没生效；② 与 `changeStatus`/`assignRoles` 的既有做法一致 |
| 5 | 审计 `action=RESET_PASSWORD`、`targetType=USER`，快照不含密码 | SYS-A-05 |

**守卫表**（`validateResetPassword`，与既有危险动作保护同风格）：

| 规则 | 错误文案（建议） |
| --- | --- |
| 目标存在 | 「用户不存在或不在可见范围内」 |
| **不得重置自己的密码** | 「请使用「修改密码」修改自己的密码」——自己的密码走 §5.5 的自助改密（要验旧密码），走管理侧重置等于绕过旧密码校验 |

> **这条守卫比看上去更有用**：它顺带覆盖了"重置最后一个启用 ADMIN"的场景。因为「最后一个启用 ADMIN」意味着系统里只有一个启用的 ADMIN，而能调本接口的人必然是**启用的 ADMIN**（默认矩阵下该权限仅 ADMIN 持有）——两者只能是同一个人，于是被"不得重置自己"挡住。
>
> **D9（原"不得重置最后一个启用 ADMIN 的密码"）已撤销**，纠错记录见 §4.7a。

### 4.7a 纠错记录：D9 为什么被撤销（2026-09-23）

初稿曾建议加一条守卫「不得重置最后一个启用 ADMIN 的密码」，理由是"否则可借公开默认密码接管唯一超管账号"。**该论证是错的**，逐组合推演如下：

| 操作者 | 目标 | 能否得逞 | 判定 |
| --- | --- | --- | --- |
| 启用的 ADMIN | **最后一个启用 ADMIN** | **否** | 系统里只有一个启用的 ADMIN，操作者也是启用的 ADMIN → **操作者就是目标本人** → 已被"不得重置自己"挡住 |
| 启用的 ADMIN | 另一个 ADMIN（非最后） | 是 | 但操作者本就拥有全部权限，**拿不到任何新能力**，不构成提权 |
| 启用的 ADMIN | 普通用户 | 是 | 同理，不构成提权 |
| **非 ADMIN** 但被授予该权限 | 任意 ADMIN | 是 | **唯一的真提权场景** |

结论与处置：

1. **默认配置下 D9 是冗余的**——「不得重置自己」已完整覆盖，加了就是一段永不触发的死代码；
2. **D9 的类比也是错的**：本仓库的"不得停用 / 删除 / 移除最后一个启用 ADMIN 的角色"防的是**锁定**（一个管理员都不剩就没人能管系统），而重置**不会**造成锁定、反而让账号变可用。初稿一边写"它不是防锁定"，一边把它挂在同一面旗子下，自相矛盾；
3. **即便在"非 ADMIN 持有该权限"的场景下，「最后一个」这个限定词仍然不对**：该操作者重置**任何一个启用的 ADMIN** 都能达到同样效果，没有理由只防最后一个；
4. **加它还会挡住合法场景**：管理员 A 帮忘记密码的管理员 B 重置密码（B 是最后一个 ADMIN 时反而被拒），这是纯粹的功能损失。

**真正的残余风险不在"提权"，而在"冒用"**：因为默认密码是公开的 `User@123`，「重置密码」在功能上等价于「**以该用户身份登录**」。这与目标是不是 ADMIN、是不是最后一个无关，是 D3=B + D7=B 组合的固有性质。对应的正确缓解不是加守卫，而是**让冒用可被发现**（已在设计中）：

| 缓解 | 位置 |
| --- | --- |
| 重置落审计，操作者记**真实操作者** | 本接口步骤 5 |
| 被重置者必被强制改密，改密也落审计 | §5.5 |
| 真正的用户会发现密码失效并来查，审计能指出是谁重置的 | 上述两条的组合效果 |
| `User@123` 可经配置覆盖（§5.6），换掉它这一风险直接消失 | D7=B 的机制 |

> **可选的更严方案（不建议）**：把守卫改为「不得重置任何持有 ADMIN 角色的用户」。它能堵住上表最后一行，但代价是**管理员之间无法互相救援**——而操作者本身就是管理员，收益很小。除非将来把 `system:user:reset-password` 下放给非 ADMIN，否则不必要。

**与创建路径共用逻辑**：`UserService.create` 与 `resetPassword` 都调用同一个私有方法写"默认密码 + 置标记"，避免两处各写一遍导致口径漂移。

---

## 5. 固定默认密码 + 强制首次改密（D1=C 的核心设计）

### 5.1 这条决策的连带范围（先看这张图）

D1=C 不是一个孤立的字段，它是一条**贯穿鉴权链路**的能力：

```
创建用户 ──写──▶ must_change_password = 1
                        │
   登录 ──读──▶ LoginResponse.mustChangePassword = true
                        │  同时写进 JWT claim（mcp）
                        ▼
   任何请求 ──▶ 【服务端强制闸门】mcp=1 时，除白名单外一律 403
                        │
                        ▼
   用户改密 ──▶ 校验旧密码 ──▶ 写新散列 + must_change_password=0
                        │
                        └──▶ 撤销该用户全部令牌 ──▶ 重新登录（新令牌 mcp=0）
```

**四个新增件缺一不可**：① `must_change_password` 列；② JWT claim；③ 服务端强制闸门；④ **自助改密接口与页面**。

**没有 ④，①②③ 就是死锁**——用户被永久关在"必须改密"的闸门后面，却没有任何改密的入口，账号直接变砖。这就是 §0-D3 我改推荐"一并做管理员重置"的原因。

### 5.2 数据库改动（⚠️ 需**手工执行**迁移脚本）

| 项 | 内容 |
| --- | --- |
| `schema.sql` | `sys_user` 增加列：`must_change_password TINYINT NOT NULL DEFAULT 0 COMMENT '首次登录强制改密 1是 0否'`（供**全新库**） |
| 新增迁移脚本 | `guarantee-web/src/main/resources/db/migration/V6__user_must_change_password.sql`（供**存量库**） |

> **本仓库没有 Flyway。** `db/migration/V1~V5` 是**手工执行的幂等脚本**（`V4` 一份文档里明确写着"本仓库无 Flyway"），所以 V6 必须：
> ① 先查 `information_schema.COLUMNS` 判断列是否已存在，再 `ALTER`（幂等，可重复执行）；
> ② 由**人**在部署时执行。
>
> **忘记执行 V6 的后果**：所有读到该列的 SQL 直接报 `Unknown column 'must_change_password'` —— 登录、用户列表、创建全部不可用。**这是上线步骤的硬前提，必须写进发布清单。**

**存量数据的取值**：默认 0（不强制）。即**既有账号不受影响**，只有当次及以后新建的账号会被置 1。若希望对既有演示账号也走一次强制改密，需另行决策（不建议：会让所有既有用户被踢一次）。

### 5.3 后端改动清单

| # | 位置 | 变更 |
| --- | --- | --- |
| 1 | `sys_user` / `SysUser` 实体 | 加 `mustChangePassword`；`selectEntityById`、`selectByUsername`、`insert` 的列清单同步 |
| 2 | `JwtTokenProvider` | 新增 claim `mcp`；`createToken` 读 `user.getMustChangePassword()`；新增静态访问器 `mustChangePassword(claims)` |
| 3 | `ResultCode` | 新增 `PASSWORD_CHANGE_REQUIRED(1006, "首次登录需先修改初始密码")` |
| 4 | `JwtAuthenticationFilter` | 解析 `mcp`；为 true 时给 Authentication **追加一个 authority** `PWD_CHANGE_REQUIRED` |
| 5 | **新增** `PasswordChangeRequiredFilter` | 见 §5.4 |
| 6 | `SecurityConfig` | `addFilterAfter(new PasswordChangeRequiredFilter(...), JwtAuthenticationFilter.class)` |
| 7 | `LoginResponse` | 加 `boolean mustChangePassword` |
| 8 | `CurrentUserVO` | 加 `boolean mustChangePassword`——**不加则刷新页面后前端丢失该状态**，守卫无法把用户引回改密页 |
| 9 | **新增** `PUT /api/auth/password` | 自助改密，见 §5.5 |
| 10 | `UserService` | 新增 `updatePassword(userId, rawNewPassword, clearMustChangeFlag, operatorUserId)` |
| 11 | `auditDict.ts` | **新增动作码映射** `CHANGE_PASSWORD: '修改密码'`——现有 `AUDIT_ACTION_LABELS` 里没有它，不加会在审计页显示英文码 |

> **为什么不把 `mustChangePassword` 加到 `CurrentUser.Principal`**：那是个 record，被 `AiController` 构造 `ProposalExecutionContext`、以及 AI 的 `ToolContext` 装配读取。为了一个只在 HTTP 鉴权层用的标志去改它，会把改动扩散到 AI 模块。**用 authority 承载更收敛**：鉴权层自己消费，业务层无感。

### 5.4 服务端强制闸门（`PasswordChangeRequiredFilter`）

**这是 D1=C 唯一真正的安全边界**。前端守卫只是体验——只在页面上拦，用户直接调接口照样能绕过。

| 项 | 规则 |
| --- | --- |
| 触发条件 | Authentication 中存在 authority `PWD_CHANGE_REQUIRED` |
| 处置 | 直接写响应：HTTP **403** + `{code:1006, message:"首次登录需先修改初始密码"}`，**不继续过滤器链** |
| 白名单（必须放行） | `PUT /api/auth/password`（改密本身）、`POST /api/auth/logout`（不能把用户困住）、`GET /api/auth/me`（前端需要读状态）、`OPTIONS /**` |
| 为何用 claim 而非查库 | 与本仓库既有取向一致——`JwtTokenProvider` 的注释写明"权限编码直接写入令牌，**避免每个请求都回查数据库**"。查库方案会给每个请求加一次查询 |
| 为何改密后要撤令牌 | 当前令牌里 `mcp` 恒为 `true`，不撤销的话用户改完密码仍被闸门拦住。撤销 + 重新登录后，新令牌 `mcp=false` |

> **白名单漏一个就出事**：例如漏了 `/api/auth/logout`，用户会被永久锁在改密页、连退出都做不到（只能清浏览器存储）。这与 `SecurityConfig` 里对 `/api/auth/logout` 放行的既有理由（"登出的语义承诺是即使令牌已过期也返回成功"）是同一类问题。

### 5.5 自助改密接口

```
PUT /api/auth/password        认证：已登录（任意角色）；无需权限码
```

| 字段 | 必填 | 校验 |
| --- | --- | --- |
| `oldPassword` | 是 | 必须与当前散列 `passwordEncoder.matches` 通过，否则「原密码不正确」 |
| `newPassword` | 是 | D8=A：长度 8~64；**不得与原密码相同**；不得等于当前默认密码 |

**执行流程**（顺序不可颠倒）：

1. 取当前用户（`CurrentUser.userId()`，**不接受任何"改谁的密码"参数**——一旦接受，越权就只是传错一个参数）；
2. `selectEntityById` 取当前散列（已确认该语句读取 `password` 列）→ 校验 `oldPassword` ；
3. 校验 `newPassword` 策略；
4. BCrypt 编码新密码 → 写 `password` 与新值，同时 `must_change_password = 0`；
5. **撤销该用户全部令牌**（`UserTokenRevoker.revokeUsers`）；
6. 审计：`source=WEB`、`action=CHANGE_PASSWORD`、`targetType=USER`、`targetId=自己`。**快照里绝不出现密码**（`SensitiveFieldMasker` 会把 `password` 写成 `<changed>`，但这是兜底，不应作为依赖）；
7. 返回成功 → 前端提示「密码已修改，请重新登录」→ 清 token → 跳登录页。

> **必须有第 5 步**。不撤销令牌的话：① 旧令牌 `mcp=true` 仍然过不了闸门（用户以为没改成功）；② 若该账号的密码是被他人改动，原持有者仍能用旧会话，等于密码变更不生效。这与 `changeStatus`/`assignRoles` 的既有做法一致。

### 5.6 默认密码的来源与风险（D7=B）

| 项 | 规则 |
| --- | --- |
| 取值 | **`User@123`**（D7=B，与 `DataInitializer` 的演示默认密码一致） |
| 单一来源 | 常量收到 **`guarantee-common`**（如 `com.guarantee.common.security.DefaultCredentials.DEFAULT_PASSWORD`），`DataInitializer` 也改为引用它。**理由不只是整洁**：`DataInitializer` 在 `guarantee-web`，`UserService` 在 `guarantee-system`，而依赖方向是 `web → system`——**`guarantee-system` 无法引用 `DataInitializer` 的常量**（会成反向依赖）。放 common 是唯一两个模块都能用的位置 |
| 配置可覆盖 | 用 `@Value("${app.security.default-password:User@123}")` 或 `@ConfigurationProperties`。**默认值就是 `User@123`**，所以你不需要任何配置改动；但生产若要换值，不必改代码重新编译 |
| 启动提示 | 启动时若仍在用内置默认值，打一条 **WARN**：「仍在使用内置默认密码，建议通过 `app.security.default-password` 覆盖」。这与 `JwtTokenProvider` 打印密钥指纹 / 拒绝内置默认密钥（AUTH-03）是同一思路：**把"还在用仓库里的值"这件事变得可见**，而不是禁止它 |
| 展示 | 创建/重置成功后提示「初始密码为系统默认密码，该用户下次登录必须修改」；**不在界面上回显密码值**（它是仓库公开值，回显没有信息量） |

**残余风险（明确接受）**：`User@123` 写在源码里且被 README 与多份文档反复引用，等于**公开口令**。因此存在"抢注窗口"——账号创建/重置完成到本人首次登录改密之间，知道该值的人可抢先登录并改密，把真正的用户锁在外面。

| # | 缓解 | 本期 |
| --- | --- | --- |
| 1 | 强制闸门：改密前**什么都不能做**（服务端强制） | ✅ |
| 2 | 改密后撤销全部令牌；创建/重置/改密全部审计留痕（使冒用**可追溯到人**） | ✅ |
| 3 | 不得重置自己的密码——顺带覆盖"最后一个启用 ADMIN"场景（§4.7a） | ✅ |
| 4 | 创建/重置后由管理员**线下立即转达**并督促首次登录 | 流程约定 + 页面提示 |
| 5 | 缩短窗口：账号 N 小时未首次登录则自动停用 | ❌ 需定时任务，另立需求 |
| 6 | 配置覆盖默认密码 | ✅ 机制在，值由你定（默认 `User@123`） |

> **一句话**：这条风险是"固定默认密码"方案的固有代价，`User@123` 的选择把它从"需要费力推测"降到"读 README 就知道"。缓解手段把影响面限制在**首次登录之前**，且全程留痕可追溯——但窗口期客观存在，需被知悉而非消除。

### 5.7 初始密码不落会话历史（与 D4 的呼应）

一次页面创建**不产生任何 `ai_*` 记录**，自改密同样只走 `ai_operation_audit`（且密码字段被脱敏）。这正是 D4 选择"助手侧不做"的好处：**只要助手不参与密码类操作，明文密码就永远不会进入 `ai_tool_call` / `ai_operation_proposal` / `ai_message` 这三条会长期留存的链路。**

---

## 6. 页面设计（施工级）

### 6.1 工具栏与操作列

**工具栏**：新增「新增用户」按钮，`v-if="canCreate"`（`system:user:create`）。

**操作列**：宽度由 200 调整为 **340**（6 个按钮），按钮顺序与权限：

| 按钮 | 权限码 | 可见条件 |
| --- | --- | --- |
| 改资料 | `system:user:update` | `canUpdate` |
| 角色 | `system:user:assign-role` | `canAssign` |
| **重置密码** | `system:user:reset-password` | `canResetPassword` |
| 停用/启用 | `system:user:disable` | `canDisable` |
| 在线会话 | `system:session:view` | `canViewSessions` |
| 删除 | `system:user:delete` | `canDelete` |

操作列的 `v-if` 必须加入 `canUpdate || canAssign || canResetPassword`——现状是 `canDisable || canDelete || canViewSessions`，**只持有改资料/角色/重置权限的账号会整列不渲染**（角色页已踩过同一个坑）。

> **按钮多到这个量**：6 个 `link` 按钮挤在一列里会换行。实现时把「重置密码」放在「角色」之后、把「在线会话」与「删除」放在末尾，并按实际渲染宽度调整 `width`；若仍拥挤，考虑把低频的「在线会话」收进一个 `el-dropdown`（本期可不做，先按 340 试）。

### 6.2 新增用户对话框

对齐 `InsuranceTypes.vue` 的既有模式（`form` reactive + `dialogVisible` + `submitting` + `formRef` + `rules` computed + `@closed="resetForm"`）。

| 字段 | 控件 | 规则 |
| --- | --- | --- |
| `username` | `el-input` | 必填；4-64 位字母数字 `_ . -`；提示"如 zhangsan" |
| `realName` | `el-input` | 必填；≤64 |
| `deptId` | `el-select` | **必填**；选项来自部门接口（仅启用、未删除） |
| `phone` | `el-input` | 选填；11 位手机号 |
| `email` | `el-input` | 选填；邮箱格式 |
| `roleCodes` | `el-select multiple` | **必填至少 1 个**（D2=A）；选项来自 `pageRoles`（仅启用角色） |

> **不提供「状态」字段**：新账号固定启用。让创建时就能建停用账号没有实际价值，反而多一条"建好却是停用"的困惑路径（与角色页修改对话框不给状态字段同理）。
>
> **也不提供任何密码字段**（D1=C）：密码由后端写入部署配置的固定默认密码，管理员既不需要、也不应该指定它。

提交 → 成功后关闭弹窗、刷新列表，并给出**成功提示（不是密码弹窗）**：

> 「用户「张三」已创建。初始密码为系统默认密码，**该用户首次登录时必须修改后才能使用**，请线下转达并督促其尽快登录。」

**不再需要 D1=B 时代的「初始密码」一次性展示弹窗**——默认密码不是每次生成的随机值；且 D7=B 下它就是仓库里的 `User@123`（README 都有），在界面上回显它没有任何信息量（§5.6）。

### 6.2a 新增页面：修改密码（`views/ChangePassword.vue`）

D1=C 强制要求，独立于用户管理页。

| 项 | 规格 |
| --- | --- |
| 路由 | `/change-password`，**不在 `AppLayout` 之下**（与 `Login.vue` 同级）——用户此时什么都做不了，不该看到带侧边栏的空壳 |
| `meta` | `{ title: '修改密码' }`，**不设 `public`**（需要登录：改密接口靠 token 认人） |
| 表单 | 原密码 / 新密码 / 确认新密码；三项均 `type="password" show-password` |
| 前端校验 | 三项必填；新密码 8~64（D8=A）；两次输入一致；新密码不得与原密码相同 |
| 强制模式 | 由 `userStore.mustChangePassword` 决定：为 true 时页面顶部显示醒目说明「首次登录需先修改初始密码」，并**隐藏「取消/返回」**——此时没有别的可去之处 |
| 主动模式 | 为 false 时作为普通功能，提供「返回」；同时在 `AppLayout` 右上角用户菜单加「修改密码」入口 |
| 成功后 | 提示「密码已修改，请使用新密码重新登录」→ 清 token → `router.replace('/login')`（因后端已撤销全部令牌，停留无意义） |

> **主动模式（非强制）也一并交付**：既然接口与页面都要做，把它同时开放给所有登录用户是零成本的，而且补上了系统里"用户无法自己改密码"这个存在已久的缺口。

### 6.2b 三道拦截：让强制改密真正生效

前端守卫只是体验，但三道都要有，否则体验很差：

| # | 位置 | 行为 |
| --- | --- | --- |
| 1 | `Login.vue` 登录成功后 | 若 `mustChangePassword` → `router.replace('/change-password')`，**不要**去 dashboard |
| 2 | `router/index.ts` 守卫 | 已有 token 且 `userStore.mustChangePassword` 且目标不是 `/change-password` → 重定向到 `/change-password` |
| 3 | `api/request.ts` 响应拦截器 | 遇 HTTP 403 且 `code === 1006` → **不清 token**（改密还要用它）、跳 `/change-password`，且**不弹**"没有权限"提示 |

> 第 3 条容易做错：现有拦截器对 403 统一提示「没有权限访问该资源」并可能触发 `redirectToLogin()`。若把 1006 混进 403 走同一条路，用户会被踢回登录页——**登录后又被踢回来，形成死循环**。必须按 `code` 分流。

### 6.3 改资料对话框

| 字段 | 控件 | 规则 |
| --- | --- | --- |
| `username` | 只读文本 | 不可改 |
| `realName` | `el-input` | 可改；≤64 |
| `phone` | `el-input` | 11 位手机号 |
| `email` | `el-input` | 邮箱格式 |
| `deptId` | `el-select` | **目标是自己时置灰 + tooltip**「不允许修改自己的所属部门」 |

**"当前值"回显口径（沿用 P-04 §7.3）**：手机号/邮箱的当前值以**掩码**展示（`138****5678`），形式为"当前：`138****5678` → 新值：`[输入框]`"。理由：这里展示的是**变更对比**，与列表的"浏览"语义不同，掩码更贴合 D-4"审计只记是否变更"的精神。

**提交前的空改动判断**：若四个字段与当前值完全一致，禁用保存并提示「未做任何修改」——避免撞 §4.1 的"至少需要提供一个待修改字段"。

### 6.4 角色分配对话框

| 元素 | 说明 |
| --- | --- |
| 当前角色 | 只读标签 |
| 变更后角色 | `el-select multiple`，选项来自 `pageRoles`（仅启用角色） |
| 变更对比 | 提交前展示「当前 → 变更后」新增/移除两组标签 |
| 影响提示 | 该用户全部令牌被撤销，**立即强制下线**，需重新登录后新权限才生效 |
| 目标是自己时 | 角色选项里 **`ADMIN` 置灰**（后端禁止给自己增删 ADMIN） |
| 移除最后一个 ADMIN 的角色 | 允许提交，由后端拒绝并展示原因（前端无法可靠判断"是否最后一个"） |

### 6.4a 重置密码确认框（D3=B）

**用 `ElMessageBox.confirm`（不是对话框表单）**——本操作**无任何入参**，不存在"填什么"的问题，只是一个危险动作确认。这与「停用」「删除」的既有形态一致。

确认文案必须写清三件事：

> 确认将「张三」的密码重置为**系统默认密码**？
> ① 重置后该用户**下次登录必须修改密码**才能使用系统；
> ② 该用户当前所有登录会话会被**立即强制下线**（含 AI 助手的长连接）；
> ③ 该用户是最后一个启用状态的超级管理员时会被系统拒绝。

| 按钮态 | 条件 |
| --- | --- |
| 目标是自己 | **禁用 + tooltip**「请使用「修改密码」修改自己的密码」（§6.5）。**这条同时覆盖"目标是最后一个启用 ADMIN"**——那种情况下目标必然是操作者本人（§4.7a） |
| 无 `system:user:reset-password` | 不渲染按钮 |
| 目标是其他 ADMIN | 允许（管理员之间互相救援；操作者本就是 ADMIN，不构成提权） |

### 6.5 「禁用 + tooltip」约定（沿用角色页已确认的约定）

凡"因业务规则不可用"的按钮，一律 `disabled` + `el-tooltip` + **`<span>` 包裹**（`el-tooltip` 对 disabled 的 `el-button` 不生效）。本页适用场景：

| 场景 | tooltip 文案 | 层 |
| --- | --- | --- |
| 修改自己的部门 | 不允许修改自己的所属部门 | 前端置灰（后端亦拒） |
| 停用自己 | 不允许停用自己的账号 | 前端置灰（后端亦拒） |
| 删除自己 | 不允许删除自己的账号 | 前端置灰（后端亦拒） |
| **重置自己的密码** | 请使用「修改密码」修改自己的密码 | **前端置灰（后端亦拒）**——自己的密码走自助改密，要验旧密码 |
| 给自己增删 ADMIN | 不允许给自己增加或移除 ADMIN 角色 | 选项置灰（后端亦拒） |

**现状差距**：`Users.vue` 现在的「停用」「删除」**没有做任何禁用处理**，点到自己才由后端拒绝——这正是 §6.5 要消灭的形态。本次一并补齐（含上表新增的「重置自己的密码」）。

> **连带注意**：`<span>` 包裹会破坏 Element Plus 的 `.el-button + .el-button { margin-left: 12px }`，操作列按钮间距必须改用 flex + `gap`（角色页已踩过此坑，见其文档 §13.5）。

### 6.6 保持不动的部分

- 手机号/邮箱**列展示维持明文**（D6=Q-15）；
- 保留既有「在线会话」「删除」按钮与其权限过滤；
- 不做「忘记密码」自助找回（无邮件/短信通道）；
- 不恢复「显示已删除」开关与恢复入口。

---

## 7. 边界与异常

| # | 场景 | 期望行为 |
| --- | --- | --- |
| E-1 | 只持有 `system:user:view`（ANALYST / VIEWER） | 可看列表；**无**新增按钮；操作列按各 `can*` 渲染按钮 |
| E-2 | 登录名重复（未删除账号） | 「登录账号已存在：{username}」，**弹窗不关** |
| E-3 | 登录名与**已删除**账号相同 | **允许创建**（唯一键含 `deleted_at`）。但需在文档/提示中知悉：这会阻止旧账号被恢复（§1.3-③ 的补丁会给出可读文案） |
| E-4 | 部门被撤销/删除后提交 | 「部门不存在或已删除: {deptId}」 |
| E-5 | 未选角色 | 前端拦下（D2=A 必填）；绕过前端则由后端拒绝 |
| E-6 | 新账号首次登录 | 登录成功，但除**改密/登出/读自己**外的一切请求被闸门拒为 `403 / code=1006`（§5.4） |
| E-7 | 强制改密用户直接用 curl 调业务接口 | 被服务端闸门拒绝（**这是真正的边界**，前端守卫不是） |
| E-8 | 强制改密用户在改密页点「登出」 | **必须可用**——`/api/auth/logout` 在白名单内，否则用户被困死（§5.4） |
| E-9 | 改密时旧密码错误 | 「原密码不正确」，不计入登录失败锁定（走的是已认证会话，不是登录尝试） |
| E-10 | 改密成功后停留在原页面 | 后端已撤销全部令牌，下一请求 401 → 前端应主动清 token 并跳登录页（§6.2a 已规定主动跳转） |
| E-11 | 用户忘记自己改后的密码 | 走 **D3=B 的管理侧重置**（§4.7）：重置为默认密码并要求下次登录修改。**这是本需求交付后唯一的恢复路径** |
| E-12 | 存量账号（迁移后） | `must_change_password` 默认 0，**不触发**强制改密，行为与升级前一致 |
| E-13 | 创建后立即被停用 | 正常流程；停用会撤销令牌（新账号本来也没有） |
| E-14 | 改资料传空请求 | 前端应拦（§6.3）；后端返回「至少需要提供一个待修改字段…」 |
| E-15 | 角色分配后用户在线 | 其令牌被撤销，下次请求 401 → 前端拦截器跳登录页 |
| E-16 | 管理员重置他人密码后 | 该用户全部令牌被撤销、`must_change_password=1`；其下次登录被闸门拦住并要求改密 |
| E-17 | 管理员重置**自己**的密码 | 被拒：「请使用「修改密码」修改自己的密码」（前端按钮亦置灰） |
| E-18 | 重置**最后一个启用 ADMIN** 的密码 | **由「不得重置自己」兜住**：最后一个启用 ADMIN 就是操作者本人（该权限默认仅 ADMIN 持有）→ 返回「请使用「修改密码」修改自己的密码」。**不需要独立的"最后一个 ADMIN"守卫**（§4.7a） |
| E-19 | 重置一个**已停用**用户的密码 | 允许。重置不改变启用状态；该用户仍无法登录（`ACCOUNT_DISABLED`），启用后才走强制改密 |

---

## 8. 涉及文件清单

### 8.1 后端

| 文件 | 变更 |
| --- | --- |
| `guarantee-web/.../db/schema.sql` | `sys_user` 加 `must_change_password TINYINT NOT NULL DEFAULT 0`（全新库） |
| **新增** `guarantee-web/.../db/migration/V6__user_must_change_password.sql` | **手工执行的幂等迁移**（存量库）；本仓库**无 Flyway**（§5.2） |
| **新增** `guarantee-common/.../security/DefaultCredentials.java` | 默认密码**单一常量来源**（D7=B：`User@123`）+ 配置可覆盖 + 启动 WARN（§5.6） |
| `guarantee-web/.../init/DataInitializer.java` | `DEFAULT_PASSWORD` 改为引用 `DefaultCredentials`（消除同值两处定义；**不是**反向依赖） |
| `guarantee-common/pom.xml` | 新增 `spring-security-crypto`（D5） |
| **新增** `guarantee-common/.../security/PasswordEncoderConfig.java` | `@Configuration` + `@Bean PasswordEncoder`（BCrypt） |
| `guarantee-auth/.../config/SecurityConfig.java` | **移除**自己的 `PasswordEncoder` bean，改为复用 common 的（D5，必须移除而非并存）；并注册 `PasswordChangeRequiredFilter` |
| `guarantee-common/.../api/ResultCode.java` | 新增 `PASSWORD_CHANGE_REQUIRED(1006, …)` |
| `guarantee-common/.../security/Permissions.java` | 新增 `USER_CREATE` / `USER_RESET_PASSWORD`；修正"不含 create"的注释 |
| `guarantee-web/.../init/PermissionCatalog.java` | `PERMISSIONS` 加 `{"system:user:create","用户新增",null}`、`{"system:user:reset-password","用户重置密码",null}`（40 → **42 项**）；`isWritePermission` 补 `:reset-password` |
| `guarantee-auth/.../security/JwtTokenProvider.java` | 新增 claim `mcp` + 静态访问器 |
| `guarantee-auth/.../security/JwtAuthenticationFilter.java` | `mcp=true` 时追加 authority `PWD_CHANGE_REQUIRED` |
| **新增** `guarantee-auth/.../security/PasswordChangeRequiredFilter.java` | 服务端强制闸门（§5.4） |
| `guarantee-auth/.../vo/LoginResponse.java` | 加 `boolean mustChangePassword` |
| `guarantee-auth/.../vo/CurrentUserVO.java` | 加 `boolean mustChangePassword`（否则刷新页面丢失状态） |
| `guarantee-auth/.../controller/AuthController.java` | 新增 `PUT /api/auth/password` |
| `guarantee-auth/.../service/AuthService.java` | 新增 `changePassword(userId, old, new)`；`buildCurrentUser` 带上新字段 |
| `guarantee-system/.../controller/UserController.java` | 新增 `POST /`、`POST /{id}/reset-password`；修正类注释（D-2 落点表述） |
| `guarantee-system/.../dto/UserDto.java` | 新增 `CreateRequest`；修正类注释 |
| `guarantee-system/.../service/UserService.java` | 新增 `create` / `validateCreate` / `updatePassword` / `resetPassword` / `validateResetPassword`；**创建与重置共用"写默认密码+置标记"的私有方法**；**`restoreBlockers` 补登录名冲突校验**（§4.6） |
| `guarantee-system/.../mapper/SysUserMapper.java` + `mapper/system/SysUserMapper.xml` | 新增 `insert`、`updatePassword`；`selectEntityById`/`selectByUsername` 列清单补新字段 |

> **已取消**：`UserCreateResponse` 与 `InitialPasswordGenerator`（D1=B 时代的产物，D1=C 后不需要）。

### 8.2 前端

| 文件 | 变更 |
| --- | --- |
| `frontend/src/api/system.ts` | 新增 `createUser` / `updateUser` / `assignUserRoles` / `resetUserPassword`（`pageRoles`、`changeUserStatus` 已存在） |
| `frontend/src/api/auth.ts`（或既有 auth 模块） | 新增 `changePassword` |
| `frontend/src/types/system.ts` | 新增 `UserCreateParams` / `UserUpdateParams` / `UserAssignRolesParams`；`UserItem` 补 `roleCodes`（角色分配回显需要，现在只有 `roleIds`/`roleNames`） |
| `frontend/src/types/ai.ts` 或 auth 类型 | `LoginResult` / 当前用户类型加 `mustChangePassword` |
| `frontend/src/stores/user.ts` | 持有 `mustChangePassword`（`fetchMe` 时刷新） |
| **新增** `frontend/src/views/ChangePassword.vue` | 修改密码页（§6.2a） |
| `frontend/src/router/index.ts` | 新增 `/change-password` 路由 + 守卫重定向（§6.2b） |
| `frontend/src/views/Login.vue` | 登录后按 `mustChangePassword` 分流 |
| `frontend/src/api/request.ts` | 403 + `code===1006` 分流，**不清 token、不弹权限提示**（§6.2b 第 3 条） |
| `frontend/src/layout/AppLayout.vue` | 用户菜单加「修改密码」入口（主动模式） |
| `frontend/src/views/system/Users.vue` | 工具栏 + 操作列改造 + 新增用户/改资料/角色/重置密码 + 禁用 tooltip |
| `frontend/src/utils/status.ts` | 无需改（`STATUS_*`、`statusParam` 已够用） |

### 8.3 文档与测试

| 文件 | 变更 |
| --- | --- |
| `REQ-系统管理手动操作能力补齐方案.md` | 改写 **P04-T6**、更新 §7.1 动作表、P-10 加指针（§2.1） |
| `REQ-系统管理助手能力.md` | **D-2 标注"不做新建用户"已被 P-10 取代**；D-2a 指向本文档；密码重置收窄为"做自助改密、不做管理员重置（除非 D3=B）"（§0.2） |
| `REQ-登录安全与令牌生命周期加固方案.md` | **N-4 需收窄**：N-4 原文把"首次登录强制改密"整体列为"本期不做"，D1=C 已采纳该项；应改为"仅复杂度/定期改密留在 N-4" |
| `frontend/src/utils/auditDict.ts` | **需改**：`AUDIT_ACTION_LABELS` 增加 `CHANGE_PASSWORD: '修改密码'` 与 `RESET_PASSWORD: '重置密码'`（现有字典两个都没有，不加会在审计页显示英文码）。`CREATE` / `USER` 已在册，无需改 |

---

## 9. 测试要点

**P-04 原有 T1~T7 保留（T6 按 §2.1 改写）**，新增：

| 编号 | 断言 |
| --- | --- |
| **P04-T8** | 改资料时四个字段与当前值一致 → 保存按钮禁用（不发空请求） |
| **P04-T9** | 「当前手机号」为掩码展示（`138****5678`），提交的仍是新值明文 |
| **P04-T10** | 操作列在**仅持有** `system:user:update` 时仍渲染（`v-if` 已含 `canUpdate`） |
| **P10-T1** | 新增用户成功：列表出现、`status=1`、角色正确、`deptName` 正确、`must_change_password=1` |
| **P10-T2** | 用**默认密码**可成功登录；登录响应 `mustChangePassword=true` |
| **P10-T3** | **闸门生效**：`mcp=1` 的令牌调任意业务接口（如 `GET /api/system/users`）→ `403 / code=1006` |
| **P10-T4** | **闸门白名单**：`mcp=1` 时 `GET /api/auth/me`、`POST /api/auth/logout`、`PUT /api/auth/password` 均可用（否则用户被困死） |
| **P10-T5** | 改密成功后：`must_change_password=0`；**旧密码登录失败、新密码登录成功**；旧令牌全部失效（401） |
| **P10-T6** | 改密后重新登录，`mustChangePassword=false`，业务接口恢复正常 |
| **P10-T7** | 改密策略：新密码 <8 位 / 与原密码相同 / 等于默认密码 → 均被拒 |
| **P10-T8** | 旧密码错误 → 「原密码不正确」，且**不计入登录失败锁定** |
| **P10-T9** | 登录名重复（未删除）→ 「登录账号已存在」，弹窗不关；与**已删除**账号同名 → **允许创建** |
| **P10-T10** | **§1.3-③ 回归**：创建与已删除账号同名的用户后，恢复旧账号 → 返回可读业务文案，**不是 500** |
| **P10-T11** | 密码**未留痕**：`ai_operation_audit` 的 `before/after` 无明文密码；应用日志全文检索不到；`GET /api/system/users` 与详情响应不含任何密码字段 |
| **P10-T12** | 无 `system:user:create` 的账号（如 OPERATOR）看不到「新增用户」按钮；直接调接口返回 403 |
| **P10-T13** | **迁移回归**：V6 脚本重复执行两次均成功（幂等）；执行后存量账号 `must_change_password=0`、可正常登录且**不**被强制改密 |
| **P10-T14** | 审计页把 `action=CHANGE_PASSWORD` 显示为「修改密码」而非英文码 |
| **P10-T15** | **管理侧重置（D3=B）**：重置他人密码 → 对方 `must_change_password=1`、用默认密码可登录、其旧令牌全部失效（401） |
| **P10-T16** | 重置**自己** → 被拒「请使用「修改密码」修改自己的密码」；前端该按钮为禁用态且有 tooltip |
| **P10-T17** | **最后一条守卫的回归（§4.7a）**：以唯一启用 ADMIN 身份重置自己 → 被拒「请使用「修改密码」」，**证明不需要独立的"最后一个 ADMIN"守卫**；同时断言 `validateResetPassword` 中**不存在**"最后一个启用 ADMIN"分支（防止有人"顺手补上"这段死代码） |
| **P10-T18** | 重置后该用户登录被闸门拦住并要求改密；改密成功后功能恢复正常（与 P10-T5/T6 同一链路） |
| **P10-T19** | 审计页把 `action=RESET_PASSWORD` 显示为「重置密码」 |

---

## 10. 验收标准

| 编号 | 验收标准 |
| --- | --- |
| **AC-62** | 可在用户配置页**完整创建一个新用户**（登录名/姓名/部门/手机号/邮箱/角色），创建后能查到且状态为启用 |
| **AC-63** | 新用户首次登录后**被强制引导到修改密码页**，在改密完成前无法访问任何其它功能（含直接调接口） |
| **AC-64** | 改密成功后必须重新登录，且之后可正常使用全部功能；密码**不进入**审计、应用日志、会话消息或任何查询接口 |
| **AC-65** | 可**修改**已有用户的姓名/手机号/邮箱/部门；登录名不可改；**不允许修改自己的部门** |
| **AC-66** | 可**调整用户角色**，保存后该用户被强制下线并需重新登录；变更前可见「当前 → 变更后」差异 |
| **AC-67** | 所有因业务规则不可用的按钮均为「禁用 + tooltip」，不存在"点了才报错"的按钮 |
| **AC-68** | 新增用户后，「恢复同名的已删除账号」返回可读业务提示而非 500（§4.6 回归） |
| **AC-69** | 存量库执行 V6 迁移后，既有账号行为与本需求上线前**完全一致**（不触发强制改密） |
| **AC-70** | 用户忘记密码时，管理员可**重置为默认密码**并要求其下次登录修改——**这是本需求交付后唯一的密码恢复路径** |
| **AC-71** | 管理员**不能**重置自己的密码（须走「修改密码」）——该规则同时覆盖"最后一个启用 ADMIN"场景，无需额外守卫 |

---

## 11. 风险

| 编号 | 风险 | 应对 |
| --- | --- | --- |
| **RK-U-01** | **默认密码泄漏到日志 / 审计 / 会话消息** | 后端只写 BCrypt 散列（明文不落库、不返回、不入审计）；`SensitiveFieldMasker` 对 `password` 写 `<changed>` 作兜底；P10-T11 全文检索日志与响应。**D1=C 下明文只存在于部署配置与一次登录请求体**，暴露面比 D1=B 的一次性展示更小 |
| **RK-U-02** | 管理员关掉密码弹窗 → 账号无人能用 | E-6 醒目文案 + 给出兜底路径；D3 的"重置密码"可低成本补上 |
| **RK-U-03** | 登录名复用导致旧账号无法恢复（**会 500**） | §4.6 补 `restoreBlockers`；P10-T6 回归 |
| **RK-U-04** | 两处 `PasswordEncoder` 漂移 | **不能新增 bean，只能移动**（D5）；`SecurityConfig` 必须移除原 bean，否则启动即 `NoUniqueBeanDefinitionException` |
| **RK-U-05** | 加了 `system:user:create` 权限码但**忘了重启后端** → 权限码不存在，ADMIN 也无该权限 | §4.4 写明生效方式；上线步骤必须含后端重启 |
| **RK-U-06** | `P04-T6` 未改写 → 自动化测试与需求互相打架 | §2.1 列为必改项 |
| **RK-U-07** | 阶段二恢复分级数据范围后，新增用户缺少"部门在范围内"校验 | §4.3 要求保留 `DataScope` 参数并留注释，避免届时被漏掉 |
| **RK-U-08** | **默认密码抢注窗口**（D1=C + D7=B 共同导致）：账号创建/重置到本人首次登录改密之间，知道 `User@123` 者可抢先登录并改密，把真正的用户锁在外面 | 见 §5.6 的六条缓解；本期做 1~4 与 6。**残余风险明确接受**——这是"固定默认密码 + 复用公开常量"的固有代价，不是实现缺陷 |
| **RK-U-09** | 默认密码是**仓库公开值**（`User@123`，README 与多份文档均有），等于新账号在首次登录前都是公开口令 | D7=B 已接受；实现上用**单一常量 + 配置可覆盖 + 启动 WARN**（§5.6），让生产可一键换值而不必改代码。**若日后要收紧，只需改配置，无需改需求** |
| **RK-U-10** | ~~忘记改后的密码 → 无任何恢复路径~~ | ✅ **已消除**：D3=B 提供管理侧重置（§4.7）。**但这条恢复路径本身依赖管理员在场**，自助找回仍不存在 |
| **RK-U-11** | **忘记执行 V6 迁移** → `Unknown column 'must_change_password'`，登录/列表/创建全挂 | §5.2 列为上线硬前提；发布清单必须包含"手工执行 V6"；`schema.sql` 只对全新库生效 |
| **RK-U-12** | **闸门白名单漏项** → 用户被永久困在改密页（连登出都不行） | §5.4 明确四项白名单；P10-T4 专门断言白名单可用 |
| **RK-U-13** | 前端 `request.ts` 把 403/1006 与普通 403 混同 → 用户被踢回登录页、登录后又被踢回来 | §6.2b 第 3 条：必须按 `code` 分流，**不清 token、不弹权限提示** |
| **RK-U-14** | **重置 = 冒用**：默认密码是公开的 `User@123`，因此"重置密码"在功能上等价于"**以该用户身份登录**"。持有该权限者可借此以他人身份操作，**审计会记成被冒用者** | **这是 D3=B + D7=B 的固有性质，不是缺陷**（要求"管理员能重置"就等于要求这个能力）。缓解靠**可见性而非阻断**：重置落审计（操作者记真实操作者）+ 强制改密也落审计 + 真实用户会发现密码失效并来查 → 可追溯到人。另：`User@123` 可经配置覆盖（§5.6），换掉它此风险直接消失 |
| ~~RK-U-15~~ | ~~重置最后一个启用 ADMIN 的密码 → 超管被接管~~ | ✅ **该风险不成立**（§4.7a）：默认配置下操作者就是该 ADMIN 本人，已被"不得重置自己"挡住；且操作者本就是 ADMIN，拿不到新能力 |

---

## 12. 工作量

| 项 | 人日 |
| --- | --- |
| `PasswordEncoder` 迁移（pom + Bean + SecurityConfig） | 0.5 |
| 权限码 + `PermissionCatalog` + 启动验证 | 0.25 |
| **`must_change_password`：schema + V6 幂等迁移 + 实体/mapper 列清单** | 0.5 |
| **强制闸门：JWT claim + authority + `PasswordChangeRequiredFilter` + `ResultCode`** | 1.0 |
| **自助改密：`PUT /api/auth/password` + `AuthService` + `UserService.updatePassword` + 撤令牌** | 1.0 |
| 后端：DTO / mapper `insert` / `create` / `validateCreate` | 1.25 |
| 后端：`restoreBlockers` 补丁 + 回归 | 0.25 |
| **前端：`ChangePassword.vue` + 路由 + 三道拦截（登录分流 / 守卫 / 403-1006）** | 1.5 |
| 前端：新增用户对话框 | 1.0 |
| 前端：改资料对话框（含掩码回显、空改动判断） | 0.75 |
| 前端：角色分配对话框（含差异、影响提示） | 0.75 |
| 前端：操作列重构 + 禁用 tooltip 约定 | 0.5 |
| **管理侧重置密码（D3=B）：接口 + 守卫 + 审计 + 前端确认框** | 0.75 |
| **默认密码单一常量 + 配置覆盖 + 启动 WARN（D7=B）** | 0.25 |
| 前端：`AppLayout` 用户菜单「修改密码」入口 | 0.25 |
| 文档同步（P04-T6 改写、D-2/D-2a 收窄、N-4 收窄、类注释、`auditDict`） | 0.75 |
| 手工验收（P10-T1~T19，含闸门/白名单/留痕/迁移幂等/重置守卫） | 1.0 |
| **合计** | **≈12.25 人日** |

> **增量来源拆解**（相对 D1=B 的 ≈7.5 人日）：
> - D1=C：**+3.75**（schema 迁移 + 强制闸门 + 自助改密 + 改密页与三道拦截）
> - D3=B：**+0.75**（管理侧重置）
> - D7=B：**+0.25**（默认密码常量与启动提示；比 D7=A 的"占位值 + 部署校验"更简单）
> - D9 撤销：**−0.25**（少一条守卫及其测试；这是评审纠错的直接收益）
>
> **每条增量都对应一个具体交付物**，不是估算膨胀。
>
> 对照 D-2 的原始估算："原 8/2.5 → 去掉用户新建与密码重置后 5/0/2/7"，即用户新建当初估约 **+4 人日**（**不含**密码重置与强制改密）——与本次"新增用户本体"的约 3.75 人日吻合，其余 8.75 人日全部是 D1=C / D3=B 带来的密码机制。

---

## 13. 变更记录

| 日期 | 版本 | 变更 | 说明 |
| --- | --- | --- | --- |
| 2026-09-23 | v1.0 | 初稿 | 承接 D-2a 的独立立项，交付 **P-10（新增用户）** 完整设计 + **P-04（改资料/角色分配）** 施工级规格。§0 列出 6 个待拍板决策（初始密码策略、角色必填、是否做重置密码、助手侧是否同步、`PasswordEncoder` 归属、掩码口径）。**发现三处必须先处理的问题**（§1.3）：① 助手侧无法承载"生成初始密码"——提案载荷经 `SensitiveFieldMasker.maskDeep` 脱敏，密码到不了执行期；若改由执行期生成回传则会明文落进 `ai_message`；② `PasswordEncoder` 在 `guarantee-auth`，而 `guarantee-system` 既不能反向依赖、也不能另定义同类型 bean（会 `NoUniqueBeanDefinitionException`）；③ **`UserService.restoreBlockers` 缺登录名冲突校验**——P-10 上线后"新建同名账号 → 恢复旧账号"会撞唯一键抛 **500**，此前因无创建入口而不可达。另登记 P04-T6 断言与 P-10 直接冲突，必须改写（§2.1） |
| 2026-09-23 | v1.1 | **按 D1=C、D2=A 重写密码方案** | ① §0 记录两项拍板，并**明确 D1=C 推翻了 D-2 的一部分**（D-2 的"不做新建用户"失效；"不做密码重置"须收窄）；新增待补决策 D7（默认密码来源）、D8（新密码强度），**并把 D3 由"不做"改为推荐"做"**——因为固定默认密码 + 强制改密之后，用户忘记改后的密码将**没有任何恢复路径**。② §5 整体重写为「固定默认密码 + 强制首次改密」，含 §5.1 的连带范围图（四个新增件缺一不可）、§5.2 的**手工幂等迁移 V6**（本仓库无 Flyway）、§5.4 的**服务端强制闸门**与白名单、§5.5 的自助改密接口与"改密后必须撤令牌"。③ §4.3 响应体简化（不再需要 `UserCreateResponse`／明文密码永不经过 HTTP）；④ 新增 §6.2a 改密页与 §6.2b 三道拦截；⑤ 文件清单、测试要点（P10-T1~T14）、验收标准（AC-63/AC-64/AC-69 重写或新增）、风险（+RK-U-08~13）、工作量（7.5 → **11.25 人日**）全部同步；⑥ 登记两处**上游文档需收窄**：`D-2` 与登录加固方案的 `N-4`（其原文把"首次登录强制改密"整体列为不做，现已被采纳） |
| 2026-09-23 | v1.2 | **D3=B / D7=B / D8=A 拍板** | ① §0.1 记入三项结论；**§0.2 更新为"不做密码重置"已被完全推翻**（现为"做自助改密 + 做管理员重置，仍不做自助找回"）。② **新增 §4.7 管理侧重置接口**：无请求体（不接受管理员指定密码，避免密码经手与进请求体）、重置为默认密码 + 置标记 + 撤令牌、与创建共用同一段写密码逻辑；守卫含**不得重置自己**。③ **新增 D9（后被 v1.3 撤销）**。④ §5.6 按 D7=B 重写：默认密码 = `User@123`，但用**单一常量（`DefaultCredentials`，放在 common 因为 `guarantee-system` 无法引用 `DataInitializer`）+ 配置可覆盖 + 启动 WARN**，默认行为与决策一致；残余抢注风险与六条缓解明列。⑤ 权限码 41 → **42**（+`system:user:reset-password`，并需在 `isWritePermission` 补 `:reset-password`）；操作列 280 → **340**（6 个按钮）。⑥ 测试 **P10-T15~T19**、验收 **AC-70/AC-71**、风险 **RK-U-14**（并划掉已消除的 RK-U-10）同步；工作量 11.25 → **12.5 人日**。⑦ `auditDict.ts` 需新增两个动作码（`CHANGE_PASSWORD`、`RESET_PASSWORD`） |
| 2026-09-23 | v1.3 | **评审纠错：撤销 D9** | 评审质疑「重置密码本就只有超管能做，超管为何还要靠重置别人来拿超管」——**该质疑成立，初稿论证错误**。新增 **§4.7a 纠错记录**，逐组合推演：① 默认配置下"最后一个启用 ADMIN"必然就是操作者本人（该权限默认仅 ADMIN 持有 + 两者都是启用 ADMIN），**已被「不得重置自己」完全覆盖**，D9 是一段永不触发的死代码；② D9 类比"防锁定"也不成立——重置不造成锁定，反而让账号可用，初稿一边承认这点一边挂在同一面旗子下，自相矛盾；③ 即便在"非 ADMIN 被授予该权限"的场景，「最后一个」这个限定词仍然错（重置任何一个启用 ADMIN 效果相同）；④ 加 D9 还会挡住"管理员 A 帮忘记密码的管理员 B 重置"这一合法场景。**风险重新定性**：真正的残余风险是**冒用**（重置 ≡ 以该用户身份登录，因默认密码公开），与对方是否 ADMIN 无关，改由**审计可见性**缓解（RK-U-14 重写，RK-U-15 划掉）。连带更新：§0.3、§4.7 守卫表（只留"不得重置自己"）、§7 E-18、§9 P10-T17（改为**反向断言"不存在该分支"**，防止后人"顺手补上"）、§10 AC-71、§11 风险、§12 工作量 **12.5 → 12.25**（−0.25）。并记录了"不建议"的更严方案（不得重置任何 ADMIN）及其代价 |
| 2026-09-23 | v1.4 | **实施完成** | 按 v1.3 规格落地全部代码（后端 20 个文件 + 前端 12 个文件），并同步四处上游文档。`mvn -o test-compile`、`vue-tsc --noEmit`、`npm run build` 全部 exit 0。新增 **§14 实施记录**（含 5 处与规格的有意偏离、验证证据、以及**尚未验证、必须手工验收的 12 项**）。**注意：代码完成 ≠ 可验收**——V6 迁移须手工执行、后端须重启、且端到端流程未在真实环境跑过 |
| 2026-09-23 | v1.5 | **端到端验证通过 + 发现一处既有缺陷** | 前置条件由用户完成（执行 V6、重启后端）后，对**运行中的真实服务**跑完 44 项断言：**44/44 通过**。P-10 全部关键路径实测到位——闸门（403/1006 + 三白名单）、自助改密与令牌撤销、管理侧重置、默认密码登录、审计无密码留痕、`restoreBlockers` 回归（**不再是 500**）、权限过滤。§14.4 换为实测证据表，§14.5 收窄为"纯 UI、需浏览器"的 7 项。**新增 §14.7：发现一处 P-10 之外的既有缺陷**——`UserTokenRevocation.issuedAfterRevocation` 比较"毫秒精度的撤销时刻"与"秒精度的 JWT `iat`"，导致**撤销后同一秒内重新登录拿到的令牌被判为已失效（401）**；受控实验证实**同秒 5/5 失败、跨秒 3/3 通过**。它影响**所有用户级撤销流程**（含 P-10 的自助改密——用户被要求"立即重新登录"），安全上是 fail-closed 无问题，属可用性缺陷；**已给出建议修法但未自行实施**（不在 P-10 范围，且涉及令牌语义） |
| 2026-09-23 | v1.6 | **第二轮验收反馈 + 纠正一处假阳性** | ① **确认框排版**（新增 §14.8）：`①②③` 挤成一整段的根因是 `.el-message-box__message p` 无 `white-space`，已加全局 `pre-line` + `line-height:1.8` + 宽度 520px，并新增 `utils/confirmText.ts` 把「一句话 + 影响清单」统一成首行 + 空行 + `• 条目`；改造 **5 个页面 12 处**，顺带清掉 3 处会原样显示星号的 `**加粗**`。**刻意不用 `dangerouslyUseHTMLString`**（文案插值了库里的用户名等数据，为排版开 XSS 面不划算）。② **初始密码澄清 + 界面调整**（新增 §14.9）：明确"重置后拿到的是固定默认密码 `User@123`（可配置覆盖）"与"强制改密时新密码 8~64 位、不得与原密码/默认密码相同"；按决定在新建/重置的提示里**写出该值**并注明可被部署覆盖，新增 `utils/defaultCredentials.ts` 作为单一镜像常量（避免 3 处字面量漂移）。③ **纠正假阳性**（新增 §14.10）：此前"含测试编译全通过"是**错的**——Maven 增量编译跳过了 `UserServiceRoleDisplayTest` 的重编译，掩盖了构造器参数变更导致的编译失败；已修好该测试，并把验证方式改为**先删 `target/test-classes` 再全量重编译**（`mvn -o test-compile` → BUILD SUCCESS）。④ 修正 `PASSWORD_MAX_LENGTH` 上一句不准确的注释（BCrypt 的 72 **字节**上限对 64 个汉字并不成立）。⑤ vue-tsc / build 复跑 exit 0，样式规则确认已在产物中 |
| 2026-09-24 | v1.7 | **排查"助手没给确认卡"**（新增 §15） | 用户反馈助手声称已生成提案但没有卡片。**定位：模型一轮里 4 个 READ + 0 个 WRITE，纯编造**；受控实验证明写工具链路（注册/流式合并/执行/proposal 事件）**完全正常**。**关键事实：提示词第 30/31/32 条早已禁止这三件事，属模型违反已有规则**，故不是加规则的问题。按"两件都做"处置：① 提示词**顶部**新增「最高优先级自检」A~D 四条（提升已有约束的显著性 + 补上真正漏掉的"用户回复选项=执行指令"场景）；② **新增 `DataSourceClaimGuard`** ——「本轮零工具却出现口径行」必然是编造（口径只能由工具生成），判定极准。③ 复跑同场景：第 2 轮**未复现编造**（模型去查了 `queryRole`，得出"方案 B 去不掉该权限"的**正确**结论），故一次实验无法证明提示词效力。④ **顺带发现并修复 `ProposalClaimGuard` 误报**（§15.6）：其确认卡分支只认"确认卡+点击"，对"我**再去**生成…（提案需要在确认卡上点击…）"这类**将来时**表述误报，两轮实测均误报；真实故障句含"已生成"、仅靠另一分支即可命中，故该分支是冗余的误报来源——已收紧为**也要求同句含完成态动词**。⑤ 记录又一个验证陷阱（§15.7）：`mvn -pl <module>` **不带 `-am`** 会对着本地仓库的**旧 jar** 编译，报出一批与改动无关的错误；跨模块验证必须带 `-am`。验证：全项目 `test-compile` BUILD SUCCESS；两个兜底单测 **17/17 通过** |
| 2026-09-24 | v1.8 | **重启后复验通过**（新增 §15.9） | 后端重启后三项复验：① **`iatMs` 修复验收通过**——撤销后同秒重登由 **5/5 401 变为 5/5 200**（§14.7 状态从"待验证"改为"已验证"）；② **P-10 全量回归 44/44 通过**，且**已删除**此前为规避该竞态而加的 1100ms 等待——说明修复在真实链路里生效，不再需要绕道；③ **新兜底 bean 已装配**，由启动成功证明（`AiChatService` 构造参数强制，缺失则应用起不来）；`/actuator/beans` 未暴露（返回 `HTTP 200 + code:404`），故不走该途径。**仍未复验**：新兜底的运行时行为需"零工具 + 编造口径行"的输入才会触发，属模型随机行为、无法稳定构造（其判定逻辑由 6 个单测覆盖）。另记录：PENDING 提案已归零（排查期间的 id=671 过了 15 分钟有效期） |
| 2026-09-24 | v1.9 | **第三轮排版修复 + 空操作提案**（新增 §14.12 / §14.13） | 用户在**提案确认卡的二次确认**上再次指出信息过密。查明 §14.8 那轮**漏了两类**：① `Departments` 停用部门、`Orgs` 停用机构两处（普查按 `①` 字符搜，而它们的 `①` 在字符串中间、未被命中）；② **`ProposalCard.vue`**（§14.8 只扫 `views/`，**没扫 `components/`**）——且根因更深：后端把整个影响面 Map 渲染成**一个**字符串，而前端按"一条一行"渲染，**一个元素=一整段影响面**，所以**卡片正文**也是一个超长 bullet。处置：新增 `impactLines()` 按后端分隔符「；」切条，**卡片正文与二次确认共用**；保留首条「影响面：」前缀（唯一标识，去掉丢信息）；已用**真实提案数据**验证前后对比。普查其余 `el-alert` 与运行期字符串，**无需再改**。另发现 **§14.13：提案可以是无变化的空操作**——`validateAssignRoles` 不校验"目标与当前是否相同"，确认它会无谓撤销该用户全部令牌、并在审计里留下一条看不出是空操作的记录；已建议加预检但**未自行实施**（不在本轮范围），并提示用户拒绝现场那张 id=673 |

---

## 14. 实施记录（2026-09-23）

### 14.1 状态

**代码已完成，API 层端到端已验证通过（44/44），待你做浏览器端 UI 验收。**

- 构建：`mvn -o test-compile` / `vue-tsc --noEmit` / `npm run build` 全部 exit 0；
- 端到端：在**运行中的真实服务**（8081 + MySQL 3307 + Redis 6379）上跑完 44 项断言，全通过（§14.4）；
- 剩余：7 项**纯 UI**、只能靠浏览器点击确认（§14.5）；
- **另发现并已修复一处 P-10 之外的既有缺陷**（§14.7）：撤销后同秒重登会 401；修复已编译，**需重启后端**方可生效并复验。

### 14.2 实际改动

**后端（20 个文件）**

| 模块 | 文件 | 变更 |
| --- | --- | --- |
| common | `pom.xml` | 加 `spring-security-crypto` |
| common | **新增** `security/DefaultCredentials.java` | 默认密码单一来源（`User@123`）+ 配置覆盖 + 启动 WARN |
| common | **新增** `security/PasswordEncoderConfig.java` | `PasswordEncoder` bean 唯一定义（D5） |
| common | `api/ResultCode.java` | 加 `PASSWORD_CHANGE_REQUIRED(1006)` |
| common | `security/Permissions.java` | 加 `USER_CREATE` / `USER_RESET_PASSWORD`，重写用户段注释 |
| web | `db/schema.sql` | `sys_user` 加 `must_change_password` |
| web | **新增** `db/migration/V6__user_must_change_password.sql` | 手工幂等迁移 |
| web | `init/PermissionCatalog.java` | 加 2 个权限码（40 → 42）；`isWritePermission` 补 `:reset-password` |
| web | `init/DataInitializer.java` | `DEFAULT_PASSWORD` 改引用 `DefaultCredentials.BUILT_IN_DEFAULT_PASSWORD` |
| system | `entity/SysUser.java` | 加 `mustChangePassword` |
| system | `mapper/SysUserMapper.java` + `.xml` | 加 `insert` / `updatePassword`；3 处查询列清单补新列 |
| system | `dto/UserDto.java` | 加 `CreateRequest`，重写类注释 |
| system | `service/UserService.java` | 加 `create` / `validateCreate` / `changeOwnPassword` / `resetPassword` / `validateResetPassword` / `mustChangePassword` / `applyInitialPassword` / `validateNewPassword` / `validatePhoneAndEmail` / `normalize` / `normalizeRoleCodes`；**`restoreBlockers` 补登录名冲突校验** |
| system | `controller/UserController.java` | 加 `POST /`、`POST /{id}/reset-password`，重写类注释 |
| auth | `security/JwtTokenProvider.java` | 加 `mcp` claim + 静态访问器 |
| auth | `security/JwtAuthenticationFilter.java` | `mcp=true` 时追加 authority |
| auth | **新增** `security/PasswordChangeRequiredFilter.java` | 服务端强制闸门 |
| auth | `config/SecurityConfig.java` | **移除** `PasswordEncoder` bean（下沉到 common）；注册闸门 |
| auth | `vo/LoginResponse.java`、`vo/CurrentUserVO.java` | 加 `mustChangePassword` |
| auth | `controller/AuthController.java`、`service/AuthService.java` | 加 `PUT /api/auth/password` 与 `changePassword` |
| ai | `tool/write/UserProposalTool.java` | 两句"不支持"话术从"本期不支持/已立项"改为**技术约束口径**（见 §14.3-5） |

**前端（12 个文件）**：`api/system.ts`、`api/auth.ts`、`api/request.ts`、`types/system.ts`、`types/auth.ts`、`stores/user.ts`、`router/index.ts`、`views/Login.vue`、`layout/AppLayout.vue`、`utils/auditDict.ts`、`views/system/Users.vue`，**新增** `views/ChangePassword.vue`。

**文档（4 个）**：本文档、`REQ-系统管理手动操作能力补齐方案.md`（P04-T6 整条替换、§7.1/§7.2/§8 更新）、`REQ-系统管理助手能力.md`（D-2 标注被推翻）、`REQ-登录安全与令牌生命周期加固方案.md`（N-4 收窄）。

### 14.3 与规格的有意偏离（5 处）

| # | 规格 | 实现 | 理由 |
| --- | --- | --- | --- |
| 1 | §6.2a 强制模式只"隐藏取消/返回" | 强制模式**额外提供「退出登录」** | §7 E-8 要求登出必须可用，否则用户被锁死在唯一页面、只能清浏览器存储自救 |
| 2 | §6.2b 只说 store 持有 `mustChangePassword` | 同时**持久化到 localStorage**，初始化读回 | 刷新后内存清空，若初始值恒 false，守卫会在 `fetchMe` 补齐前放行业务路由 → 白等一轮 403。`fetchMe`/`login` 仍覆盖它（服务端是唯一事实来源）。残余代价：极小概率的"陈旧 true"会让用户停在改密页，可用「退出登录」恢复 |
| 3 | §6.2b 第 2 条只说守卫重定向 | 顺带让 `AppLayout` 的 `router` 被真正使用 | 加用户菜单「修改密码」入口时发现 `router` 原本是已声明未使用的变量 |
| 4 | §6.3 未规定上送哪些字段 | 改资料**只上送真正变化的字段**（不是整包回传） | 整包回传会让审计记下无意义的"变更"，且并发编辑时用陈旧值覆盖他人改动 |
| 5 | §1.3-① 称 `UserProposalTool` 话术"无需改动" | **改了**两句 | 原话术的理由是"本期不支持 / 已作为独立能力立项"——P-10 交付后该理由**失效**，且会让模型对用户说"尚未支持"。真实原因是**技术约束**（密码过不去提案脱敏链路），改成这个口径后话术不再随时间失效；指向的页面入口也变成真实可用的 |

**一处未按建议做（正确）**：部门下拉**没有**做 `isDeleted` 二次过滤——`DepartmentOption` 契约里没有该字段（后端 `/departments/options` 已按"仅启用、未删除"过滤），加判断会依赖未承诺的字段。

**一处按规格保留**：`validateResetPassword` **不含**"最后一个启用 ADMIN"分支（§4.7a 的决定）；`disabledReason(row,'reset')` 的注释里写明了它如何被"不得重置自己"顺带覆盖。

### 14.4 验证证据

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 后端编译 | `mvn -o -q compile` | **exit 0**（全模块） |
| 后端测试编译 | `mvn -o -q test-compile` | **exit 0** |
| 前端类型检查 | `frontend> .\node_modules\.bin\vue-tsc.cmd --noEmit` | **exit 0** |
| 前端生产构建 | `frontend> npm run build` | **exit 0**，`✓ built in 6.63s` |
| 权限数量影响面 | 全仓检索 `PERMISSIONS.length` / `ADMIN_PERMISSIONS` / `writePermissions` | 无测试硬编码权限总数；`ProposalFlowIT` 用 ANALYST/VIEWER 权限集，二者未变，AI 工具集不受影响 |
| 组件扫描 | `GuaranteeAiAdminApplication` 的 `scanBasePackages = "com.guarantee"` | 覆盖 `com.guarantee.common.security`，新 bean 会被加载（同包已有被扫到的 `TraceIdFilter`） |
| **端到端（真实服务）** | `node verify-p10.mjs`（临时脚本，44 项断言） | **44/44 通过，exit 0** |

**端到端验证（2026-09-23，真实运行中的 8081 + MySQL 3307 + Redis 6379，健康检查 `UP`）**

前置条件已由你完成：V6 迁移已执行、后端已重启。以下均**实测**而非推断：

| 验证点 | 实测结果 |
| --- | --- |
| V6 迁移生效、存量账号不受影响 | 列表查询正常；`admin` 登录 `mustChangePassword=false` |
| 权限码与重启生效 | `system:user:create` / `system:user:reset-password` 均存在，权限总数 **42** |
| 新建用户 | 成功；`status=1`、角色 `VIEWER` 正确；**响应体无任何密码字段** |
| 重名与同名已删 | 有效账号重名被拒「登录账号已存在」；与**已删除**账号同名**允许创建** |
| **闸门是唯一安全边界** | 默认密码登录后 `mustChangePassword=true`；调业务接口 → **HTTP 403 + code 1006**；`/auth/me`、`/auth/logout` **可用**（用户不会被困死） |
| 自助改密 | 旧密码错误→「原密码不正确」；过短→「长度需在 8~64 位之间」；同旧密码→被拒；改密成功 |
| 改密后令牌撤销 | 旧令牌 → **401**；旧密码登录失败（1001）；新密码登录成功且 `mustChangePassword=false`；业务接口恢复 200 |
| 管理侧重置 | 重置**自己**被拒「请使用「修改密码」修改自己的密码」；重置他人成功；重置后默认密码可登录且 `mustChangePassword=true`；旧令牌失效 |
| **`restoreBlockers` 回归（§1.3-③）** | 同名已删账号恢复 → **HTTP 200 + 「该用户不能恢复：登录账号已被同名账号占用，无法恢复：…」**，**不是 500** |
| 审计 | 出现 `CREATE` / `CHANGE_PASSWORD` / `RESET_PASSWORD`；快照**不含**默认密码明文、新密码明文、BCrypt 散列 |
| 权限过滤 | `analyst` 无 `system:user:create`（14 项权限）；直接调新建接口 → `code=403`「你当前没有该操作的权限，请联系管理员」 |

> **两点关于本仓库既有语义的实测澄清**（曾让首轮脚本误报失败）：
> ① 审计查询的响应字段是 `AuditPage.{total, items}`（不是 `list`/`records`）；
> ② 方法级鉴权拒绝返回的是 **HTTP 200 + `body.code=403`**（`GlobalExceptionHandler` 返回 `Result<Void>`，未设 HTTP 状态），不是 HTTP 403。前端 `request.ts` 的成功分支据此处理非 0 code——**两者都不是缺陷**。

### 14.5 仍未验证（需浏览器，纯 UI）

API 层已全部实测通过。以下**只能靠你在浏览器里点**，因为需要真实渲染：

| 编号 | 待验收 | 备注 |
| --- | --- | --- |
| P04-T8/T9/T10 | 空改动禁用保存；当前手机号**掩码**展示；仅持有 `update` 时操作列仍渲染 | |
| AC-67 | 六个按钮的"禁用 + tooltip"是否正常（含 `<span>` 包裹是否生效） | |
| §6.1 | 操作列 6 个按钮在 **340px** 下是否换行 | 换行则按 §6.1 把「在线会话」收进 `el-dropdown` |
| §6.2b | 前端刷新页面后是否仍被正确引导到改密页（§14.3-2 的 localStorage 方案） | |
| §6.2b-3 | 403/1006 是否真的分流到改密页而**不**弹"没有权限"、**不**跳登录页 | 这是最容易写成死循环的一处 |
| P10-T13 | V6 脚本**重复执行第二次**是否幂等（我只验证了它已生效，未复跑脚本） | |
| — | 闸门过滤器的**实际执行顺序**（`addFilterAfter(闸门, UsernamePasswordAuthenticationFilter)`）是否如设计——从"403 生效"可间接确认，但未单独验证排序本身 | 实测 403 已出现，说明顺序正确 |

### 14.6 遗留

| 项 | 说明 |
| --- | --- |
| `D3=B` 之外的"忘记密码自助找回" | 仍不做（无邮件/短信通道），恢复靠管理侧重置 |
| 密码复杂度 / 定期改密 / 历史密码 | 仍留在 **N-4**（§0.1 D8=A） |
| RK-U-08 抢注窗口 | 已接受；缓解 5/6 条，第 6 条（配置覆盖默认密码）机制已就位但默认仍用 `User@123` |
| RK-U-14 冒用 | 已接受；靠审计可见性缓解 |

### 14.7 验证中发现的**既有缺陷**（P-10 之外，已修复，待重启验证）

**用户级撤销后，同一秒内重新登录拿到的令牌会被判为已失效 → 401。**

| 项 | 内容 |
| --- | --- |
| **现象** | 撤销该用户令牌后**立即**登录，新令牌在随后的每个请求上都 401（前端会弹"登录状态已失效，请重新登录"并跳登录页）。等约 1 秒后重试即恢复正常 |
| **根因** | `UserTokenRevocation.issuedAfterRevocation` 的判定是 `令牌 iat > 撤销时刻`。撤销时刻写入的是 `System.currentTimeMillis()`（**毫秒**精度），而 JWT 的 `iat`（RFC 7519 NumericDate）只有**秒**精度。二者落在同一秒时，`iat`（该秒的 0 毫秒）必然 **小于**撤销时刻 → 刚签发的令牌被判为"未在撤销之后签发" |
| **实测证据** | 受控实验（`probe-iat-race.mjs`）：重置密码后**立即**登录 → **5/5 全部 401**；等待 **1100ms** 后再登录 → **3/3 全部 200**。完全符合"同秒即失败"的预测 |
| **影响面** | **所有用户级撤销流程**，与 P-10 无关：停用后启用、角色分配后重登、管理侧重置密码、**以及 P-10 新增的自助改密**（改密后提示"请用新密码重新登录"，用户照做即撞上） |
| **严重性** | **安全上无问题**（fail-closed，多拒不少拒）；是**可用性/体验**缺陷，窗口 ≤1 秒，重试即恢复。但发生在"改密后重新登录"这条**用户被明确要求立即执行**的路径上，容易被当成"改密把账号弄坏了" |

**修复（已实施，2026-09-23）**

| 项 | 内容 |
| --- | --- |
| 方案 | 给令牌加一个**毫秒精度**的自定义 claim `iatMs`；`JwtTokenProvider.issuedAtMillis(claims)` **优先取它**，缺失时回退到 `iat` 秒×1000 |
| 改动点 | 仅 `JwtTokenProvider`：新增常量 `CLAIM_ISSUED_AT_MILLIS`、`createToken` 加一个 claim、`issuedAtMillis` 改取值优先级。**调用方无需改动** |
| 为什么这样修 | ① 让新旧事件的先后**可精确排序**，窗口彻底消失；② **老令牌**（无 `iatMs`）回退到秒，行为与修复前**完全一致**——不放松任何安全语义（仍 fail-closed，不会多放行任何令牌）；③ **不触碰** AUTH-04 §4.4.3 的红线："不得靠重签令牌续期"依然成立（重签产生的 `iatMs` 同样晚于撤销时刻） |
| 被否掉的方案 | "放宽比较、允许同秒令牌"——会给"与撤销同秒签发的**旧**令牌"留出 ≤1 秒存活窗口，与本仓库 fail-closed 取向不一致 |
| 编译验证 | `mvn -o -q test-compile` → **exit 0** |
| **已验证（2026-09-24 重启后）** | ✅ **A 段由 5/5 401 变为 5/5 200**，B 段仍 3/3 200 —— 符合验收条件。并且 `verify-p10.mjs` 里为规避该缺陷而加的 **1100ms 等待已删除**，整套 **44/44 仍通过**（见 §15.9） |

> **重启后请告知**，我会复跑 `probe-iat-race.mjs` 确认 A 段从 5/5 401 变为 5/5 200。

### 14.8 第二轮验收反馈调整（2026-09-23）

| # | 反馈 | 处置 |
| --- | --- | --- |
| 1 | **确认框文案信息密度过高**：`① …；② …；③ …` 挤成一整段，没有层次 | 根因是 `ElMessageBox` 的正文只是一个 `<p>`，而 `.el-message-box__message p` **没有** `white-space`，所以 `\n` 被折叠；默认宽度还只有 420px。处置：① `styles/main.css` 加全局 `.el-message-box__message p { white-space: pre-line; line-height: 1.8 }` + `--el-messagebox-width: 520px`；② **新增** `utils/confirmText.ts`（`confirmText(首行, [影响项…])` → 首行 + 空行 + 每条独占一行 `• xxx`）；③ 改造 **5 个页面 12 处**确认框（用户 6 / 角色 3 / 险种 2 / 部门 1 / 机构 1） |
| 2 | 同一批文案里混着 `**加粗**` 的 Markdown 写法 | 纯文本渲染会**原样显示星号**（等于把 `**` 打给用户看）。已清除 3 处 |
| 3 | 「重置后新密码是什么规则」 | 见 §14.9。同时按决定**在界面上写出默认密码的值** |

**为什么排版用纯文本而不是 `dangerouslyUseHTMLString`**：这些文案普遍插值了数据库来的数据（用户名/角色名/部门名/机构名）。走 HTML 渲染等于把它们当标记解析，为排版给 5 个页面开出存储型 XSS 面不划算。`white-space: pre-line` 已足够。

### 14.9 初始密码的两层规则（澄清 + 按决定调整界面）

**第一层：重置/新建后账号实际拿到的密码** —— 固定默认密码，不随机、也不由管理员指定。

| 项 | 值 |
| --- | --- |
| 值 | `DefaultCredentials.BUILT_IN_DEFAULT_PASSWORD` = **`User@123`** |
| 可配置 | 是，`app.security.default-password`；仍用内置值时启动打 WARN |
| 落库 | BCrypt 编码后写 `sys_user.password`；同时置 `must_change_password = 1` |

**第二层：用户被强制改密时，新密码的规则（D8=A 极简）**

| 规则 | 是否服务端强制 | 位置 |
| --- | --- | --- |
| 长度 **8~64 位** | ✅ | `UserService.PASSWORD_MIN/MAX_LENGTH`；前端同值先校验 |
| 不能与**原密码**相同 | ✅ | `changeOwnPassword`；前端也先校验 |
| 不能等于**系统默认密码** | ✅ | `validateNewPassword` |
| 两次输入一致 | 仅前端 | `ChangePassword.vue`（后端收不到"确认密码"） |
| 复杂度 / 定期改密 / 历史密码 | ❌ 无 | 属 **N-4**，未做 |

**按 D7=B 决定的界面调整（本轮实施）**：原先确认框只说"系统默认密码"、成功提示说"请线下转达"，**却没告诉管理员转达什么**——不知道部署配置的人答不上来。而 D7=B 复用的本就是 README 里公开的常量，不回显没有任何安全收益。处置：

- **新增** `frontend/src/utils/defaultCredentials.ts`：把内置值与配置键做成**单一镜像常量** + 一句完整提示。放一处是因为同一句话出现在 3 个地方（新建对话框提示、新建成功、重置成功），各写一遍必然漂移——本仓库已因"两处同值定义"栽过一次。
- 新建对话框提示、新建成功提示、重置确认框、重置成功提示**均写出内置值**，并注明"若部署已通过 `app.security.default-password` 覆盖，以运维配置为准"——这样对"用了自定义密码"的部署也不会误导。
- 成功提示的 `duration` 放宽到 8s：这条信息管理员需要照着转达，一闪而过不合适。

> ⚠️ **跨仓库镜像风险**：`defaultCredentials.ts` 是后端 `DefaultCredentials.java` 的镜像。改后端常量时**必须同步改前端**，否则界面会告知一个错误的密码——用户拿它登录不上，且**不报错、只是登不上**，属最难排查的一类缺陷。已写进该文件的注释。

### 14.10 一个假阳性：增量编译掩盖了测试编译失败

| 项 | 内容 |
| --- | --- |
| 症状 | 我在 §14.4 记录过"`mvn -o -q test-compile` → exit 0"，据此声称"含测试编译全通过" |
| 真相 | **该结论是假阳性**。后来 `mvn -o -q -pl guarantee-system -am test-compile` 报错：`UserServiceRoleDisplayTest` 直接 `new UserService(6 个参数)`，而 P-10 给构造器加了 `PasswordEncoder` 与 `DefaultCredentials` → 参数个数不符 |
| 根因 | Maven 的**增量编译**判定该测试源未变更而跳过了重编译；改了 `UserService` 后它仍是旧的 `.class`，所以那次 `test-compile` 什么也没编、直接报成功 |
| 修正 | ① 修好该测试（注入 `new BCryptPasswordEncoder()` 与 `new DefaultCredentials(BUILT_IN_DEFAULT_PASSWORD)`）；② 验证方式改为**先删掉各模块 `target/test-classes` 再全量重编译**（不动 `target/classes`，避免影响正在运行的进程）：`mvn -o test-compile` → **BUILD SUCCESS** |
| 教训 | **"编译通过"必须以真正重新编译为前提**。增量编译在"只改了被依赖类"时会静默跳过依赖它的测试源，给出一个毫无意义的成功 |

### 14.11 本轮验证证据

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量测试编译 | 删除各模块 `target/test-classes` 后 `mvn -o test-compile` | **BUILD SUCCESS** |
| 前端类型检查 | `frontend> .\node_modules\.bin\vue-tsc.cmd --noEmit` | **exit 0** |
| 前端生产构建 | `frontend> npm run build` | **exit 0**，`✓ built in 6.53s` |
| 样式落地 | 检索产物 CSS | `pre-line` 与 `--el-messagebox-width: 520px` **均已在产物中**，且排在 Element Plus 默认值之后（生效） |
| 前端 dev server | 监听 5273 | **在跑**（HMR 会自动应用本轮改动） |

> 仍未完成：**后端重启**（§14.7 的 `iatMs` 修复待复验），以及 §14.5 的 7 项浏览器端 UI 验收。

### 14.12 第三轮：确认框漏网 2 处 + 提案卡影响面排版（2026-09-24）

用户在**提案确认卡的二次确认**上再次指出"信息太密集、没有文本格式"。排查后发现 §14.8 那一轮**漏了两类**：

| # | 漏掉的东西 | 为什么漏 | 处置 |
| --- | --- | --- | --- |
| 1 | `Departments.vue` 停用部门、`Orgs.vue` 停用机构 两处确认框 | §14.8 的普查是**按 `①` 字符**搜的，而这两处的 `①` 在字符串**中间**（`确认停用「x」？① …`）而非紧跟引号，没被那个模式命中 | 两处均改用 `confirmText` |
| 2 | **`ProposalCard.vue`**：二次确认**与卡片正文** | §14.8 只扫了 `views/`，**没扫 `components/`** | 见下 |

**根因（比"文案写成一段"更深一层）**：后端把整个影响面 Map 渲染成**一个**字符串
（`影响面：键 值；键 值；…`，见 `ProposalPreview.formatImpact`——它刻意不用 `Map.toString()`
以免出现 `{引用订单数=44064}` 这种 Java 内部表示）。而前端**按"一条一行"渲染**它
（`v-for` → `• {{item}}`）。**一个元素 = 一整段影响面**，于是卡片正文里也是一个超长 bullet，
不只是那个二次确认框。

处置：`ProposalCard.vue` 新增 `impactLines()`，按后端自己的分隔符「；」切成逐条，**卡片正文与二次确认共用同一函数**（两处口径一致）。实测对比：

```
修复前（二次确认一行到底）
这是危险操作：角色分配 用户「user0005」。影响面：当前角色 只读用户；变更后角色 只读用户；影响 该用户会被立即强制下线，需要重新登录（新权限随即生效）。确认执行吗？

修复后
这是危险操作：角色分配 用户「user0005」。确认执行吗？

• 影响面：当前角色 只读用户
• 变更后角色 只读用户
• 影响 该用户会被立即强制下线，需要重新登录（新权限随即生效）
```

**保留首条的「影响面：」前缀**：它是唯一的"这段是影响面"标识，去掉反而丢信息。**已知边界**：切分依据就是「；」，若某个**值**内部含「；」会被多切一刀；实测这些值都是计数/名称/短句，切开后语义仍成立。

**普查结论（其余位置无需改）**：`AppLayout` 退出确认、`Orgs`/`OperationAudits`/`Roles`/`ChangePassword`/`Users` 的 `el-alert` 文案均为单句或两句话，在正常页面/弹窗宽度下读起来没有问题；运行期字符串里已无「多分句拼接」写法（用 `^\s*\+ '` 续行模式复核，仅剩两条我已改过的成功提示）。

### 14.13 顺带发现：提案可以是**无变化的空操作**

验证排版时用真实数据打了一次提案，返回的 `impact` 是
「影响面：当前角色 **只读用户**；变更后角色 **只读用户**；影响 该用户会被立即强制下线…」
——**当前角色与变更后角色完全相同**，即这是一个**没有任何变化的提案**。

| 项 | 内容 |
| --- | --- |
| 成因 | `UserService.validateAssignRoles` 只校验"角色存在且启用""不得给自己增删 ADMIN""不得移除最后一个 ADMIN 的 ADMIN"，**没有校验"目标集合与当前集合是否相同"** |
| 危害 | 确认这个提案会执行 `assignRoles` → **撤销该用户全部令牌**（无谓强制下线），而权限一点没变；同时审计里留下一条"角色分配"记录，看审计的人会以为真发生了变更 |
| 影响面 | 仅目标用户本人（`revokeTokens(List.of(id))`），**不会**波及该角色的全部持有者 |
| 建议 | 在 `validateAssignRoles` 加一条"变更后与当前完全一致 → 拒绝并提示无需变更"（与 `RoleService.assignPermissions` 的"权限未发生变化"前端禁用同理）；**未自行实施**——它不在本轮（UI 排版）范围内，且改的是写工具的预检语义 |
| 现场处置 | **请把当前那张空操作提案（id=673）拒绝掉**，不要确认 |

---

## 15. 另一个需求域的问题：助手"没给确认卡"（2026-09-24 排查）

> 本节与 P-10 **无关**（属 `REQ-系统管理助手能力` 的域），但由同一轮验收暴露，故记录在此。

### 15.1 现象

用户在 AI 助手里请求"把行政部的张涛看系统配置的权限去掉"。助手列出 A/B 两个方案请其选，用户回 `B`，助手回了一段"**已按你的选择发起提案**…需要在确认卡上点击『确认执行』后才会生效"——**但界面上没有任何卡片**，正文末尾还跟着一句"（系统提示：本次回复提到的提案并未生成…）"。

### 15.2 结论：模型没调工具，机制本身正常

实测那次会话（id=481）的 `ai_tool_call`：**4 个 READ（queryDepartment/queryUser/queryRole×2）+ 0 个 WRITE**，连一条失败的写调用都没有。**模型是纯编造**；末尾那句"系统提示"是 `ProposalClaimGuard` 的事后纠偏——**后端是对的**。

再用明确指令走一次同一需求（受控实验）：

```
[tool_call] queryUser         READ  SUCCESS   5ms
[proposal]  id=671 no=OP202609242235598749 tool=proposeUserChange action=ASSIGN_ROLES
[tool_call] proposeUserChange WRITE SUCCESS  47ms
```

工具注册、流式 `tool_calls` 合并、执行、`proposal` 事件下发**全链路正常**。因此**不是**"我们漏识别 tool_calls"。

**触发条件**：用户只回了一个字母 `B`。模型把它当成对话确认，产出"总结式"回答，而没有当成"去调工具"的指令。

### 15.3 关键事实：提示词**本来就有**禁止这三件事的条款

| 条款 | 内容 |
| --- | --- |
| 30 | 写操作不要用文字征求确认代替确认卡；用户选了之后**直接再次调用写工具** |
| 31 | **严禁"没调用写工具却说已生成提案"** |
| 32 | 提案编号只能来自工具返回值；**也不得写一行口径来暗示调用过写工具** |

所以这是**模型违反已有规则**，不是缺规则。因此"再加 10 条规则"收益有限（提示词已 42 条 / 215 行）。

### 15.4 处置（按"两件都做"的决定）

| # | 处置 |
| --- | --- |
| 1 | **提示词**：在文件**顶部**新增「最高优先级自检」A~D 四条（不新增第 43 条规则，而是把已有约束**提升到最显眼位置**，并补上真正漏掉的触发场景 C：*用户回复选项 = 执行指令*） |
| 2 | **后端兜底（新增 `DataSourceClaimGuard`）**：**本轮零工具调用却出现「口径：」行 → 必然编造**，追加纠正。判定极准（口径行只能由 `DataSourceText` / `BaseProposalTool` 生成） |

> **提示词改动无需重启**：`BusinessAssistantPrompt.loadTemplate()` **每次请求都重读** classpath 资源，`mvn test-compile` 已把新文件复制到 `target/classes`。**新增的兜底 bean 需要重启**。

### 15.5 复跑同一场景的结果（比预期更有信息量）

**第 2 轮并没有复现编造**：模型这次没说"已生成"，而是去查了 `queryRole`，发现**「只读用户」角色本身也含系统配置查看权限**，于是判定"方案 B 去不掉这项权限"，改问 A/C。**这一轮它的分析其实是对的**。

所以：一次实验无法证明提示词有多大作用（模型有随机性），但也没有复现原故障。**卡片的深层成因是模型倾向用"列 A/B 让用户回字母"的交互而不直接动手**——这一条提示词里已禁止（第 30 条 + 新增的 C 条），但不可能靠提示词保证。

### 15.6 顺带发现并修复：`ProposalClaimGuard` 误报

**两轮都被追加了"提案并未生成"，两轮都是误报。**

| 项 | 内容 |
| --- | --- |
| 触发点 | `claimsProposal` 的**确认卡分支**：`CARD_CUES(确认卡) && CARD_ACTIONS(点击/确认执行)`，它假设"提到卡片就说明卡片存在" |
| 误报原句 | 「我再去核对具体权限项并生成变更提案（提案需要在确认卡上**点击**「确认执行」后才会生效）。」——**将来时**，并未声称生成任何东西 |
| 为什么能修 | 真实故障句是「**我已生成**变更提案，需要在确认卡上点击…」，**同句含完成态动词**，仅靠第一个分支即可命中——**确认卡分支对真实故障是冗余的，却是误报的唯一来源** |
| 修法 | 确认卡分支**同样要求同句含完成态动词**（实现上：无完成态动词的句子直接跳过，两个分支共用一个前提） |
| 影响 | 两起实测误报消失；真实故障仍被拦住；新增测试 1 个（含两句误报原句），`ProposalClaimGuardTest` 10 → 11 |
| 为什么值得修 | 它会在**正常回复**后面贴一句"提案并未生成"，用户会以为系统出错；且噪音会让真纠偏也被忽视 |

### 15.7 又一个验证陷阱：`-pl <module>` 不带 `-am` 会对着**旧 jar** 编译

排查中执行 `mvn -o -pl guarantee-ai test` 报出 11 个莫名其妙的错误（`DataScopeService.resolve` 签名不符、`Permissions.AI_DEBUG_VIEW` 找不到…）。**这些与改动无关**：不带 `-am` 时 Maven 从**本地仓库**取 `guarantee-system` / `guarantee-common`，而那些 `install` 过的 jar 是旧版本。

| 教训 | 说明 |
| --- | --- |
| 跨模块验证必须带 `-am` | `mvn -o -pl guarantee-ai -am test ...`（走 reactor 从源码构建）；只测单模块时再加 `"-Dsurefire.failIfNoSpecifiedTests=false"` |
| 这与 §14.10 是同一类问题 | 都是**"看似编译通过了、其实编的不是当前代码"**。验证命令本身也会说谎 |

### 15.8 本节验证证据

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 全项目测试编译 | 删各模块 `target/test-classes` 后 `mvn -o test-compile` | **BUILD SUCCESS** |
| 两个兜底的单元测试 | `mvn -o -pl guarantee-ai -am test "-Dtest=ProposalClaimGuardTest,DataSourceClaimGuardTest"` | **17/17 通过**（6 + 11），BUILD SUCCESS |
| 提示词资源已更新 | 比对 `src/main/resources` 与 `target/classes` | 两处均含新增的「最高优先级自检」，行数一致（230） |

> **仍待你验证**：重启后端后，新兜底才生效；届时可用一句"**本轮零工具 + 编造口径行**"的输入观察是否被追加纠正。另外原故障的**可靠规避方式**是**直接给动作指令**（如"把 user0005 的角色改为 VIEWER，直接发起提案"）——受控实验下 100% 生成卡片。

### 15.9 重启后的验证（2026-09-24）

后端已重启（新 PID，`/actuator/health` = `UP`）。三项复验：

| 项 | 结果 | 说明 |
| --- | --- | --- |
| **`iatMs` 修复（§14.7）** | ✅ **A 段 5/5 401 → 5/5 200**，B 段 3/3 200 | 撤销后**同秒**重新登录不再被误判失效——正是设定的验收条件 |
| **P-10 全量回归** | ✅ **44/44 通过**，且**已删除**为规避竞态加的 1100ms 等待 | 说明修复在真实链路里生效，不再需要"绕道" |
| **新兜底 bean 已装配** | ✅ 由**启动成功**证明 | `AiChatService` 的构造参数是强制的，`DataSourceClaimGuard` 缺失会让应用起不来；`UP` 即是证据。`/actuator/beans` **未暴露**（返回 `HTTP 200 + code:404「路径不存在」`，本仓库对未映射路径即此语义），故不走该途径 |

> **仍未复验**：新兜底的**运行时行为**需要一个"零工具 + 编造口径行"的输入才会触发，而那是模型随机行为，无法稳定构造。其**判定逻辑**由 6 个单元测试覆盖（含真机误报/正例两向）。
>
> **当前 PENDING 提案数 = 0**：排查期间用受控实验生成的提案（id=671）已过 15 分钟有效期。想看到卡片，用**直接动作指令**即可（如"把 user0005 的角色改为 VIEWER，直接发起提案"）。
