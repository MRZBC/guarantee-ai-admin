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

实测 DRY-RUN 输出（2026-09-30，共享库，**未改库**）：
```
发现 36 个分区名与真实覆盖不符：
  p202709（上界 740255）实际覆盖 202609 → 目标名 p202609
  p202809（上界 740621）实际覆盖 202709 → 目标名 p202709
  p202811（上界 740682）实际覆盖 202711 → 目标名 p202711
  p202812（上界 740713）实际覆盖 202712 → 目标名 p202712
[phase1] ALTER TABLE ai_operation_audit RENAME PARTITION p202709 TO __repart_tmp_21;
[phase2] ALTER TABLE ai_operation_audit RENAME PARTITION __repart_tmp_21 TO p202609;
...（共 36 个分区 → 72 条）
DRY-RUN：以上 72 条 RENAME PARTITION 未执行
```
两条设计取舍：
1. **两阶段临时名**（先全部改成 `__repart_tmp_N`，再改成目标名）：改名是"名额占用"操作，直接改成目标名会撞上"另一个同样待改名的分区"（例如 `p202701 → p202601` 时 `p202601` 还没腾出来）。两阶段法不依赖排序技巧。
2. **名字取"数据主要落在的那个月"**（`DATE_SUB(FROM_DAYS(上界), INTERVAL 1 MONTH)`）：对漂移分区，`p202811` 覆盖的是 2027-11-02~2027-12-01，叫 `p202711` 比叫 `p202712` 更诚实（12 月只占 1 天）。

### 4.1 为什么不直接改共享库的分区名

- 跨任务共享同一开发库（阶段三/四/五都在用），改名会瞬时持有元数据锁；
- 归档脚本 `scripts/archive-operation-audit.ps1` 虽然按**真实上界**判定（改名不影响其正确性），
  但任何"库里名字变了"的中间状态都会让同时在跑的人工巡检/截图对不上；
- 因此**只提供方案，不执行**。真正执行前需 Lead 确认无人正在写 `ai_operation_audit`。

### 4.2 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 改名时的元数据锁 | 毫秒级阻塞（不复制数据，`RENAME PARTITION` 是纯元数据操作） | 业务低峰执行；先跑 DRY-RUN |
| `-AddMonths` 用 `REORGANIZE pmax` | 需重建 `pmax` 的元数据；本表 `pmax` 通常为空，代价极小 | 若 `pmax` 已有数据（说明分区耗尽），先确认再执行 |
| 误删/误改 | 数据丢失 | 脚本**永不 DROP**、**永不触碰 pmax**；只做 RENAME / REORGANIZE |
| 名字与新建库口径仍不完全一致（±1 天） | 语义噪音 | 已在 §2.1 写明；要彻底消除需 §5 重建 |

### 4.3 回滚

改名可逆，回滚就是"再改回去"（脚本日志 `.agent/archive/operation-audit/repartition-operation-audit.log`
里有每一步的 `原名 → 临时名 → 目标名`）：

```sql
-- 回滚模板（示例：把校准后的名字改回原名字；按日志逐条生成）
ALTER TABLE ai_operation_audit RENAME PARTITION p202609 TO p202709;
```
建议回滚前先留一份分区定义：
```bash
mysqldump --no-data --skip-comments -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin \
  ai_operation_audit > ai_operation_audit-ddl.sql
```

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
