-- =====================================================================
--  业务 MCP 迁移脚本 V10：ai_mcp_token + sys_user.account_type
--
--  依据：docs/REQ-第五阶段-MCP评测与可观测.md §5.1.2/§5.1.5、§6.1
--        （REQ-MCP-02/05）、AC-MCP-02/03/06
--
--  背景：
--    业务 MCP 需要**机器身份**：服务账号 + 可撤销的 MCP Token，最小权限只读。
--    现状没有任何客户端凭据机制，拿令牌只能用"用户名 + 密码"登录，而新账号首登会被
--    "强制改密闸门"拦住 —— 机器不该走人类用户那条路。
--
--  交付两块：
--    1) ai_mcp_token —— 机器凭据（明文永不入库：只有 SHA-256 哈希 + 展示前缀）；
--    2) sys_user.account_type —— 区分 HUMAN / SERVICE（服务账号不参与登录、
--       不计入人类用户统计、不适用首登改密）。
--
--  ⚠️⚠️ 执行顺序硬约束（阶段三踩过一次红灯）：
--    本脚本建的 ai_mcp_token **带**逻辑删除三列，因此**必须同步登记**进
--    guarantee-system/src/main/java/com/guarantee/system/mybatis/LogicalDeleteTables.java
--    的 MANAGED（由 T5-03/task-15 完成），否则
--    LogicalDeleteSchemaIntegrationTest 的
--    "带 is_deleted 的表数 == MANAGED.size()" 与 "idx_%_deleted 索引数 == MANAGED.size()"
--    两条断言会同时失败。
--    **正确顺序：先改 MANAGED（task-15 的一次显式修改），再执行本脚本。**
--    （对照：ai_turn_metric 是只追加流水表，不带三列、不进 MANAGED，见 V9。）
--
--  ⚠️ 本仓库没有 Flyway：db/migration/V*.sql 都是**手工执行的幂等脚本**，
--     应用启动跑的是 schema.sql（由 task-15 追加同一份 DDL）。上线前请手工执行。
--
--  幂等实现说明：
--    · 建表用 CREATE TABLE IF NOT EXISTS；
--    · 加列/索引/约束用 information_schema 判断 + PREPARE/EXECUTE（MySQL 8.0 不支持
--      ADD COLUMN IF NOT EXISTS，同 V1~V6）。
--
--  执行方式（手工执行，**先确认 MANAGED 已登记**）：
--    mysql -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin < V10__ai_mcp.sql
--
--  回滚（一般不需要；保留供演练）：
--    ALTER TABLE sys_user DROP COLUMN account_type;
--    DROP TABLE ai_mcp_token;
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1) 机器凭据：ai_mcp_token
--
--    明文（mcp_<Base64URL(32B)>）只在签发时返回一次；库里只有：
--      · token_hash   —— SHA-256 十六进制（**不用 BCrypt**：鉴权要按哈希反查，
--                        而 Token 本身有 256 位熵，暴力枚举不可行；
--                        密码才必须用慢哈希 + 随机盐）；
--      · token_prefix —— 明文前 10 个字符（mcp_ + 6），仅供展示与人工核对。
--
--    失效两套并存：revoked_at（显式撤销，即时生效）与逻辑删除三列（行级下架）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_mcp_token (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    service_account_id BIGINT       NOT NULL COMMENT '服务账号 sys_user.id（account_type=SERVICE）',
    token_prefix       VARCHAR(16)  NOT NULL COMMENT '明文前缀（mcp_ + 6 字符），仅展示用，不是凭据',
    token_hash         VARCHAR(64)  NOT NULL COMMENT 'SHA-256(明文) 小写十六进制；明文永不入库',
    permissions        VARCHAR(512) NOT NULL COMMENT '权限范围，逗号分隔（建议含 ai:mcp:read）',
    expires_at         DATETIME     NULL     COMMENT '有效期；NULL=长期有效（仍可随时撤销）',
    last_used_at       DATETIME     NULL     COMMENT '最后成功使用时间（不延长有效期）',
    revoked_at         DATETIME(6)  NULL     COMMENT '撤销时间（微秒）；非空即不可用',
    revoked_by         VARCHAR(64)  NULL     COMMENT '撤销人：应用写 sys_user.id',
    created_by         VARCHAR(64)  NULL     COMMENT '签发人：应用写 sys_user.id',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted         TINYINT      NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at         DATETIME(6)  NULL     COMMENT '删除时间（微秒精度，唯一键分量）；禁止默认值',
    deleted_by         VARCHAR(64)  NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    -- 房规：每张受管表都要有名为 idx_<table>_deleted 的 is_deleted 索引
    KEY idx_ai_mcp_token_deleted (is_deleted),
    -- 鉴权入口：按哈希反查；哈希本身唯一，且必须沿用逻辑删除的函数索引形态
    UNIQUE KEY uk_ai_mcp_token_hash (token_hash, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    -- 服务账号停用/删除时批量撤销其全部凭据
    KEY idx_ai_mcp_token_account (service_account_id, revoked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'MCP 机器凭据（明文不入库）';

-- ---------------------------------------------------------------------
-- 2) 服务账号：sys_user.account_type
--
--    HUMAN（默认）= 人类用户，照旧参与登录与首登强制改密；
--    SERVICE      = 机器账号，**不参与登录**（登录闸门会按类型拒绝）、
--                   不计入人类用户统计、不适用首登改密。
--
--    存量数据全部取 HUMAN：既有账号不受影响（与 V6 的"默认不强制"同款取舍）。
-- ---------------------------------------------------------------------
SET @n_sys_user_account_type := (SELECT COUNT(*) FROM information_schema.COLUMNS
                                   WHERE TABLE_SCHEMA = DATABASE()
                                     AND TABLE_NAME = 'sys_user'
                                     AND COLUMN_NAME = 'account_type');
SET @ddl := IF(@n_sys_user_account_type = 1, 'DO 0',
  'ALTER TABLE sys_user ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT ''HUMAN'' COMMENT ''账号类型 HUMAN/SERVICE（SERVICE 不参与登录）''');
PREPARE stmt_account_type FROM @ddl; EXECUTE stmt_account_type; DEALLOCATE PREPARE stmt_account_type;

SET @n_idx_sys_user_account_type := (SELECT COUNT(*) FROM information_schema.STATISTICS
                                       WHERE TABLE_SCHEMA = DATABASE()
                                         AND TABLE_NAME = 'sys_user'
                                         AND INDEX_NAME = 'idx_sys_user_account_type');
SET @ddl := IF(@n_idx_sys_user_account_type = 1, 'DO 0',
  'ALTER TABLE sys_user ADD INDEX idx_sys_user_account_type (account_type)');
PREPARE stmt_account_type_idx FROM @ddl; EXECUTE stmt_account_type_idx; DEALLOCATE PREPARE stmt_account_type_idx;

-- ---------------------------------------------------------------------
-- 3) ai_tool_call.conversation_id 放开为可空（**已拍板：方案 A**，Lead 2026-09-30 批准）
--
--    事实：ai_tool_call.conversation_id 目前是 BIGINT **NOT NULL**，而 MCP 调用没有会话
--    （AiToolCallRecorder 从 ToolContext 取到的 conversationId 为 null）→ MCP 行的工具记录
--    会因 NOT NULL 约束落不了库（Recorder 吞异常并打 ERROR 日志），
--    于是 AC-MCP-05"每次 MCP 调用在 ai_tool_call 中可识别来源"无法达成。
--
--    为什么选 A（放开可空），而不是 B / C：
--      · A：最贴合事实——MCP 调用**真的**没有会话；约束应表达真实可空性；
--      · B（固定哨兵会话）：会**污染用户可见的会话列表**——一次外部机器调用就凭空多出
--        一个"会话"，而且此后所有按 conversation 聚合/展示的功能都要额外加白名单；
--      · C（另建 ai_mcp_call_log）：与 AC-MCP-05 明确要求的"在 ai_tool_call 中可识别"矛盾，
--        且是同一件事的重复建模（工具调用记录只能有一处真源）。
--
--    只读复核结论（2026-09-30，未执行本脚本时完成）：
--      · AiToolCall.conversationId / ToolCallVO.conversationId / MyToolCallItem.conversationId
--        都是 `Long`（可空），全链路不存在拆箱 NPE；
--      · GET /api/ai/conversations/tool-calls/{conversationId} 用路径变量（必填），不受 NULL 影响；
--      · ⚠️ queryMyToolCalls 的数据源（AiToolCallMapper.selectMine / countMine）是
--        `INNER JOIN ai_conversation c ON c.id = t.conversation_id`：
--        conversation_id 为 NULL 的 MCP 行会**被静默排除**（不报错、也查不到）。
--        task-15 必须显式决定它的可见性（推荐：MCP 行不进"某人的自查列表"，
--        改由审计/运维视图查看——ai_tool_call 本身没有 user_id 列，跨会话归属只能来自
--        同一次调用写入的 ai_audit_log 行；若确需服务账号自查，需再加 caller_id 归因列）。
--
--    MCP 行必须写全（AC-MCP-05 靠这两项识别与追溯）：
--      · `source = 'MCP'`；
--      · `trace_id` 由**同一次调用**写入的 `ai_audit_log` 行承载
--        （AiToolCallRecorder 同时写 ai_tool_call 与 ai_audit_log，后者带 trace_id 与
--         user_id = 服务账号 id）；若要直接从 ai_tool_call 追溯，需另加 trace_id 列（未批准）。
-- ---------------------------------------------------------------------
SET @n_ai_tool_call_conv_nullable := (SELECT COUNT(*) FROM information_schema.COLUMNS
                                        WHERE TABLE_SCHEMA = DATABASE()
                                          AND TABLE_NAME = 'ai_tool_call'
                                          AND COLUMN_NAME = 'conversation_id'
                                          AND IS_NULLABLE = 'YES');
SET @ddl := IF(@n_ai_tool_call_conv_nullable = 1, 'DO 0',
  'ALTER TABLE ai_tool_call MODIFY COLUMN conversation_id BIGINT NULL COMMENT ''关联会话；MCP/评测调用没有会话时为 NULL''');
PREPARE stmt_tool_call_conv FROM @ddl; EXECUTE stmt_tool_call_conv; DEALLOCATE PREPARE stmt_tool_call_conv;
-- ---------------------------------------------------------------------

-- 复核（三项都应为 1；account_type 列存在、索引存在）
SELECT COUNT(*) AS ai_mcp_token_present
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_mcp_token';

SELECT COUNT(*) AS sys_user_account_type_present
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'account_type';

SELECT COUNT(*) AS idx_ai_mcp_token_deleted_present
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_mcp_token' AND INDEX_NAME = 'idx_ai_mcp_token_deleted';

SELECT COUNT(*) AS ai_tool_call_conversation_id_nullable
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_call'
   AND COLUMN_NAME = 'conversation_id' AND IS_NULLABLE = 'YES';
