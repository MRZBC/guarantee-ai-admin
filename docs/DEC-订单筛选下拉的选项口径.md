# DEC-订单筛选下拉的选项口径（机构 / 险种）

> 状态：**已落地**（后端 + 前端 + 单测 + 真实 HTTP 走查）
> 触发场景：①「这个下拉里选了险种，列表却是空的 —— 造点这些险种的数据」
> ②「如果险种或机构删掉了，但是订单里面有相关的数据，也要能筛选哈」
> 相关：授权口径见 `docs/DEC-业务筛选下拉的授权口径.md`（"谁能用"与"列出什么"是两件事）

---

## 一、先给数据：缺的不是数据，是筛选项

本地库实测（`insurance_type` × 订单引用）：

| 险种 | id | 类别 | 名下订单 | 原下拉里 |
|---|---|---|---|---|
| 投标保函（标准） | 1 | TENDER | **44064 条投标** | ❌ 缺席（`status = 0`） |
| 电子投标保函 | 2 | TENDER | 36650 条投标 | ✅ |
| 投标保函（小额） | 3 | TENDER | 19286 条投标 | ✅ |
| 履约保函（标准） | 4 | PERFORMANCE | 22030 条履约 | ✅ |
| 履约保函（预付款） | 5 | PERFORMANCE | 18446 条履约 | ✅ |
| 履约保函（质量） | 6 | PERFORMANCE | 9524 条履约 | ✅ |

同一个筛选参数在两个页面上的结果：

```
投标订单 + 险种=履约保函（标准）(4)   → total=0        ← 用户看到的"没数据"
履约订单 + 险种=履约保函（标准）(4)   → total=22030    ← 数据一直在
```

四个毛病，逐条修：

| 毛病 | 表现 | 修法 |
|---|---|---|
| 下拉不分类别 | 投标页列出「履约保函…」，履约页列出「投标保函…」 | 前端按订单类别过滤 |
| 下拉只认 `status = 1` | 停用险种/机构名下的历史订单筛不到 | 可选集合含"被订单引用的" |
| 下拉只认 `is_deleted = 0` | 直连删除的险种/机构名下的历史订单筛不到 | 同上（"被引用"优先于删除标记） |
| **维度名被过滤掉** | 订单列表该列空白；数据概览「险种分布」里 44% 的那条**没有名字** | 去掉列表/分布图 join 上的 `status`/`is_deleted` |

> 前三条解决"能不能筛到"，第四条解决"筛出来看不看得见"——只修前三条会得到
> "筛选能筛、结果那一列是空的"这种半成品。

---

## 二、决策：下拉必须与"列表能展示什么"对齐

一条原则：**列表里能出现的机构/险种，筛选里就必须能选到；筛选里给出的每一项，也都必须能筛出数据。**

### 2.1 只列当前订单类别的险种（前端）

`OrderTable.vue` 按页面的 `orderType` 过滤 `category`：投标页只列 `TENDER`、履约页只列 `PERFORMANCE`。
接口仍返回两类全集，过滤放在组件里，避免为同一个下拉引入按类别分叉的接口契约。

### 2.2 可选集合 = 启用中且未删除 ∪ 被订单引用（后端，机构与险种同一规则）

```sql
WHERE (status = 1 AND is_deleted = 0)
   OR EXISTS (SELECT 1 FROM tender_order      t WHERE t.<维度>_id = 主表.id AND t.is_deleted = 0)
   OR EXISTS (SELECT 1 FROM performance_order p WHERE p.<维度>_id = 主表.id AND p.is_deleted = 0)
```

- 新增 `InsuranceTypeMapper.selectFilterOptions()` 与 `SysOrgMapper.selectFilterOptions()`；
  机构侧的"仅启用"下拉语句 `selectEnabledOptions` **已删除**——同一张表留两份近似的选项查询，
  下一次改口径必然只改一处（险种侧的"仅启用"口径 `selectAllEnabled()` 保留：AI Tool 与写操作
  路径用它解析险种，与"筛选"是两件事，不要互相替换）。
- **"被引用"优先于"停用/删除"**：这是本次新增的一半。历史订单不会因为主数据被停用或删除
  而从列表里消失，筛选就必须还能选到它。
- 子查询显式写 `is_deleted = 0`：逻辑删除拦截器只处理最外层 FROM/JOIN，子查询由 SQL 自身负责
  （设计 §5.2，与既有 `countOrderByType` 同一写法）；语句显式含 `is_deleted` → 拦截器整句跳过，
  因此 `status = 1` 那一支的 `is_deleted = 0` 必须自己写。
- 命中 `idx_tender_insurance` / `idx_perf_insurance` / `idx_tender_org` / `idx_perf_org`，
  EXISTS 是索引查找，不扫全表。

### 2.3 停用 / 已删除的项必须标注（前端）

后端把 `status` / `isDeleted` 一并回传（`OrgOptionVO` 新增两字段，险种侧 `InsuranceTypeVO` 本就有），
选项文案渲染为「XX（已停用）」「XX（已删除）」。这些项**保持可选**——筛选历史数据正是它存在的意义；
标注只是避免用户误以为它们还能用于新业务。

### 2.4 维度名称必须保留（订单列表 + 数据概览分布图）

前三条保证"能筛"，这一条保证"筛出来看得见"。两处 SQL 的维度 join 原先都带
`AND x.status = 1 AND x.is_deleted = 0`，而 `COUNT(*)` 统计的是订单本身——
维度条件只影响"能不能取到名字"，于是：

| 位置 | 原表现（实测） |
|---|---|
| 订单列表 `LEFT JOIN insurance_type / sys_org` | 直连删除机构后，筛出的行 `orgName=''`（列空白） |
| 数据概览「险种分布」 | `insuranceTypeId=1`「投标保函（标准）」**44064 条、占投标单量 44%，`typeName` 为空字符串**——前端直接把它当 ECharts 的 `name`，图上是一块无标签的扇区 |

**改法**：去掉这两类维度 join 上的 `status` / `is_deleted` 条件，让历史订单永远显示下单时的
险种名 / 机构名。逻辑删除字段与 LD 体系都**没有**改动（DDL 不变，`is_deleted` 照旧是唯一真相）。

两个必须一起守住的前提：

1. 这些语句仍显式包含 `is_deleted` 条件（订单主表 / 项目 / 企业的），因此逻辑删除拦截器
   **整句跳过**（`LogicalDeleteSqlRewriter`："已显式出现 is_deleted 条件时整句跳过"）。
   若哪天把条件删干净，拦截器会给维度表自动注入 `is_deleted = 0`，把这条口径悄悄抵消——
   两处 mapper 的片段注释与本条测试都在钉这件事。
2. 项目（p）/ 企业（e）维度在**列表与分布图**里维持原状（被删则名称变空）：订单页对这两项
   用的是模糊搜索而不是下拉，见 §2.5。

### 2.5 项目 / 企业：模糊搜索，不做下拉

项目与企业各有**数千条**（实测：项目 3343 条、企业 3000+ 条）。若照抄机构/险种做全量下拉，
一屏要翻三千项，既渲染不动也没法用。因此这两项走**远程模糊搜索**：

| 维度 | 做法 |
|---|---|
| 触发 | 输入 **≥2 个字** 才发请求（单字关键词范围太泛且结果没有区分度） |
| 防抖 | 300ms；请求进行中显示 loading |
| 条数 | 最多 20 条（`pageSize=20`），按后端既有排序 |
| 候选文案 | `项目名（项目编码）` / `企业名（企业编码）`——编码用来区分"浙江省水利工程项目0681"这类同名前缀 |
| 接口 | 复用既有的 `/api/projects?projectName=`、`/api/enterprises?entName=`（都是**登录即可访问**的业务接口，无需新增接口、无需扩权） |
| 已选项 | 远程搜索会把候选列表换掉，因此选中的整条记录单独留存并始终并入候选，否则标签会退化成裸 id |

**没有做的事**：没有为项目/企业新增专用检索接口，也没有放开任何权限。
编码（`projectCode` / `entCode` / 统一社会信用代码）目前**不参与匹配**——后端只支持名称 LIKE；
若用户习惯用编码找，需要在 `ProjectQuery` / `EnterpriseQuery` 上加一个 `OR 编码 LIKE` 的改动（未决项）。

---

## 三、删除守卫：**被订单引用不再拦删除**（口径已调整）

原先机构与险种的删除都带一道"被订单引用即拒绝"的守卫，理由是"删除后被引用的历史会指向一条
不存在的记录"。**这道守卫已按要求取消**：

| 实体 | 停用（`status = 0`） | 删除（`is_deleted = 1`） |
|---|---|---|
| 机构 | 允许，只拦"启用中的下级机构" | 只剩**一道层级守卫**：存在未删除的下级机构时拒绝（避免悬挂层级）。**关联订单不再是阻碍** |
| 险种 | 允许 | **没有任何前置检查**，被订单引用也可删 |

**为什么现在安全**：守卫当初要防的是"历史指向不存在的记录"，而本次口径已经把那件事解决了——

1. 历史订单仍照常展示该机构/险种的**名称**（§2.4：列表与分布图的维度 join 去掉 `is_deleted`）；
2. 仍能**按它筛选**（§2.2：候选 = 启用未删除 ∪ 被订单引用，含已删除）；
3. 删除是**可逆的**，恢复后回到删除前的状态。

于是"删除"的语义收敛为一句话：**从配置列表移除、不再用于新业务**，而不是"抹掉历史"。
详情记在 `docs/DEC-逻辑删除设计方案.md` §6.2。

**仍然拦删除的只有层级关系**（父机构下还有未删除的子机构）：删父留子会产生悬挂层级，
这不是"历史可见性"问题，而是结构完整性问题。

**助手侧同步改了**：`OrgProposalTool` / `InsuranceTypeProposalTool` 的描述、
两个 Executor 的执行期前置检查、以及提示词第 29 条，都从"被引用即拒绝"改为
"被引用也可以删，引用数量只作影响面"。否则模型会继续对用户宣称一条已不存在的规则。

---

## 四、变更清单

| 文件 | 变更 |
|---|---|
| `guarantee-system/.../mapper/InsuranceTypeMapper.java` / `.xml` | `selectFilterOptions` 口径扩为"启用未删除 ∪ 被订单引用（含停用/已删除）" |
| `guarantee-system/.../service/InsuranceTypeService.java` | `listFilterOptions()` 语义同步；`listAllEnabled()` 保持"可用于新业务" |
| `guarantee-system/.../controller/InsuranceTypeController.java` | `/options` 注释记录选项口径 |
| `guarantee-system/.../vo/OrgOptionVO.java` | 新增 `status` / `isDeleted`（前端据此标注「已停用」「已删除」） |
| `guarantee-system/.../mapper/SysOrgMapper.java` / `.xml` | 新增 `selectFilterOptions`；**删除**只认启用的 `selectEnabledOptions` |
| `guarantee-system/.../service/OrgService.java` | `listOptions()` → `listFilterOptions()`（能筛出数据的口径） |
| `guarantee-system/.../controller/OrgController.java` | `/options` 改调新方法 + 注释记录选项口径 |
| `frontend/src/types/system.ts` | `OrgOption` / `InsuranceTypeOption` 增加 `status` / `isDeleted` |
| `frontend/src/views/orders/OrderTable.vue` | 险种按订单类别过滤；机构与险种共用 `optionLabel()` 标注「已停用/已删除」 |
| `guarantee-order/.../mapper/order/TenderOrderMapper.xml` / `PerformanceOrderMapper.xml` | `fromJoin` 去掉险种/机构的 `is_deleted` 条件（订单列表保留历史名称）；项目/企业维持原状 |
| `guarantee-analysis/.../mapper/analysis/OrderAnalysisMapper.xml` | 险种分布 / 机构分布的维度 join 去掉 `status = 1 AND is_deleted = 0`（否则最大占比的那一条没有名字） |
| `guarantee-system/src/test/.../InsuranceTypeFilterOptionsIntegrationTest.java` | 7 项（含"已删除但被引用 → 可选"、"已删除且无引用 → 不可选"） |
| `guarantee-system/src/test/.../OrgFilterOptionsIntegrationTest.java` | 新增 6 项（停用/删除 × 有单/无单 + 启用恒可选） |
| `guarantee-order/src/test/.../OrderDimensionNamePreservationTest.java` | 新增 2 项文本守卫：险种/机构 join 不带 `is_deleted`，项目/企业条件必须留着（拦截器整句跳过的前提） |
| `guarantee-analysis/src/test/.../DistributionDimensionNameTest.java` | 新增 2 项文本守卫：分布图 join 不带 `status`/`is_deleted`，订单源仍自己过滤 `is_deleted` |
| `guarantee-system/.../service/OrgService.java` | **删除"关联订单"阻碍**：`deleteBlockers` 只剩"未删除的下级机构"；`deleteImpact` 文案改为"仅提示不拦" |
| `guarantee-system/.../service/InsuranceTypeService.java` | **删除 `deleteBlockers`（方法整体移除）**：险种删除不再有任何前置检查；`deleteImpact` 文案同步 |
| `guarantee-ai/.../tool/write/OrgProposalTool.java`、`InsuranceTypeProposalTool.java` | 工具描述去掉"被引用即拒绝"；险种 DELETE 分支去掉阻碍预检与"改用停用"引导 |
| `guarantee-ai/.../service/executor/OrgProposalExecutor.java`、`InsuranceTypeProposalExecutor.java` | 执行期前置检查与结果文案同步（机构只留层级检查） |
| `guarantee-ai/src/main/resources/prompts/business-assistant.st` | 第 29 条改写：**订单引用不拦删除**，只有层级/挂载关系才拦 |
| `frontend/src/types/order.ts` | `OrderQuery` 增加 `projectId` / `enterpriseId`（模糊搜索选中值） |
| `frontend/src/views/orders/OrderTable.vue` | 新增项目 / 企业**远程模糊搜索**（≥2 字、300ms 防抖、最多 20 条、保留已选项） |
| `guarantee-system/src/test/.../LogicalDeleteServiceIntegrationTest.java` | LD-T10 反转为"被订单引用的险种**可以**删除，且删除后仍在筛选下拉里" |
| `guarantee-web/src/test/.../LogicalDeleteWebIT.java` | AC-8 反转为"被订单引用的机构**可以**删除，且删除后仍可按它筛选" |
| `docs/DEC-逻辑删除设计方案.md` | §6.2 删除前置检查表与说明改写为"业务数据引用不拦删除、只有层级引用拦" |

**没有做的事**：没有新增订单数据。这些险种名下各有 9k–22k 条订单，缺的是筛选项，不是数据量。

---

## 五、验证记录

### 1. 单测

`mvn -o -DskipITs test`（全模块）→ **232 项全绿**：
common 3 + system 94 + auth 25 + order 2 + analysis 2 + ai 106。
本轮新增 17 项（两个选项口径 IT 13 项 + 订单/分析各 2 项文本守卫），另有 2 项按新口径**反转**
（`LD-T10` 险种、`AC-8` 机构：从"删除被拒绝"改为"可以删除且仍可筛"）。
夹具一律 `__lft_` / `__oft_` 前缀建行、`@AfterEach` 物理清理，跑完复查库中残留 0 行。

集成测试：`mvn -o -pl guarantee-web -am -Dit.test=LogicalDeleteWebIT verify` → **4 项全绿**
（`*IT` 走 failsafe，需 MySQL/Redis 可用）。

### 2. 真实 HTTP 走查（构建后的 jar，`:8087`，现场张涛 JWT）

一次性夹具（`__vt_` 前缀）：停用机构、直连删除机构、直连删除险种，各自挂上投标订单，
**不改动任何既有行**：

| 请求 | 结果 |
|---|---|
| `GET /api/system/orgs/options` | `code=0`，**23 项**（21 + 2 夹具）：停用机构 `status=0`、已删机构 `isDeleted=1` 都在列 |
| `GET /api/system/insurance-types/options` | `code=0`，**7 项**（6 + 1 夹具）：已删险种 `isDeleted=1` 在列 |
| 投标订单 + `orgId=停用机构` | `total=2`（修复前为 0） |
| 投标订单 + `orgId=已删机构` | `total=1`（修复前为 0） |
| 投标订单 + `insuranceTypeId=已删险种` | `total=3`（修复前为 0） |

夹具全部删除，库回到 **21 机构 / 6 险种 / 0 残留**。

按页面类别过滤后，演示数据里的每一项也都能筛出数据：

| 页面 | 下拉项 | 选中后 total |
|---|---|---|
| 投标订单 | 投标保函（标准）/ 电子投标保函 / 投标保函（小额） | 44064 / 36650 / 19286 |
| 履约订单 | 履约保函（标准）/（预付款）/（质量） | 22030 / 18446 / 9524 |

### 3. 维度名称保留（走查实测）

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 数据概览「险种分布」里的 id=1（演示数据，无需夹具） | `typeName=''`，44064 条（占投标单量 44%） | **`typeName='投标保函（标准）'`**，44064 条 |
| 订单列表：按直连删除的机构（`__vt2_` 夹具）筛选 | 行内 `orgName=''` | **`orgName='夹具-已删机构'`**，且 `total=1` |
| 订单列表：该行同时引用已删险种 | `insuranceTypeName=''` | `insuranceTypeName='投标保函（标准）'` |

夹具全部删除，库回到 **21 机构 / 6 险种 / 100000 订单 / 0 残留**。

### 4. 前端

`vue-tsc --noEmit` 通过；Vite 开发服务器已热更新（`localhost:5273` 编译产物含 `optionLabel`、按类别过滤与两个远程搜索）。

### 5. 删除守卫取消（走查实测，构建后 jar，`:8087`）

夹具走真实 HTTP 新建（`__vd_` 前缀），各挂一条投标订单，**不改动任何既有行**：

| 步骤 | 修复前 | 修复后 |
|---|---|---|
| `DELETE /api/system/orgs/{id}`（该机构名下 1 条订单） | `code=1000`「该机构不能删除：存在 1 条关联订单」 | **`code=0` 成功** |
| `DELETE /api/system/insurance-types/{id}`（该险种名下 1 条订单） | `code=1000`「该险种不能删除：已被 1 条订单引用」 | **`code=0` 成功** |
| 张涛侧 `GET /system/orgs/options` | — | 22 项，夹具项 `isDeleted=1` 仍在列 |
| 张涛侧 `GET /system/insurance-types/options` | — | 7 项，夹具项 `isDeleted=1` 仍在列 |
| 按已删机构筛投标订单 | — | `total=1`，`orgName='夹具-待删机构'`、`insuranceTypeName='夹具-待删险种'` |
| 按已删险种筛投标订单 | — | `total=1` |

夹具（含审计行）全部清理，库回到 **21 机构 / 6 险种 / 100000 订单 / 0 残留**。

### 6. 项目 / 企业模糊搜索（走查实测）

用张涛 JWT 按前端方式 URL 编码调用：

| 关键词 | 项目结果 | 关键词 | 企业结果 |
|---|---|---|---|
| `水利` | 963 条，首条「广东省水利工程项目2692（PRJ002692）」 | `远洋` | 197 条 |
| `浙江省水` | 353 条，首条「浙江省水利工程项目2392（PRJ002392）」 | `远洋科技` | 25 条，首条「远洋科技有限公司1171（ENT001171）」 |
| `不存在的项目名` | 0 条 | `不存在的企业名` | 0 条 |

> 后端需**重启**（`:8081` 的开发实例仍是旧代码），前端无需重新构建。

---

## 六、未决项

1. **`/api/orders/**` 仍无 `@PreAuthorize`**（与授权口径文档同一未决项，需单独拍板）。
2. **项目 / 企业的编码不参与模糊匹配**：后端只支持名称 LIKE。若用户习惯用项目编码 / 统一社会信用代码
   检索，需要在 `ProjectQuery` / `EnterpriseQuery` 上加 `OR 编码 LIKE`（一处改动）。
3. **项目 / 企业维度在订单列表里仍会因被删除而名称变空**（维持原状）：本轮只对机构/险种做了
   名称保留。若将来订单页也要按它们筛选，应按同一规则处理。
4. ~~新增险种不传保额区间会 500~~ **已修**：产品口径确认为「不填 = 不限」，见第七节。

---

## 七、附：险种保额区间「不填 = 不限」（走查中撞到的既有 bug，已修）

**现象**：新增险种时不传保额区间 → `code=500「系统内部错误」`。
根因是 `insurance_type.min_amount / max_amount` 为 `NOT NULL DEFAULT 0`，而接口允许不传，
mapper 又显式插入 `null` → `Column 'min_amount' cannot be null` → 被全局兜底报成系统故障。
（前端新建表单把 minAmount 设为必填、maxAmount 未必；AI 工具描述写的是"选填"——三处口径本就不一致。）

**口径（用户拍板）**：**可以不填，代表不限**（不设下限 / 不设上限）。

**实现**：沿用库里已有的 `DEFAULT 0`，把 **`0` 与空都定义为"不限"**，**不动 DDL**：

| 位置 | 规则 |
|---|---|
| 新增 | 不传 → 归一成 `0` 落库（不再 500） |
| 修改 | 传具体值 → 改成该值；传 `0` → 清空成「不限」；不传（null）→ 保持原值（部分更新语义，AI 只改一个字段时依赖它） |
| 校验 | 只有**上下限都给了具体值**时才要求 `min < max`；上限为空/0 时下限随便填 |
| 展示 | 配置页列表、表单占位、AI 确认卡、AI 查询工具出参：`0/空` 一律渲染成「不限」 |

**变更**：`InsuranceTypeService`（归一 + 校验）、`InsuranceTypeProposalTool`（描述 + 卡片文案
`plainAmount()`）、`InsuranceTypeQueryTool`（出参 `amountText()` + 工具说明）、
`frontend/src/views/system/InsuranceTypes.vue`（表单可留空、`0 ↔ 空` 双向转换、列表显示「不限」、
去掉 minAmount 的必填规则）。

**验证**：新增 `InsuranceTypeAmountBoundIntegrationTest` 6 项（不填/只填下限/上限显式 0 均成功；
两边具体值且 `min ≥ max` 报可读 400；改 0 清空上限；只改名不动区间）；全模块单测 **238 项全绿**
（common 3 + system 100 + auth 25 + order 2 + analysis 2 + ai 106）。

真实 HTTP 走查（构建后 jar，`:8087`）：

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 新增险种**完全不传**区间 | `code=500「系统内部错误」` | **`code=0`，落库 `min=0 max=0`（不限）** |
| 新增险种只传下限 100 万 | 同 500 | `code=0`，`min=1000000 max=0`（上不封顶） |
| 只改名称、区间不传 | — | 区间保持 `1000000 / 0`（部分更新语义生效） |
| 下限 200 万 > 上限 100 万 | 500（约束或兜底） | **`code=400`「最小保额必须小于最大保额（最高担保金额留空表示不限）」** |

夹具（`__IA_` 前缀）全部清理，险种回到 6 条。
