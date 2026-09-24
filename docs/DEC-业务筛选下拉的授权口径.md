# DEC-业务筛选下拉的授权口径（机构 / 险种）

> 状态：**已落地**（代码 + 单测 + 真实 HTTP 走查）
> 触发场景：行政部「张涛」（`user0005`，自定义角色 `OPER_NO_SYS` =「业务运营（无系统配置）」）
> 一进投标订单页就报 `code=403「你当前没有该操作的权限，请联系管理员」`
> 相关：**列出什么**（选项口径）见 `docs/DEC-订单筛选下拉的选项口径.md` —— 两份文档分别回答
> "谁能用这个下拉"与"下拉里应该出现哪些项"，改一处不要以为另一处也跟着变了

---

## 一、现象与直接原因

现场请求（`GET /api/system/orgs/options`，带张涛的 JWT）：

```json
{"code":403,"message":"你当前没有该操作的权限，请联系管理员","success":false}
```

**"投标订单报错"其实不是订单接口的错误**，而是订单页筛选区两个下拉框的数据源：

| 前端调用点 | 接口 | 原授权 |
|---|---|---|
| `frontend/src/views/orders/OrderTable.vue`（`onMounted` → `loadOptions()`） | `GET /api/system/orgs/options` | `system:org:view` |
| 同上 | `GET /api/system/insurance-types/options` | `system:insurance:view` |

张涛的角色只持有 7 项业务权限（`ai:chat`、`dashboard:view`、`order:tender:view`、
`order:performance:view`、`analysis:overview:view`、`project:view`、`enterprise:view`），
一个 `system:*` 都没有 → 两个下拉双双 403。

`OrderTable.vue` 的 `try/catch` 只把下拉置空，**拦不住报错提示**：
`frontend/src/api/request.ts` 的响应拦截器对非 0 业务码统一 `ElMessage.error`，
所以用户看到的是"页面进来了、列表有数据、却连弹两次没有权限"。

---

## 二、根因

**下拉框是订单筛选的一部分，它的授权却按"接口所在的模块"挂，而不是按"谁来用"挂。**

- `system:org:view` / `system:insurance:view` 在 `PermissionCatalog` 里的语义是
  **「机构配置」/「险种配置」页面的入口权限**；
- 但这两个 `/options` 接口的真正消费方是**订单页的筛选区**（全站唯一调用点，见
  `frontend/src/api/system.ts` 的 `listOrgOptions()` / `listInsuranceTypeOptions()`）；
- 于是"能看订单"和"能进配置页"这两个互相独立的授权域被绑死：只要角色是
  "业务运营但无系统配置"（这正是产品上要支持的角色形态），订单页的筛选就不可用。

顺带说明：订单列表本身是好的——`/api/orders/tender` 当时正常返回数据，
`order:tender:view` 也确实在下发（见 JWT）。

---

## 三、决策

**两个只读字典接口的授权口径 = 「配置页只读」**或**「能看到订单（投标 / 履约）」**，
二者任一即放行。

落地为 `Permissions` 里两条共享表达式（`guarantee-common`）：

```java
Permissions.ORG_OPTIONS_READ        // hasAnyAuthority(system:org:view, order:tender:view, order:performance:view)
Permissions.INSURANCE_OPTIONS_READ  // hasAnyAuthority(system:insurance:view, order:tender:view, order:performance:view)
```

| 接口 | 方法 | 授权 |
|---|---|---|
| `GET /api/system/orgs/options` | `OrgController#options` | `Permissions.ORG_OPTIONS_READ` |
| `GET /api/system/insurance-types/options` | `InsuranceTypeController#options` | `Permissions.INSURANCE_OPTIONS_READ` |
| `GET /api/system/orgs`（列表）、`/tree`、`/{id}` | 同上 Controller | **不变**，仍是 `system:org:view` |
| `GET /api/system/insurance-types`、`/{id}` | 同上 Controller | **不变**，仍是 `system:insurance:view` |

### 为什么收口成共享表达式，而不是在各 Controller 内联

"机构放了、险种忘了"是这类双接口改动的典型漏改（与 `PermissionCatalog` 收口角色矩阵、
`LogicalDeletePermissions` 收口参数级校验同一个理由）。两条表达式放在权限码旁边，
两个 Controller 引用同一份定义，新增消费方（例如将来项目页也要机构筛选）只改一处。

### 刻意没做的三件事

1. **不放宽成"登录即可"**：机构/险种清单虽然是低敏字典，但无任何业务权限的账号
   （例如只有 `ai:chat`）也不该读配置域字典。授权清单与业务可见性对齐即可，
   没有必要把边界抹平。
2. **不给「业务运营（无系统配置）」补 `system:*:view`**：那会让一个名字就叫
   "无系统配置"的角色获得「机构配置」「险种配置」两个页面入口，与角色语义直接冲突，
   而且该角色若多人共用就是集体放权。**改接口口径，不改角色授权。**
3. **不把订单筛选改成"没权限就隐藏下拉"**：下拉是订单页的正常功能，
   藏掉等于功能缺失；正确做法是让它对"能看订单的人"可用。

---

## 四、变更清单

| 文件 | 变更 |
|---|---|
| `guarantee-common/.../security/Permissions.java` | 新增 `ORG_OPTIONS_READ` / `INSURANCE_OPTIONS_READ`，并说明它们**不是** `sys_permission` 权限码、不参与角色分配 |
| `guarantee-system/.../controller/OrgController.java` | `/options` 改用共享表达式 + 注释说明为何刻意宽于本页其它方法 |
| `guarantee-system/.../controller/InsuranceTypeController.java` | 同上 |
| `guarantee-system/src/test/.../controller/OrderFilterDictionaryPermissionTest.java` | 新增：SpEL 行为断言（订单权限放行 / 配置权限放行 / 无关权限拒绝）+ 反射接线断言（接口引用共享表达式、配置读路径未被放宽） |

未改前端：`OrderTable.vue` 的两个请求无需改动，授权修好后自然可用。

---

## 五、验证记录

### 1. 单测

`mvn -o -pl guarantee-common,guarantee-system test` → **Tests run: 81, Failures: 0, Errors: 0**
（含新增的 5 项 `OrderFilterDictionaryPermissionTest`）

### 2. 真实 HTTP 走查

用**构建后的 jar**（`guarantee-web/target/guarantee-ai-admin.jar`）另起一个实例
（`:8087`，避开正在运行的开发实例 `:8081`），拿现场的张涛 JWT 原样重放：

| 请求 | 结果 |
|---|---|
| `GET /api/system/orgs/options` | `code=0`，**21 项**机构（修复前 403） |
| `GET /api/system/insurance-types/options` | `code=0`，**5 项**险种（修复前 403） |
| `GET /api/orders/tender?pageNum=1&pageSize=1` | `code=0`（未受影响） |
| `GET /api/system/orgs?pageNum=1&pageSize=1` | `code=403`（**配置页读路径未被顺手放宽**，符合预期） |
| `GET /api/system/orgs/tree` | `code=403`（同上） |

回归对照：`operator`（持有 `system:org:view`）→ `/options` 与 `/system/orgs` 均 `code=0`，
配置页用户没有被误伤。

> 走查用的是独立端口的实例，因此**浏览器里要看效果需要重启开发后端**（当前 `:8081`
> 仍是旧代码）。前端 `:5273` 的代理指向 `:8081`，无需改动。

---

## 六、未决项（本次刻意不动，需另行拍板）

1. **`/api/orders/**` 至今没有 `@PreAuthorize`**：`order:tender:view` /
   `order:performance:view` 目前只在前端菜单与路由生效，直接调订单接口只要有登录态就能拿数据
   （`SecurityConfig` 只要求 `authenticated()`）。本决策把下拉对齐到"能看订单"，
   在订单接口补上方法级鉴权之后才真正自洽——**建议后续补**，但那是一次影响面更大的收紧，
   不混在本修复里做。
2. **`/api/system/insurance-types/options` 的返回体超出前端契约**：前端只用
   `id / typeCode / typeName / category / categoryName`（`InsuranceTypeOption`），
   后端却返回了 `InsuranceTypeVO` 全量字段（`baseRate`、`minAmount`、`maxAmount`、
   `description`、`createdAt/updatedAt`、逻辑删除标记）。当前授权口径下不构成新的暴露面
   （能看订单的人本来就看得到订单的费率），但若将来把这个下拉放开给更多消费方，
   应先把返回体收窄成真正的"选项"。
3. **前端对字典接口失败的处理**：`request.ts` 对任何非 0 业务码都会弹提示，
   组件内的 `try/catch` 无法静默降级。若希望"兜底失败时不打扰用户"，
   需要给请求层加 `silent` 选项——属于体验改进，与本次授权口径无关。
