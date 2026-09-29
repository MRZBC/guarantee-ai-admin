-- =====================================================================
--  观测底座迁移脚本 V9：ai_turn_metric + ai_tool_call.source
--
--  依据：docs/REQ-第五阶段-MCP评测与可观测.md §5.3.1/§5.3.2/§5.3.3、§6.1
--        （REQ-MCP-08/09/10）、AC-MCP-07~10
--
--  背景：
--    轮次 / 调用数 / token / 耗时 / 是否触顶此前**只写结构化日志**（AI_TURN_COST），
--    表里查不到；而 ai_message.token_count 是**字数估算**不是模型用量。
--    本脚本建 ai_turn_metric（一次问答一行，与日志字段一一对应），并给 ai_tool_call
--    加来源列，使"助手调的 / 外部 Agent 调的 / 评测跑的"可区分（AC-MCP-05）。
--
--  口径要点：
--    · 一次问答 = 一行，**含失败与触顶**（outcome = SUCCESS/ERROR/CAPPED）；
--    · input_tokens/output_tokens 是模型**真实 usage**；ai_message.token_count 只作展示，
--      两者并存但口径必须写清（RK-MCP-07）；
--    · trace_id 用来把成本记录、工具调用、审计三者串起来（AC-MCP-10）；
--    · 标签/字段只放可枚举维度，**不放问题正文、不放用户输入、不放参数值**（红线 §2.3-5）。
--
--  ⚠️ 与逻辑删除受管清单的关系（已拍板口径）：
--    ai_turn_metric 是**只追加的指标流水表**，因此**不带**逻辑删除三列
--    （is_deleted/deleted_at/deleted_by），也**不登记**进
--    guarantee-system/src/main/java/com/guarantee/system/mybatis/LogicalDeleteTables.java
--    的 MANAGED —— 与 ai_knowledge_import_log 同策略。
--    （对照：同阶段的 ai_mcp_token 带三列，必须登记 MANAGED，见 V10。）
--
--  ⚠️ 本节新增列 outcome 的说明（对 REQ §5.3.2 的补列，需 Lead 知悉）：
--    AC-MCP-09 要求页面的**失败率**与 ai_turn_metric 聚合结果一致，而 REQ §5.3.2 的列清单
--    没有表达"成功/失败"的列（只有 capped/cap_reason）。这里补 outcome
--    （SUCCESS/ERROR/CAPPED，与 §5.3.1 的 ai.chat.requests 计数器的 outcome 标签同值域），
--    否则失败率只能靠猜。REQ §5.3.2 的其余列**一个不少**。
--
--  ⚠️ 本仓库**没有 Flyway**：db/migration/V1~V9 全部是**手工执行的幂等脚本**，
--     应用启动不会自动跑本目录（应用启动跑的是 schema.sql，由 T5-01/task-13 追加同一份 DDL）。
--     本文件用于**存量库**补齐与变更留档；上线前请手工执行。
--
--  幂等实现说明：
--    · 建表用 CREATE TABLE IF NOT EXISTS（不存在则建，存在则跳过）；
--    · 加列/加索引用 information_schema 判断 + PREPARE/EXECUTE 动态执行
--      （MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS / ADD INDEX IF NOT EXISTS，同 V1~V6）。
--    · 重复执行无副作用；已存在的表不会被改动（列有变化时须另起 V{n} 迁移）。
--
--  执行方式（手工执行）：
--    mysql -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin < V9__ai_observability.sql
--
--  回滚（一般不需要；保留供演练）：
--    ALTER TABLE ai_tool_call DROP INDEX idx_ai_tool_call_source;
--    ALTER TABLE ai_tool_call DROP COLUMN source;
--    DROP TABLE ai_turn_metric;
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1) 单轮指标：一次问答一行（含失败与触顶），只追加
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_turn_metric (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT     NOT NULL COMMENT '会话 id',
    message_id     BIGINT      NULL     COMMENT '对应的助手消息 id（失败/触顶时可能为空）',
    user_id        BIGINT      NOT NULL COMMENT '归属用户（MCP 场景为服务账号）',
    model          VARCHAR(64) NULL     COMMENT '模型名',
    prompt_version VARCHAR(32) NULL     COMMENT '提示词版本（第四阶段提供）',
    rounds         INT         NOT NULL DEFAULT 0 COMMENT '工具轮次',
    tool_calls     INT         NOT NULL DEFAULT 0 COMMENT '工具调用次数',
    tool_cost_ms   BIGINT      NOT NULL DEFAULT 0 COMMENT '工具耗时合计(ms)',
    total_cost_ms  BIGINT      NOT NULL DEFAULT 0 COMMENT '端到端耗时(ms)，与 AI_TURN_COST 日志同源',
    input_tokens   INT         NOT NULL DEFAULT 0 COMMENT '模型输入 token（真实 usage，非字数估算）',
    output_tokens  INT         NOT NULL DEFAULT 0 COMMENT '模型输出 token（真实 usage）',
    capped         TINYINT     NOT NULL DEFAULT 0 COMMENT '是否触顶 0否 1是',
    cap_reason     VARCHAR(32) NULL     COMMENT '触顶原因 SOFT_TIMEOUT/MAX_ROUNDS/MAX_CALLS_PER_ROUND/FRAMEWORK_LIMIT/UNKNOWN',
    source         VARCHAR(8)  NOT NULL DEFAULT 'CHAT' COMMENT '来源 CHAT/MCP/EVAL',
    outcome        VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '结果 SUCCESS/ERROR/CAPPED（失败率口径）',
    trace_id       VARCHAR(64) NULL     COMMENT '与审计、工具调用、日志串联的 traceId',
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_turn_metric_created (created_at),
    KEY idx_ai_turn_metric_user (user_id, created_at),
    KEY idx_ai_turn_metric_model (model, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '单轮 AI 指标（一次问答一行，只追加）';

-- ---------------------------------------------------------------------
-- 2) ai_tool_call.source：区分 助手(CHAT) / 外部 MCP(MCP) / 评测(EVAL)
--    存量数据默认 CHAT：历史记录确实都是助手与页面产生的。
-- ---------------------------------------------------------------------
SET @n_ai_tool_call_source := (SELECT COUNT(*) FROM information_schema.COLUMNS
                                 WHERE TABLE_SCHEMA = DATABASE()
                                   AND TABLE_NAME = 'ai_tool_call'
                                   AND COLUMN_NAME = 'source');
SET @ddl := IF(@n_ai_tool_call_source = 1, 'DO 0',
  'ALTER TABLE ai_tool_call ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT ''CHAT'' COMMENT ''调用来源 CHAT/MCP/EVAL'' AFTER tool_type');
PREPARE stmt_tool_call_source FROM @ddl; EXECUTE stmt_tool_call_source; DEALLOCATE PREPARE stmt_tool_call_source;

-- ---------------------------------------------------------------------
-- 3) source 上的索引：审计按来源过滤（"某个外部 Agent 昨天调了什么"）不能全表扫
-- ---------------------------------------------------------------------
SET @n_idx_ai_tool_call_source := (SELECT COUNT(*) FROM information_schema.STATISTICS
                                     WHERE TABLE_SCHEMA = DATABASE()
                                       AND TABLE_NAME = 'ai_tool_call'
                                       AND INDEX_NAME = 'idx_ai_tool_call_source');
SET @ddl := IF(@n_idx_ai_tool_call_source = 1, 'DO 0',
  'ALTER TABLE ai_tool_call ADD INDEX idx_ai_tool_call_source (source)');
PREPARE stmt_tool_call_source_idx FROM @ddl; EXECUTE stmt_tool_call_source_idx; DEALLOCATE PREPARE stmt_tool_call_source_idx;

-- 复核（三项都应为 1）
SELECT COUNT(*) AS ai_turn_metric_present
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_turn_metric';

SELECT COUNT(*) AS ai_tool_call_source_present
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_call' AND COLUMN_NAME = 'source';

SELECT COUNT(*) AS idx_ai_tool_call_source_present
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_call' AND INDEX_NAME = 'idx_ai_tool_call_source';
