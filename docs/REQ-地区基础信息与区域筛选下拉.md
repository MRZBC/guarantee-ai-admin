# 需求：地区基础信息表（`sys_region`）+ 区域筛选下拉

> 状态：**已落地（P0 完成）** —— 见 §16 实施记录；本文档同时是口径与验收依据
> 提出人诉求：*「筛选项不要用区域编码了，用地区选项下拉框；要新建一个地区表，作为系统的地区基础信息」*
> 拍板结论：*「地区表就按照省市区来，作为字典，把省市都生成吧，要逻辑删除，不用权限码」*
> 现状核对时间：2026-09-25（开发库实测）
> 相关：`docs/DEC-订单筛选下拉的选项口径.md`（下拉"能筛出数据"的口径就出自那里，本文档沿用）

---

## 0. 决策结论（已拍板）

| # | 问题 | **结论** | 说明 |
|---|---|---|---|
| **D1** | 表几级、导入多少数据 | **省/市/区县三级全量：34 + 342 + 3056 = 3432 行** | 初版只导省+市；后按要求补全区县，因为机构区划要能填到区县 |
| **D2** | 下拉列哪些地区 | **整本字典（全部启用地区）** —— `onlyWithData` 默认 `false` | 初版取"只列有数据的"；后按"下拉看到全部字典数据"改为全量（`onlyWithData=true` 仍保留给分析/对账） |
| **D3** | 是否纳入逻辑删除体系 | **纳入**（受管表 18 → 19 张） | 与 `sys_permission` 等基础数据一致 |
| **D4** | 主键 | **用 `code` 作主键**（自然键，无代理 id） | 业务数据一直用 `region_code` 字符串引用它 |
| **D5** | 权限 | **不新增权限码**，字典接口登录即可 | 筛选字典的授权对齐"能看业务数据" |
| **D6** | 机构 `regionCode` 写入 | **省/市/区县三级都能填**（可传码或名称） | 初版限定省级；后按要求放开，筛选侧同步改成层级前缀匹配（见 §6.3） |
| **D7** | 「地区配置」维护页 | **本期不做**（国标基础数据，导入即用） | 需要时按 P2 另立 |

---

## 1. 背景与目标

### 1.1 问题：系统里**没有地区主数据**，区域全靠手填字符串

实测（开发库）四个具体毛病：

1. **筛选框提示的编码在库里根本不存在**：订单 / 项目 / 企业 / 机构配置四个页面的「区域编码」是自由文本输入，
   占位符写的是 `如 330100`（市级码），而**业务数据里只有省级码 `330000`** ——
   用户照着提示填，得到的是**空列表**（能筛出数据的前提被破坏）。
2. **没有单一事实源**：`region_code` / `region_name` 只是 5 张表上的字符串列（机构、企业、项目、投标订单、履约订单），
   谁写谁负责；初始化时靠 `DataInitializer` 里硬编码的 8 个省级常量。
3. **写入路径也在手填**：机构新增/修改目前**只能通过助手**（页面没有机构表单），
   `regionCode` 是模型给的自由文本（`@NotBlank`，只校验非空与长度）→ 可以写进一个不存在的区划码。
4. **名称与编码的对应关系无约束**：`regionName` 由前端传或从上级机构继承（更离谱的是"未指定"兜底），
   改一个机构的区划名不会影响别人，历史数据也不会有校验点。

### 1.2 目标

- 新建**地区基础信息表** `sys_region`（国标行政区划：省 / 市 / 区县三级结构），作为系统里"地区"的唯一事实源。
- 提供一个**只读字典接口**，把四个页面的「区域编码」输入框换成**地区下拉框**（可选、可搜索、"全部"= 清空）。
- 写入路径（机构新增/修改）**按字典校验区划码**，堵住新脏数据。
- 顺带把"下拉里出现的每一项都能筛出数据"这条既有口径复用到地区上。

### 1.3 非目标（本期明确不做）

- **不迁移**现有 5 张表的 `region_code` / `region_name` 冗余列，**不建外键**（见 §8）。
- 不做行政区划的**在线同步**（国标更新频率极低，人工导入即可）。
- 不做「地区配置」维护页面、不做地区的新增/删除（见 D7）。
- 不改数据概览的「区域分布」图（它按 `regionName` 聚合展示，不受影响）。
- 不改数据范围/权限：地区**不参与**数据范围过滤（阶段一 O3 起数据范围恒为全量）。

---

## 2. 现状核对（2026-09-25 实测）

### 2.1 业务数据里的地区值域：只有 8 个省级码

```
表                  region_code / region_name（条数）
sys_org             110000 北京市 2 | 310000 上海市 1 | 320000 江苏省 4 | 330000 浙江省 6
                    370000 山东省 2 | 420000 湖北省 1 | 440000 广东省 3 | 510000 四川省 2
enterprise          110000 117 | 310000 68 | 320000 765 | 330000 1059 | 370000 248 | 420000 134 | 440000 417 | 510000 192
project             110000 200 | 310000 164 | 320000 1204 | 330000 1792 | 370000 411 | 420000 241 | 440000 698 | 510000 290
tender_order        110000 4052 | 310000 2943 | 320000 24050 | 330000 35009 | 370000 8820 | 420000 4983 | 440000 13976 | 510000 6167
performance_order   110000 1952 | 310000 1458 | 320000 12339 | 330000 17257 | 370000 4554 | 420000 2505 | 440000 7028 | 510000 2907
```

**结论**：粒度是**省级**，没有市级/区县；`region_code` 与 `region_name` 一一对应，无别名。

### 2.2 四个"区域编码"输入口（改造点）

| 页面 | 位置 | 当前实现 | 行为 |
|---|---|---|---|
| 投标 / 履约订单 | `frontend/src/views/orders/OrderTable.vue:340` | `el-input` 占位 `如 330100` | 服务端精确匹配 `o.region_code = ?` |
| 项目管理 | `frontend/src/views/Projects.vue:146` | 同上 | 服务端精确匹配 |
| 企业管理 | `frontend/src/views/Enterprises.vue:145` | 同上 | 服务端精确匹配 |
| 机构配置 | `frontend/src/views/system/Orgs.vue:298` | 同上 | 服务端精确匹配 **+ 前端本地过滤**（`row.regionCode === regionCode`，`:84`） |

接口层参数一致：`PageQuery.regionCode` → mapper 里 `AND xx.region_code = #{q.regionCode}`
（**当时**是精确相等；v1.2 起改为层级前缀匹配，见 §6.3）。

### 2.3 现有"地区"相关代码资产（几乎为空）

| 项 | 现状 |
|---|---|
| 地区表 / 实体 / Service | **不存在**（全仓库只有 `region_code` / `region_name` 字段） |
| 地区字典（前端） | 不存在；分析页直接用后端返回的 `regionName` 当图表标签 |
| 地区初始化 | `DataInitializer.REGIONS` 硬编码 8 个省级（`{code, name, 权重, 机构数}`），仅用于造演示数据 |
| 机构区划名来源 | `OrgService.resolveRegionName(parentId)`：**从上级机构继承**，兜底 `"未指定"`（`:406-411`） |
| 机构区划码校验 | 仅 `@NotBlank` + `@Size(max=12)`；**无字典校验** |

---

## 3. 范围

**做**：`sys_region` 建表（三级结构）+ 省级数据导入 + 存量库迁移脚本 + 只读字典接口 +
四个页面的筛选下拉改造 + 机构写入的字典校验 + 一个"业务数据里的区划码都在字典里"的对账 SQL。

**不做**：冗余列迁移/外键、地区维护页、地区的新增删除、三级数据导入（本期）、分析页改造、
数据范围与权限模型调整。

---

## 4. 数据模型：`sys_region`

### 4.1 DDL 草案

```sql
CREATE TABLE IF NOT EXISTS sys_region (
    code        VARCHAR(12) NOT NULL                COMMENT '行政区划代码（GB/T 2260），省级 6 位',
    name        VARCHAR(64) NOT NULL                COMMENT '名称，例如 浙江省',
    short_name  VARCHAR(32) NULL                    COMMENT '简称/别名，用于搜索，例如 浙江',
    level       TINYINT     NOT NULL                COMMENT '层级 1省 2市 3区县',
    parent_code VARCHAR(12) NOT NULL DEFAULT ''     COMMENT '上级区划代码；省级为空串',
    status      TINYINT     NOT NULL DEFAULT 1      COMMENT '状态 1启用 0停用',
    sort_no     INT         NOT NULL DEFAULT 0      COMMENT '排序号（国标顺序）',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL DEFAULT NULL       COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB'   COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (code),
    KEY idx_sys_region_parent (parent_code, level),
    KEY idx_sys_region_deleted (is_deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '行政区划基础信息';
```

### 4.2 字段与设计说明

| 决策 | 说明 |
|---|---|
| **主键 = `code`**（D4 建议） | 业务数据已用 `region_code` 字符串引用它，再引入代理 `id` 只会多一个需要对照的标识。国标码稳定不可变，适合做自然主键。若评审倾向仓库惯例（`id` + `uk(code)`），改动仅限本表，不影响接口契约（接口只暴露 `code`）。 |
| **`level` + `parent_code`** | 三级用**邻接表**表达（与 `sys_department` 同款），不引入 `path` 列：本期只需按 level 取省级，未来取下级用 `parent_code` 递归/一层查询即可。 |
| **`short_name`** | 搜索友好（用户打"浙江"能命中"浙江省"）；`el-select` 默认按 label 匹配，加别名可避免"必须打全称"。 |
| **`sort_no`** | 国标顺序（110000 → 820000），避免按字典序把"北京"排到"安徽"后面。 |
| **逻辑删除三件套** | 纳入受管清单（D3 建议），与 `sys_permission` 等基础数据表一致。 |
| **不加 `pinyin` 列** | 拼音/首字母搜索需要额外词库与维护成本，本期不做（`short_name` + 编码已够用）。 |

---

## 5. 数据初始化与迁移

### 5.1 导入数据：省 34 + 市 342 + 区县 3056 = 3432 行

省级 34 个（GB/T 2260）：

```
110000 北京市   120000 天津市   130000 河北省   140000 山西省   150000 内蒙古自治区
210000 辽宁省   220000 吉林省   230000 黑龙江省
310000 上海市   320000 江苏省   330000 浙江省   340000 安徽省   350000 福建省   360000 江西省
370000 山东省
410000 河南省   420000 湖北省   430000 湖南省   440000 广东省   450000 广西壮族自治区
460000 海南省
500000 重庆市   510000 四川省   520000 贵州省   530000 云南省   540000 西藏自治区
610000 陕西省   620000 甘肃省   630000 青海省   640000 宁夏回族自治区   650000 新疆维吾尔自治区
710000 台湾省   810000 香港特别行政区   820000 澳门特别行政区
```

市级 342 个（含直辖市下的「市辖区」与省直辖县级行政区划，保持国标形态），
区县 3056 个（含直辖市下辖区、县级市、自治县等）。
数据来源与生成方式（种子文件头部也写了同一段）：

- 数据集：`modood/Administrative-divisions-of-China` 的 `pca-code.json`（省 31 / 市 342 / 区县 3056）；
- **代码补零到 6 位**（省 2 位补 `0000`、市 4 位补 `00`、区县本就是 6 位），
  例如 `33`→`330000`、`3301`→`330100`、`330102` 原样；
- 港澳台（`710000` / `810000` / `820000`）由该数据集缺失，按国标**手工补入**三个省级单位（无下级）；
- 名称口径以民政部/国标 `GB/T 2260` 为准（参考
  [民政部行政区划代码](https://www.mca.gov.cn/mzsj/tjbz/a/201713/201708220925.html)）。

省名统一用**全称**（"浙江省""内蒙古自治区"），与现有业务数据里的 `region_name` 一致——
若不一致，筛选下拉的显示名会和列表里的地区列对不上。
> 现有业务数据命中的 8 个省必须**逐字一致**（实测：浙江省 / 江苏省 / 广东省 / 北京市 / 上海市 / 山东省 / 湖北省 / 四川省）。
> 集成测试 `RegionServiceIntegrationTest.businessDataMatchesDictionary` 会对账这件事。

### 5.2 区县为什么后来还是导进来了

初版只导省+市，理由是"业务数据只到省级，区县没有数据可筛"。但**机构区划要能填到区县**
（产品要求），而"能填"的前提是字典里有 —— 否则区县码会被写入校验判为"不存在"。
于是补全区县：种子文件从 376 行扩到 **3432 行**（约 225 KB）。

由于种子是 `INSERT IGNORE` 且启动时自动执行，**补数据本身不需要任何迁移动作**：
重启一次即把新增的 3056 行插进去，已有行的名称/状态不被覆盖（这正是当初选 `INSERT IGNORE` 的收益）。

### 5.3 落地方式（**不需要手工迁移脚本**）

| 目标 | 做法 |
|---|---|
| 全新库 | `db/schema.sql` 追加 `sys_region` 建表语句（启动时执行，全部 `CREATE TABLE IF NOT EXISTS`） |
| 存量库 | **同一条路径**：`spring.sql.init.data-locations: classpath:db/seed/region.sql`（启动时执行，`INSERT IGNORE` 幂等） |
| 受管表清单 | `LogicalDeleteTables.MANAGED` 由 18 张 → 19 张 |
| 演示数据 | `DataInitializer` 不动：它只造业务数据；地区由 schema + seed 负责，避免"演示库有、正式库没有"两套来源 |

**为什么不开 `db/migration/V7__region.sql`**：仓库的 `db/migration/V*.sql` 是**手工执行**的脚本
（V6 的文件头就写着"本仓库没有 Flyway，上线前必须手工执行，否则会 Unknown column"）。
建**新表**这件事用 `schema.sql`（`IF NOT EXISTS`）+ `data-locations` 就能自动覆盖新库与存量库；
再写一份手工脚本，只会多一个"忘了执行"的失败点。相比之下，V1~V6 处理的是**给已有表加列**，
`CREATE TABLE IF NOT EXISTS` 无能为力，才必须手工。

**幂等性为什么用 `INSERT IGNORE` 而不是 `ON DUPLICATE KEY UPDATE`**：

1. 人工改过的名称/状态不会被每次重启覆盖回种子值；
2. 被逻辑删除的地区不会因为重启而"复活"。
   代价：日后修正国标名称要显式 `UPDATE`（seed 文件末尾给了示例）。

---

## 6. 接口设计

### 6.1 `GET /api/system/regions/options`（只读字典）

| 项 | 设计 |
|---|---|
| 参数 | `level`（可选，1 省 / 2 市 / 3 区县；不传 = 全层级）、`parentCode`（可选，级联/按上级取用）、`onlyWithData`（默认 **`false`**，见 §6.2） |
| 返回 | `[{ code, name, shortName, level, parentCode }]`（按 `sort_no, code` 升序；`parentCode` 供前端组装级联树） |
| 授权 | **登录即可**（`SecurityConfig` 默认 `authenticated()`，方法上不加 `@PreAuthorize`） |
| 为什么登录即可（D5） | 地区是**国标公开数据**、最低敏，且被 4 个业务页共用。沿用 `DEC-业务筛选下拉的授权口径` 的教训：**筛选字典的授权必须对齐"能不能看业务数据"，不能因为它在 `/system` 路径下就要求系统配置权限**——否则"能看订单但没有系统配置权限"的角色又会一进页面就吃 403。 |
| 反向保险 | 若日后坚持要权限码，应新增 `system:region:view` 并**同时**把它加入所有业务角色的权限清单（`PermissionCatalog`：OPERATOR / ANALYST / VIEWER），否则就是给上面那个 403 埋雷。 |

### 6.2 选项口径：`onlyWithData`（默认全量）

- **`false`（默认，产品口径）**：返回全部"启用未删除"的地区 —— 省/市/区县都能选，
  即"下拉看到整本字典数据"。前端不再按"有没有业务数据"裁剪。
- `true`（可选）：只返回"启用未删除 **且** 当前有业务数据引用"的地区。
  给分析/对账场景用（例如"当前有单的地区有哪些"），不再作为下拉默认。
  实现是一条 `SELECT DISTINCT region_code` 的**五表 UNION**
  （`sys_org` / `enterprise` / `project` / `tender_order` / `performance_order`）
  + 进程内缓存（TTL 5 分钟，`RegionService.usedRegionCodes()`）；
  ⚠️ 不要改成"每个地区一次 `EXISTS`"：`region_code` 上没有索引，会退化成逐行全表扫 × 地区数。

### 6.3 筛选的层级语义：前缀匹配（D6 的配套改动）

下拉既然能选到市/区县，筛选就不能再用精确相等——否则"选浙江省"会漏掉挂在
`330100`（杭州市）等下级码上的机构/订单。因此筛选统一改成 **层级前缀匹配**：

| 选中 | 前缀 | 匹配范围 |
|---|---|---|
| 浙江省 `330000` | `33` | 该省全部市 + 区县（`330000` / `330100` / `330102` …） |
| 杭州市 `330100` | `3301` | 杭州及其下辖区县 |
| 上城区 `330102` | `330102` | 精确匹配 |

- 实现：`com.guarantee.common.region.RegionCodePrefix`（纯函数，有单测），
  mapper 里用 MyBatis `<bind>` 调用它再 `LIKE CONCAT(#{regionPrefix}, '%')`；
  前端 `frontend/src/utils/region.ts` 是同一规则的复刻（机构树本地过滤用）。
- 覆盖 **7 处筛选点**：订单列表（投标/履约）、订单汇总、项目列表、企业列表、
  区域分析、机构列表。
- **为什么不需要查地区表判断层级**：GB/T 2260 的 6 位码结构固定（省 = 前 2 位 + `0000`，
  市 = 前 4 位 + `00`，区县 = 6 位），因此 `guarantee-order` / `guarantee-analysis`
  不必依赖 `guarantee-system` 的地区表。

### 6.4 校验用：`RegionService.requireEnabledRegion(codeOrName)`

供机构写入路径调用：**省/市/区县三级都接受**，可传码或名称（名称按全称/简称解析）；
未知码、停用、已逻辑删除的地区 → `400`，提示里给出可用地区接口。
历史数据不回溯校验（见 §8）。

---

## 7. 前端改造

### 7.1 统一组件：`frontend/src/components/RegionSelect.vue`

| 项 | 设计 |
|---|---|
| 形态 | **`el-cascader`**（省 → 市 → 区县），`check-strictly`（可只选到省或市）、`filterable`、`clearable`；空值 = **全部地区** |
| 为什么不是普通下拉 | 整本字典 3432 条，平铺既扫不动也没法用；级联把层级关系直接表达出来 |
| 数据 | 挂载时加载一次 `/system/regions/options?onlyWithData=false`（模块级缓存，四个页面共用一个请求结果），前端按 `level` + `parentCode` 组装树 |
| 搜索 | `filter-method` 同时匹配 **名称 / 简称 / 区划码**（用户既能打"萧山"，也能打 `330109`） |
| 值 | `emitPath: false` → `v-model` 仍是**单个区划码**字符串，四个页面的用法与后端参数都不变 |
| 边界 | 加载失败时置空 + 不抛错；叶子节点不留空 `children`（避免出现"可展开"的假象） |

### 7.2 四个页面的替换

| 页面 | 改动 |
|---|---|
| `OrderTable.vue` | 「区域编码」`el-input` → `<RegionSelect v-model="query.regionCode" />`，`placeholder` 删掉"如 330100"这种会误导的示例 |
| `Projects.vue` / `Enterprises.vue` | 同上 |
| `Orgs.vue` | 同上；本地过滤改用 `regionMatches(row.regionCode, regionCode)`（与后端同一前缀规则，否则"选省"在本地会把下级机构过滤掉） |

> 四页统一后，"区域编码"这个词在界面上统一成「**地区**」。

### 7.3 助手写入路径（D6）

- `OrgProposalTool` 的 `regionCode` 参数描述：必须来自地区字典，**省/市/区县都可**（给了三类示例），
  不得编造；
- 服务端 `OrgService.create/update` 调 `RegionService.requireEnabledRegion(...)`；
- 支持"只给名称"：模型传 `浙江省` / `杭州市` 时服务端解析成编码，
  并**用字典全称回填 `regionName`**（原实现是从上级机构继承、兜底"未指定"，已删除）。
- 可选（P1）：给助手加只读工具 `queryRegion(keyword)`，让它在提案前自己查码。

### 7.4 粒度放开后的口径（已实现）

初版把机构区划限定在省级，理由是"订单地区从机构继承、筛选按省级精确匹配"。
本次按要求放开到省/市/区县，并同步做了两件事，使其仍然自洽：

1. **筛选改前缀匹配**（§6.3）——选省能筛到挂在市/区县码上的记录；
2. **字典补全区县**（§5.2）——否则区县码过不了写入校验。

仍然保持的**历史事实**：现有订单的 `region_code` 是省级快照（它们是演示数据，
由机构省份推导），因此"选杭州市"目前筛出的是**真正按市级记录**的订单（通常为 0 条），
这属于数据现状而不是功能缺陷。

---

## 8. 与既有冗余列的关系

**本期不动数据模型**：5 张表继续各自保存 `region_code` + `region_name`（快照式冗余）。
理由：这是**历史快照语义**——订单记录的是"下单时的区划"，而地区主数据将来可能改名/撤并；
改成外键会让历史订单跟着主数据变，反而不对。代价是"名称可能与字典不一致"，用对账兜住。

**对账 SQL（建议随文档一起进"数据质量"小节，实测当前为 0 行）**：

```sql
SELECT 'tender_order' AS src, region_code, COUNT(*) FROM tender_order
 WHERE region_code NOT IN (SELECT code FROM sys_region) GROUP BY region_code
UNION ALL SELECT 'performance_order', region_code, COUNT(*) FROM performance_order
 WHERE region_code NOT IN (SELECT code FROM sys_region) GROUP BY region_code
UNION ALL SELECT 'sys_org', region_code, COUNT(*) FROM sys_org
 WHERE region_code NOT IN (SELECT code FROM sys_region) GROUP BY region_code
UNION ALL SELECT 'enterprise', region_code, COUNT(*) FROM enterprise
 WHERE region_code NOT IN (SELECT code FROM sys_region) GROUP BY region_code
UNION ALL SELECT 'project', region_code, COUNT(*) FROM project
 WHERE region_code NOT IN (SELECT code FROM sys_region) GROUP BY region_code;
```

**不建外键**：5 张表 × 最多 10 万行，加外键会让批量造数与导入变慢，且业务上允许"区划码暂时不在字典里"
（国标更新滞后于业务）；用**写入校验 + 对账**替代约束。

---

## 9. 权限与菜单

| 项 | 本期 |
|---|---|
| 新权限码 | **不新增**（D5） |
| 菜单 / 路由 | **不新增** |
| 助手工具权限 | 复用既有：机构写工具已经要求 `ai:system:write` + `system:org:create/update`，不因地区校验而变化 |
| 若将来做维护页（P2） | 「系统配置 → 地区配置」：`system:region:view`（页面）+ `system:region:disable`（启停）+ `system:region:update`（排序/简称），并进 `PermissionCatalog` 的权限矩阵（ADMIN 全量、OPERATOR 只读、ANALYST/VIEWER 只读） |

---

## 10. 验收标准

| 编号 | 验收项 |
|---|---|
| RG-AC-1 | `sys_region` 表存在，含省 34 / 市 342 / 区县 3056，`code`/`level`/`parent_code`/`status`/`sort_no` 正确 |
| RG-AC-2 | 新库（空库启动）与存量库（重启即执行 seed）**都能**得到同样的表与数据；重复执行幂等 |
| RG-AC-3 | `GET /api/system/regions/options`：任意登录用户可访问；按 `sort_no` 升序；**默认返回整本字典**（3432 项，含 `parentCode`）；`onlyWithData=true` 时只返回有业务数据的地区 |
| RG-AC-4 | 四个页面（订单/项目/企业/机构配置）的「地区」为**省市区级联**，可只选到省/市，也可选到区县 |
| RG-AC-5 | 级联搜索支持名称、简称与区划码（如 `萧山`、`330109`） |
| RG-AC-6 | 清空 = 不带 `regionCode` 参数，结果回到全量 |
| RG-AC-7 | 机构新增/修改：省/市/区县码与名称都能填（名称解析成编码、`regionName` 取字典全称）；字典里不存在的码 → 可读的 400 |
| RG-AC-8 | **层级前缀匹配**：选省能筛出挂在市/区县码上的记录（订单/项目/企业/机构四处都要验） |
| RG-AC-9 | 对账：现有业务数据的区划码全部命中字典，且 `region_name` 与字典全称逐字一致 |
| RG-AC-10 | 机构/险种/订单页原有筛选行为不回归（机构下拉、险种下拉、订单列表与详情） |

---

## 11. 测试要点

| 层 | 用例 |
|---|---|
| 单元测试（`guarantee-common`） | `RegionCodePrefixTest`（4 项）：省→前 2 位 / 市→前 4 位 / 区县原样、包含关系、"选省能覆盖到市与区县"、空值与脏数据退化 |
| 集成测试（`ItMybatisConfig`） | `RegionServiceIntegrationTest`（10 项）：种子数据（省含港澳台、市/区县按上级可取且 `parentCode` 回流）、**与业务数据对账**（订单用到的每个码都在字典里且名称逐字一致）、`onlyWithData` 过滤、缓存 TTL 行为、写入校验（省/市/区县三级 + 名称解析放行；未知码、停用、已删除拒绝） |
| 迁移/幂等 | 种子用 `INSERT IGNORE`：重复执行不报错、不覆盖人工改动、不复活已删除行（启动时每次都跑，等价于每次都验证幂等） |
| 前端 | `vue-tsc --noEmit` 通过；四个页面手工走查（级联逐级展开、跨级搜索、清空=全部、与其它筛选组合） |
| 端到端 | 用真实 jar 起实例，curl 验证：整本字典（3432）、按上级取杭州 13 个区县、`onlyWithData=true`（8）、**前缀匹配**（把一条订单改成 `330100` 后"选浙江省"仍能筛到）、机构写入市/区县码 |

---

## 12. 风险与不做的事

| 风险 | 对策 |
|---|---|
| 地区名与业务数据的 `region_name` 不一致（多字/少字/别名） | 导入前**逐字比对**现有 8 个省的名称（§5.1 注释）；`businessDataMatchesDictionary` 对账断言逐字一致 |
| 把"有业务数据"的判断做成实时 `EXISTS` → 慢查询 | 默认不再用它；`onlyWithData=true` 走缓存（§6.2 写了反面教材） |
| 级联字典 3432 条带来的请求/渲染成本 | 一次性加载 + 模块级缓存（四个页面共用一次请求）；级联只在展开时渲染子级 |
| 筛选改成前缀匹配后**匹配面变大**，误伤别的省 | 前缀按层级截断（省 2 位 / 市 4 位 / 区县 6 位），`RegionCodePrefixTest` 覆盖"杭州的前缀不匹配宁波" |
| 主键选自然码与仓库惯例不一致（D4） | 只影响本表；接口只暴露 `code`，随时可加代理 `id` 而不改契约 |

---

## 13. 工作量与分期

| 期 | 内容 | 状态 |
|---|---|---|
| **P0** | 建表 + 省/市/区县数据 + seed 自动执行 + 受管清单 19 张 + 字典接口 + `RegionSelect` 级联组件 + **四个页面**替换 + 机构写入校验 + **筛选改层级前缀匹配** | ✅ **已完成**（见 §16） |
| **P1** | 助手 `queryRegion` 只读工具（让模型先查码再提案）+ 对账 SQL 进"数据质量"清单 | 待排 |
| **P2**（按需） | 地区维护页（启停/排序/简称）；国标数据更新时的批量校准 | 独立立项 |

---

## 14. 待确认事项（评审用）

1. **D3**：地区表纳入逻辑删除体系（18 → 19 张受管表）是否同意？
2. **D4**：主键用 `code` 还是按仓库惯例用代理 `id` + `uk(code)`？
3. 界面用词统一成「**地区**」是否 OK（现状有"区域编码 / 区划 / 区域"三种叫法）？
4. **订单的地区粒度仍是"下单时的区划快照"**：现有演示订单只到省级，所以"选杭州市"目前筛出 0~少量。
   若希望订单也按市/区县记录，需要上游写单链路配合（本期不动）。
5. **级联控件的交互**：需要"输入即跨级搜索"（现已支持）还是更希望"先选省再看市"的两级联动？当前是前者。

---

## 15. 变更记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.0 | 2026-09-25 | 首版：现状核对（8 个省级码 + 4 处输入口）、表结构草案、字典接口与选项口径、前端组件方案、分期与待拍板 6 项 |
| v1.1 | 2026-09-25 | 按拍板结论定稿并落地 P0：省+市数据、逻辑删除、不加权限码；补充 §16 实施记录 |
| v1.2 | 2026-09-25 | 按要求调整口径：**下拉看到整本字典**（`onlyWithData` 默认 false）、**机构区划省/市/区县都能填**；联动改动：补全区县数据（3432 行）、`RegionSelect` 升级为省市区级联、**7 处筛选改层级前缀匹配**（`RegionCodePrefix` + 单测）、`Orgs.vue` 本地过滤同规则 |
| — | 2026-09-25 | 顺带修复：`Orgs.vue` / `InsuranceTypes.vue` 的删除确认文案仍写着"被订单引用会被拒绝"（上一轮已取消该守卫，属漏改的前端文案），改为"删除不影响历史订单：仍显示名称、仍可照它筛选" |

---

## 16. 实施记录（2026-09-25）

### 16.1 变更清单

| 层 | 文件 | 变更 |
|---|---|---|
| 表结构 | `guarantee-web/.../db/schema.sql` | 新增 `sys_region`（三级结构；主键 `code`；逻辑删除三件套 + `is_deleted` 索引） |
| 种子数据 | `guarantee-web/.../db/seed/region.sql`（新） | **省 34 + 市 342 + 区县 3056 = 3432 行**，`INSERT IGNORE` 幂等；文件头写明数据来源、补零规则、港澳台补录与"改名称要显式 UPDATE" |
| 启动装配 | `guarantee-web/.../application.yml` | 新增 `spring.sql.init.data-locations: classpath:db/seed/region.sql` |
| 逻辑删除 | `guarantee-system/.../mybatis/LogicalDeleteTables.java` | 受管表 18 → **19**（含 `sys_region`），注释说明地区删除不影响历史订单 |
| 后端 | `guarantee-system/.../entity/SysRegion.java`、`vo/RegionOptionVO.java`、`mapper/SysRegionMapper.java` + `.xml`、`service/RegionService.java`、`controller/RegionController.java`（均新增） | 字典查询（level/parentCode/onlyWithData，默认全量）、`usedRegionCodes()` 带 TTL 缓存、`requireEnabledRegion()` 写入校验（省/市/区县） |
| 筛选语义 | `guarantee-common/.../region/RegionCodePrefix.java`（新）+ **7 处 mapper** | 层级前缀匹配：订单列表（投标/履约）、订单汇总、项目、企业、区域分析、机构列表；用 MyBatis `<bind>` 调用纯函数，无需改 DTO |
| 机构写入 | `guarantee-system/.../service/OrgService.java` | `validateCreate` / `validateUpdate` 过字典；`create`/`update` 用字典值规范化 `regionCode`、缺省时用字典全称填 `regionName`；删除已无用的 `resolveRegionName`（原来会兜底成"未指定"） |
| 助手 | `guarantee-ai/.../tool/write/OrgProposalTool.java` | 描述改为"必须来自地区字典，**省/市/区县都可**（给出 330000 / 330100 / 330102 示例），不要编造" |
| 前端 | `frontend/src/types/system.ts`、`api/system.ts` | 新增 `RegionOption`（含 `parentCode`）与 `listRegionOptions()` |
| 前端 | `frontend/src/components/RegionSelect.vue`（新） | 地区**级联**：省→市→区县、可只选父级（`check-strictly`）、跨级搜索（名称/简称/码）、模块级缓存、`emitPath:false` 保持 v-model 是单个区划码 |
| 前端 | `frontend/src/utils/region.ts`（新） | `regionPrefix()` / `regionMatches()`：与后端同一条前缀规则（机构树本地过滤用） |
| 前端 | `OrderTable.vue` / `Projects.vue` / `Enterprises.vue` / `system/Orgs.vue` | 「区域编码」输入框 → `<RegionSelect>`，标签统一为「地区」；`Orgs.vue` 本地过滤改前缀语义 |
| 测试 | `guarantee-common/.../RegionCodePrefixTest.java`（新，4 项） | 前缀规则 + 包含关系 + 边界 |
| 测试 | `guarantee-system/.../RegionServiceIntegrationTest.java`（新，10 项） | 见 §11 |
| 测试 | `LogicalDeleteSqlRewriterTest`、`LogicalDeleteSchemaIntegrationTest`、`LogicalDeleteServiceIntegrationTest`、`LogicalDeleteWebIT` | 受管表 18→19；机构夹具的 `regionCode` 从占位的 `000000` 改为字典里的 `330000`（`000000` 现在会被校验拒绝） |
| 构建 | `guarantee-system/pom.xml`、`src/test/resources/application.yml` | 把 `db/seed/region.sql` 一并复制进测试资源并配置 `data-locations`（测试与生产走同一条种子路径） |

### 16.2 验证证据（v1.2 最终态）

| 验证 | 结果 |
|---|---|
| 全模块单测 `mvn -o -DskipITs test` | **251 项全绿**（common 7 + system 109 + auth 25 + order 2 + analysis 2 + ai 106） |
| 集成测试 `-Dit.test=LogicalDeleteWebIT verify` | **4 项全绿** |
| 前端 `vue-tsc --noEmit` | 通过 |
| 字典接口（真实 jar，`:8087`） | 默认 **3432 项**（level1=34 / level2=342 / level3=3056）；`level=3&parentCode=330100` → 杭州 **13 个区县**（`parentCode` 正确回流）；`onlyWithData=true` 仍返回 **8** 个有数据的省 |
| **前缀匹配**（把一条订单的区划改成 `330100`） | 选浙江省 `330000` → `total=35010`（含该条，改前会漏）；选杭州市 `330100` → `1`（精确匹配时代为 0） |
| 机构写入 | `330100` → 成功，落库 `杭州市`；`330102` → 成功，落库 `上城区`；按浙江省筛机构 → 命中这两个市级/区县级夹具（也是前缀匹配） |
| 库状态 | `sys_region` 34/342/3056；夹具清理干净；机构 21、订单 100000 不变 |

### 16.4 未在浏览器中实测的部分（如实声明）

- **级联控件的交互**只做了静态验证：`vue-tsc --noEmit` 通过、Vite 开发服务器已提供新版
  `RegionSelect.vue`（产物含 `el-cascader` 与 `buildTree`）、后端数据接口用真实 HTTP 验证过。
  **没有**在浏览器里点过"逐级展开 / 跨级搜索 / 清空 / 与其它筛选组合"这些交互路径——
  这几条需要人工走查（`localhost:5273`，后端重启后即可）。
- 机构页的**新增/修改入口在页面上并不存在**（只有助手与 API），因此"机构能填到区县"这条
  是在 **HTTP + 助手工具描述**这一层验证的，没有页面表单可点。

### 16.5 与规划的偏差
1. **没有 `db/migration/V7__region.sql`**：改用 `schema.sql` + `data-locations` 自动覆盖新库与存量库，
   少一个"手工忘了执行"的失败点（理由见 §5.3）。补区县数据时这条设计的收益直接兑现：
   重启一次就补齐 3056 行，无需任何迁移动作。
2. **`onlyWithData` 默认从 true 翻成 false**：产品口径改为"下拉看到整本字典"；
   该参数保留给分析/对账场景，不是死代码。
3. **筛选从精确相等改成层级前缀匹配**：这是"机构能填到区县"的必然配套，
   否则选省会漏掉下级码的数据。规则收敛成一个无依赖的纯函数 + 单测，
   mapper 侧只加一行 `<bind>`。
4. **额外修了一处历史遗留**：`OrgService.resolveRegionName()`（区域名继承上级、兜底"未指定"）
   被字典取值取代并删除。
5. **顺带发现并修复**：`frontend/src/api/system.ts` 在本次改造中被误整体覆盖，
   已按 HEAD + 调用点恢复并补回 7 个未提交的函数（`createUser` / `updateUser` /
   `resetUserPassword` / `assignUserRoles` / `createRole` / `updateRole` / `assignRolePermissions`），
   `vue-tsc` 全量通过即证明 API 面已完整（详见本次会话记录）。
