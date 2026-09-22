# 一次性迁移辅助脚本（可评审后删除）

这些 `.mjs` 脚本是**逻辑删除（`is_deleted`）落地过程中使用的一次性辅助工具**，
用于批量改写 mapper XML、DTO、前端页面与测试文件。

## 为什么被移到这里

它们原本散落在 `scripts/` 根目录，与 `reset-demo-data.ps1`、`start-local-env.ps1` 这类
**长期维护的项目工具**混在一起，容易让人误以为需要维护。实际上它们是"改完即弃"的：

| 脚本 | 用途 |
| --- | --- |
| `add-ld-fields.mjs` | 给 16 个实体加 `isDeleted` / `deletedAt` / `deletedBy` 字段 |
| `add-ld-dto-flag.mjs` | 给 5 个 Query DTO 加 `includeDeleted` |
| `add-ld-select-cols.mjs` | 批量给 mapper XML 的 SELECT 列清单加三列 |
| `patch-ld-*.mjs` | 分批改写 Mapper / Service / Controller / 前端 |
| `fix-ld-*.mjs` | 修复实施过程中暴露的批量改写遗漏 |
| `frontend-hide-deleted-at.mjs` | 前端删除时间展示的两次调整（**结论已作废**，见下） |
| `update-progress-frontend-decision.mjs` | 更新进度文档 |
| `gen-logical-delete-sql.mjs` | 由模板生成 `schema.sql` 与 V1/V2 迁移脚本 |

## ⚠️ 两个已知作废的脚本

| 脚本 | 作废原因 |
| --- | --- |
| `frontend-hide-deleted-at.mjs` | 它实现的是"前端不展示 `deleted_at` / `deleted_by`"。该结论**已被推翻**：`deleted_by` 是评审明确要求保留的字段（`VARCHAR(64) DEFAULT 'DB'`），前端应展示它（`utils/status.ts#formatDeletedBy`）。此脚本**不要再执行** |
| `gen-logical-delete-sql.mjs` | 它生成的 V2 是"裸 `(业务键, deleted_at)`"形态，已被 `V3__logical_delete_functional_unique_keys.sql` 取代（函数索引）。脚本本身**未同步更新**，再执行会退回错误形态。保留仅为追溯生成逻辑 |

## 保留决策

建议**评审后整体删除本目录**。若需要保留追溯价值，可只保留本 README。

真正需要长期维护的 SQL 是：

- `guarantee-web/src/main/resources/db/schema.sql`（空库自举，**权威定义**）
- `guarantee-web/src/main/resources/db/migration/V1__logical_delete.sql`（加列）
- `guarantee-web/src/main/resources/db/migration/V2__logical_delete_unique_keys.sql`（唯一键，被 V3 修正）
- `guarantee-web/src/main/resources/db/migration/V3__logical_delete_functional_unique_keys.sql`（**函数索引，最终形态**）
