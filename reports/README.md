# `reports/` 说明（评测产物与归档口径）

> 本目录是**评测运行器的产物**，不是手写文档。文件会被 `scripts/ai-golden-questions.mjs` **重写**，
> 因此同一文件在 git 里反复"变脏"是预期现象（diff 主要是生成时间与逐题耗时）。
> 口径与用法见 `docs/TEST-助手黄金问题集.md`；数字真源见 `scripts/single-source-of-truth.mjs`。

## 命名

```text
eval-<suite>-<日期>.md|json          # 正式报告（suite = deterministic | live | all）
eval-live-gq25-<日期>.md|json        # 单题复跑（--only=GQ-25，关知识层实例）
archive/<名字>[-<原因>].md|json      # 历史快照（不再重写，仅作对照与 --baseline）
```

- **`*-notrun`**：当时缺 `DEEPSEEK_API_KEY`（真机集未跑）的快照，保留作对照。
- **`*-before-fix`**：修复前基线（4 条失败：GQ-10 / GQ-20 / GQ-24 / GQ-29），用于 `--baseline` diff。
- 点前缀的历史文件（如 `.retry-*`）保留原名，属于当时的临时产物，仅作证据。

## 当前状态（2026-09-30）

| 文件 | 内容 |
|---|---|
| `eval-live-2026-09-30.md\|json` | 真机集主报告（8088 临时实例 + 真实 Key）：**通过 32/32**、失败 0、未跑 1（GQ-25 需另起关知识层实例） |
| `eval-live-gq25-2026-09-30.md\|json` | GQ-25 单题复跑（8089，`--guarantee.ai.knowledge.enabled=false`）：**1/1 PASS** |
| `eval-deterministic-2026-09-30.md\|json` | 确定性集（Stub 模型，纳入 `mvn verify`）：**12/12** |
| `archive/eval-live-2026-09-30-notrun.*` | 缺 Key 时的"未跑"快照 |
| `archive/eval-live-2026-09-30-before-fix.*` | 缺陷 8/9/10 修复前的 4 条失败基线 |

> **33/33 全覆盖** = 主报告 32 条 PASS + GQ-25 单独实例 PASS。
> 主报告里 GQ-25 仍标"未跑"是**设计使然**（它必须跑在关掉知识层的实例上），不是失败。

## 归档策略

- 正式报告（`eval-*`）**跟踪入库**，作为可 diff 的基线；
- 被取代的正式报告**移入 `archive/` 并加原因后缀**（不再重写），保持历史可对照；
- 跑评测会重写同名的正式报告——提交时只带"确实想固化为基线"的那一版。
