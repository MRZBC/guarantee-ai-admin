# 实施记录：审计表分区命名与真实边界差一年（T6-03 / task-25 ①）

> 版本：2026-09-30 ｜ 需求来源：交付遗留项（`ai_operation_audit` 分区命名误导）
> 结论一句话：**分区是 `schema.sql` 的建表语句建的；边界值用的是 Python `date.toordinal()` 那套序数，
> 而分片函数是 MySQL `TO_DAYS()`，两者相差 365 天 → 既有库里每个分区的真实覆盖比名字早一年。**
> 已修 `schema.sql`（只影响**新建库**）+ 提供默认 DRY-RUN 的既有库校准脚本；**共享库现有分区未改名**。

---

## 1. 分区是谁创建的（证据）

| 证据 | 位置 | 内容 |
|---|---|---|
| 建表 + 分区定义 | `guarantee-web/src/main/resources/db/schema.sql:445`（`CREATE TABLE IF NOT EXISTS ai_operation_audit`）、`schema.sql:490`（`PARTITION BY RANGE (TO_DAYS(operated_at))`）、`schema.sql:491-527`（36 个月分区 + `pmax`） | 分区在同一句 DDL 里一次性建好 |
| 谁执行它 | `guarantee-web/src/main/resources/application.yml:31-34`：`spring.sql.init.mode: always`、`schema-locations: classpath:db/schema.sql` | **每次启动**都执行；`CREATE TABLE IF NOT EXISTS` 对已存在的表是空操作，因此实际只在**首次启动**那天建出这张表 |
| 迁移脚本没有参与 | `guarantee-web/src/main/resources/db/migration/*.sql` 全目录 grep `PARTITION` **0 命中** | 排除"迁移脚本建的" |
| 与线上库逐值一致 | `information_schema.PARTITIONS` 的 `PARTITION_DESCRIPTION` 与 `schema.sql` 原文**逐个数字相同**（`p202601=739648 … p202812=740713`） | 证明线上分区就是这份 DDL 建的，不是手工另建 |

**没有任何生成脚本**：这 36 个数字是**硬编码**在 `schema.sql` 里的。

---

## 2. 根因：Python 序数 ≠ MySQL `TO_DAYS()`

| 量 | 值 | 说明 |
|---|---|---|
| `TO_DAYS('2025-02-01')`（MySQL） | **739648** | MySQL 的 `TO_DAYS` 以"第 0 年"起算，现代日期比 Python 序数**小 365** |
| `datetime.date(2026,2,1).toordinal()`（Python） | **739648** | 与上一行**同值** |
| `schema.sql` 里 `p202601` 的边界 | **739648** | 即"按 2026-02-01 算出来的值"，但 MySQL 读作 **2025-02-01** |
| `TO_DAYS('2027-10-01')` / `toordinal(2027-10-01)` | 740620 / **740255**（`p202709` 的值） | 同一个偏差 |

**后果（实测）**：`p202709 VALUES LESS THAN (740255)` → `FROM_DAYS(740255) = 2026-10-01` →
该分区覆盖 **2026-09**，而名字写着 2027-09。线上数据也印证：`p202709` 是唯一有行的分区
（`TABLE_ROWS = 2475`，正是 2026-09 的操作审计）。

### 2.1 附带发现：闰日漂移（±1 天）

偏移是**恒定 365 天**，不是"整年"：凡覆盖区间跨过 2028-02-29 的月份，真实上界会落到**下月 2 日**
（多 1 天）。实测 `p202802 ~ p202812` 共 **11 个**分区如此（`DAY(FROM_DAYS(desc)) = 2`）：

| 分区 | 真实上界 | 实际覆盖 | 主要月份 |
|---|---|---|---|
| `p202709` | 2026-10-01 | 2026-09-01 ~ 2026-09-30 | 2026-09 |
| `p202801` | 2027-02-01 | 2027-01-01 ~ 2027-01-31 | 2027-01 |
| `p202811` | 2027-12-02 | 2027-11-02 ~ 2027-12-01 | 2027-11（29/30 天） |
| `p202812` | 2028-01-02 | 2027-12-02 ~ 2028-01-01 | 2027-12 |

**改名能让名字变真，但改不掉这 1 天**；要精确到日历月只能重建分区（见 §5，代价高，未执行）。

---

## 3. 已做的修复（只影响未来 / 新建库）

`guarantee-web/src/main/resources/db/schema.sql:475-489` 增加了一段"为什么 + 正确写法"的说明，
并把 `schema.sql:490-527` 的 **36 个边界值全部改成由 MySQL `TO_DAYS()` 求出的正确值**
（例：`p202601` 由 `739648` → **`740013`** = `TO_DAYS('2026-02-01')`），并给每个分区标注它**真正覆盖的月份**。

安全性：
- `CREATE TABLE IF NOT EXISTS` 对**已存在的表是空操作** → 共享开发库与其它既有环境**不受影响**；
- 新建库从此"名字 = 真实覆盖"，不再有偏移。

### 3.1 以后新增/重建分区的正确写法（照抄）

```sql
-- 名字写它覆盖的那个月，边界写"下月 1 日"的 TO_DAYS，且必须由 MySQL 求值
ALTER TABLE ai_operation_audit REORGANIZE PARTITION pmax INTO (
    PARTITION p202901 VALUES LESS THAN (TO_DAYS('2029-02-01')),
    PARTITION pmax     VALUES LESS THAN MAXVALUE
);
```
**禁止**在应用/脚本里用别的语言的"天数序数"（Python `toordinal`、Excel 日期序列号等）拼边界值——
这正是本次事故的成因。用 `scripts/repartition-operation-audit.ps1 -AddMonths N` 可以按此口径批量生成。

---

## 4. 既有库怎么办：校准脚本（**默认 DRY-RUN，未执行**）

`scripts/repartition-operation-audit.ps1`（新增）：

| 模式 | 行为 |
|---|---|
| 不带 `-Execute`（默认） | 只打印计划：把每个"名字 ≠ 真实覆盖"的分区改成与真实覆盖一致的名字（如上表 `p202709 → p202609`），并列出 11 个闰日漂移分区 |
| `-Execute` | 真正执行改名 |
| `-AddMonths N` | 按 §3.1 的正确口径追加 N 个未来分区（边界用 `TO_DAYS('<下月1日>')` 表达式） |

实测 DRY-RUN 输出（2026-10-01 定稿版；**2026-09-30 首版用的 `RENAME PARTITION` 是错的，见 §4.4**）：
```
发现 36 个分区名与真实覆盖不符：
  p202709（上界 740255）实际覆盖 202609 → 目标名 p202609
  p202809（上界 740621）实际覆盖 202709 → 目标名 p202709
  p202811（上界 740682）实际覆盖 202711 → 目标名 p202711
  p202812（上界 740713）实际覆盖 202712 → 目标名 p202712
[reorganize-rename] ALTER TABLE ai_operation_audit REORGANIZE PARTITION p202709 INTO (PARTITION p202609 VALUES LESS THAN (740255));
...（共 36 条）
DRY-RUN：以上 36 条 1:1 REORGANIZE 改名未执行
```
两条设计取舍：
1. **1:1 `REORGANIZE` + 按目标名升序逐条处理**：MySQL 8.0 没有 `RENAME PARTITION`（§4.4），
   `REORGANIZE ... INTO (PARTITION 新名 VALUES LESS THAN (原边界数值))` 是唯一可用的改名手段；
   升序可保证"目标名此刻已腾空"（例如 `p202701 → p202601` 之前，`p202601` 已在处理 `p202601 → p202501` 时腾出），
   脚本还会逐条**动态**校验目标名不存在。**边界数值原样保留**（含闰日漂移），只换名字。
2. **名字取"数据主要落在的那个月"**（`DATE_SUB(FROM_DAYS(上界), INTERVAL 1 MONTH)`）：对漂移分区，`p202811` 覆盖的是 2027-11-02~2027-12-01，叫 `p202711` 比叫 `p202712` 更诚实（12 月只占 1 天）。

### 4.1 为什么不直接改共享库的分区名

- 跨任务共享同一开发库（阶段三/四/五都在用），1:1 `REORGANIZE` 会**重建该分区**（行数据复制一次），
  执行期间该分区的写入会等待；
- 归档脚本 `scripts/archive-operation-audit.ps1` 按**真实上界**判定（不依赖名字，改名不影响其正确性），
  但任何"库里名字变了"的中间状态都会让同时在跑的人工巡检/截图对不上；
- 因此**先给方案**（默认 DRY-RUN），执行前需 Lead 确认无人正在写 `ai_operation_audit`。
  **2026-10-01 已获授权并在共享库执行完毕，记录见 §6。**

### 4.2 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 1:1 `REORGANIZE` 重建分区 | 该分区行数据复制一次；执行期间该分区写入等待（本表仅当月分区有数据，~2.6k 行，实测 36 条共 4.4 秒） | 业务低峰执行；先跑 DRY-RUN；执行前备份 DDL |
| `-AddMonths` 用 `REORGANIZE pmax` | 需重建 `pmax`；本表 `pmax` 通常为空，代价极小 | 若 `pmax` 已有数据（说明分区耗尽），先确认再执行 |
| 误删/误改 | 数据丢失 | 脚本**永不 DROP**、**永不触碰 pmax**；只做 REORGANIZE（1:1 改名 / 追加） |
| 名字与新建库口径仍不完全一致（±1 天） | 语义噪音 | 已在 §2.1 写明；要彻底消除需 §5 重建 |

### 4.3 回滚

改名可逆（把真名与旧名对调，同样用 1:1 `REORGANIZE`）：脚本日志
`.agent/archive/operation-audit/repartition-operation-audit.log` 与执行记录 `.agent/t36-repartition-execute-v2.log`
里有每一步的 `旧名 → 新名 + 边界数值`：

```sql
-- 回滚模板（示例：把校准后的名字改回原名字；按日志逐条生成）
ALTER TABLE ai_operation_audit REORGANIZE PARTITION p202609 INTO (PARTITION p202709 VALUES LESS THAN (740255));
```
建议回滚前先留一份分区定义：
```bash
mysqldump --no-data --skip-comments -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin \
  ai_operation_audit > ai_operation_audit-ddl.sql
```

### 4.4 ⚠️ 实测发现：MySQL 8.0 **不支持** `ALTER TABLE ... RENAME PARTITION`

首版脚本（2026-09-30）用的是"两阶段 `RENAME PARTITION`"，2026-10-01 首次 `-Execute` **第一条就报**：

```
ERROR 1064 (42000): You have an error in your SQL syntax; ... near 'PARTITION p202601 TO __repart_tmp_1' at line 1
```

在**自建探针表**上复核（`DROP TABLE IF EXISTS t36_probe; CREATE TABLE ... PARTITION BY RANGE ...`）：

| 写法 | 结果 |
|---|---|
| `ALTER TABLE t36_probe RENAME PARTITION p1 TO p1x;` | ❌ 1064（`near 'PARTITION p1 TO p1x'`） |
| 同上 + 反引号 | ❌ 1064 |
| `ALTER TABLE t36_probe REORGANIZE PARTITION p1 INTO (PARTITION p1x VALUES LESS THAN (TO_DAYS('2020-01-01')));` | ✅ 成功，**数据保留**（探针：p1 2 行 → p1x 2 行，总数 3 行不变） |
| 目标名已存在时 REORGANIZE | ❌ `ERROR 1517 Duplicate partition name p2`（脚本据此做前置校验，给可读错误） |

**结论**：改名只能用 1:1 `REORGANIZE`；它**会重建该分区**（不是纯元数据操作）。
首次失败是"第一条语句即失败"，事务外 DDL 逐条执行 → **库未发生任何变化**（已核对：分区名与行数完全同执行前）。

---

## 5. 可选：彻底重建（精确到日历月，**本次未执行**）

只有重建分区才能消除 §2.1 的 ±1 天。两种做法与代价：

1. **REPARTITION（MySQL 8.0.35+）**：`ALTER TABLE ... PARTITION BY RANGE (TO_DAYS(operated_at)) (...)` 直接按新定义重建，整表锁 + 全量重写 → 大表不可接受。
2. **影子表 + 分批搬迁**：
   ```sql
   CREATE TABLE ai_operation_audit_new LIKE ai_operation_audit;   -- 再改成分区定义正确的版本
   -- 分月 INSERT ... SELECT（按 operated_at 逐月搬，每批一个事务）
   -- 校验行数一致后 RENAME TABLE 切换，再 DROP 旧表
   ```
   代价：需要停机窗口或双写；风险主要是"搬迁期间新写入丢失"。
   **结论：当前收益（1 天边界精度）远低于代价，建议保留现状 + 采用 §4 的改名校准。**

---

## 6. 复核命令（可复制）

```powershell
# 1) 线上分区的真实边界（看"名字 vs 真实覆盖"）
mysql -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin -e "
SELECT PARTITION_NAME, PARTITION_DESCRIPTION,
       FROM_DAYS(PARTITION_DESCRIPTION) AS bound_date,
       DATE_FORMAT(DATE_SUB(FROM_DAYS(PARTITION_DESCRIPTION), INTERVAL 1 MONTH),'%Y%m') AS true_name,
       TABLE_ROWS
FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ai_operation_audit'
ORDER BY PARTITION_ORDINAL_POSITION;"

# 2) 校准方案（DRY-RUN，不改库）
pwsh -File scripts/repartition-operation-audit.ps1 -Password '<pwd>'

# 3) 追加未来分区（DRY-RUN）
pwsh -File scripts/repartition-operation-audit.ps1 -Password '<pwd>' -AddMonths 12

# 4) 运维归档（安全：按真实上界判定；默认 DRY-RUN）
pwsh -File scripts/archive-operation-audit.ps1 -Password '<pwd>'
```

## 7. 变更记录

| 日期 | 内容 |
|---|---|
| 2026-09-30 | 首版（T6-03）：查清分区由 `schema.sql` 建、根因是 Python `toordinal()` 与 MySQL `TO_DAYS()` 相差 365 天；修正 `schema.sql` 的未来分区边界值并写明正确写法；新增默认 DRY-RUN 的 `scripts/repartition-operation-audit.ps1`；**未改动共享库现有分区** |
| 2026-10-01 | 执行记录（T6-06 / task-36 B3）：探针表实测发现 **MySQL 8.0 无 `RENAME PARTITION`**，脚本改为 1:1 `REORGANIZE`（升序 + 保留原边界数值）；**在共享开发库实际完成 36 个分区重命名**，前后边界值/逐分区行数/总行数/pmax 全部核对一致，见 §8 |

---

## 8. 执行记录：既有库分区实际重命名（2026-10-01 02:05–02:07，task-36 B3）

> 执行人：phase5b-mcp（Lead 授权）｜库：`guarantee_ai_admin`@127.0.0.1:3307｜表：`ai_operation_audit`
> **未 DROP 任何分区、未触碰 `pmax`、未改任何业务数据。**

### 8.1 四段证据

**(1) 备份**
```powershell
mysqldump --no-data --skip-add-drop-table --skip-comments --set-gtid-purged=OFF \
  guarantee_ai_admin ai_operation_audit > .agent/audit-table-ddl-before.sql   # 5,503 B
# 另存 SHOW CREATE TABLE → .agent/audit-table-show-create-before.txt          # 4,233 B
```
备份里仍是旧名/旧值：`PARTITION p202601 VALUES LESS THAN (739648)`、`p202812 VALUES LESS THAN (740713)`。

**(2) 执行前快照**（`.agent/t36-partitions-before.tsv`，逐分区精确 `COUNT(*)`）
```
分区数=37（36 + pmax）｜名字与真实覆盖不符=36｜闰日漂移(上界非 1 号)=11｜全表精确行数=2623（独立 COUNT(*) 复核同为 2623）
p202709  desc=740255 真实覆盖 202609  行数=2623   ← 唯一有数据的分区（2026-09 的操作审计）
其余 35 个分区行数均 0；pmax=MAXVALUE 行数 0
```

**(3) DRY-RUN → `-Execute`**
```powershell
pwsh -File scripts/repartition-operation-audit.ps1 -Password ***            # DRY-RUN → 36 条 1:1 REORGANIZE，未执行
pwsh -File scripts/repartition-operation-audit.ps1 -Password *** -Execute   # 36/36 成功，耗时 4.4 秒，0 失败
```
执行语句形如：`REORGANIZE PARTITION p202709 INTO (PARTITION p202609 VALUES LESS THAN (740255));`
（**边界数值原样保留**，只有名字变。）日志：`.agent/t36-repartition-execute-v2.log`。

**(4) 执行后复核**（`.agent/t36-partitions-after.tsv`）
| 核对项 | 执行前 | 执行后 | 结论 |
|---|---|---|---|
| 分区总数 | 37 | 37 | ✅ |
| 名字与真实覆盖不符 | 36 | **0** | ✅ 全部对齐（`p202501(202501)` … `p202712(202712)`） |
| 非 pmax 分区数 | 36 | 36 | ✅ |
| 边界值多重集（`PARTITION_DESCRIPTION`） | 36 个 | 36 个 | ✅ 差异 0（数值一一对应） |
| **逐分区行数多重集** | 36 个 | 36 个 | ✅ 差异 0 |
| **全表行数** | **2623** | **2623** | ✅（独立 `COUNT(*)` 复核） |
| 有数据的分区 | `p202709` = 2623 | **`p202609` = 2623** | ✅ 同一边界 `740255`，只换了名字 |
| `pmax` | `MAXVALUE`，0 行 | `MAXVALUE`，0 行 | ✅ 未动 |

**闰日漂移如实记录**：`p202502`…`p202512`（重命名前为 `p202602`…`p202612`）共 **11 个**分区的真实上界仍是**下月 2 日**
（比日历月多 1 天），脚本在 DRY-RUN/执行时都 WARN。这是历史值的先天偏差，**改名不能消除**；
本表这些分区均为 0 行，实际影响为零，要彻底消除只能按 §5 重建（未做）。

**(5) 归档脚本复核**（名字变了，判定必须仍然正确）
```powershell
pwsh -File scripts/archive-operation-audit.ps1 -Password *** -RetentionMonths 24   # → 没有到期分区（与执行前一致）
# 附加（更强）：13 个月窗口 → 按**真实上界**正确识别出 8 个到期分区
#   p202501…p202508（上界 2025-02-01 … 2025-09-01），导出校验通过、DRY-RUN 未删；演练产生的 8 个空导出文件已清理
```
证明归档判定**不依赖分区名**，重命名后仍然正确。

### 8.2 意外与纠正

- 2026-10-01 02:05:23 首次 `-Execute` **失败**：`ERROR 1064 ... near 'PARTITION p202601 TO __repart_tmp_1'`
  —— MySQL 8.0 **不存在** `RENAME PARTITION`（详见 §4.4）。失败发生在**第一条**语句，
  且 `information_schema` 复核确认**库未发生任何变化**（36 个旧名、2623 行原样）。
- 随后在**自建探针表**上验证 1:1 `REORGANIZE` 可改名且**保数据**，据此把脚本改为
  "升序 + 保留原边界数值 + 动态校验目标名"，重新 DRY-RUN 后才再次执行。

### 8.3 回滚方式

把真名与旧名对调即可（边界数值不变），逐条生成：

```sql
ALTER TABLE ai_operation_audit
  REORGANIZE PARTITION p202501 INTO (PARTITION p202601 VALUES LESS THAN (739648));
-- … 36 条，映射关系见 .agent/t36-partitions-before.tsv / -after.tsv（按 desc 一一对应）
```

留档文件（均在被 gitignore 的 `.agent/` 下）：
`audit-table-ddl-before.sql`、`audit-table-show-create-before.txt`、`t36-partitions-before.tsv`、
`t36-partitions-after.tsv`、`t36-repartition-dryrun-v2.log`、`t36-repartition-execute-v2.log`。
