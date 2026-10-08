-- =====================================================================
--  智能电子保函运营管理平台 (guarantee-ai-admin)
--  MySQL 8.0 schema
--
--  This file is idempotent: the application runs it on every startup
--  (spring.sql.init.mode=always) so a fresh database self-provisions.
--
--  Create the schema first:
--    CREATE DATABASE guarantee_ai_admin
--      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
-- =====================================================================

-- ---------------------------------------------------------------------
-- 系统配置域
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sys_org (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    org_code    VARCHAR(32)  NOT NULL COMMENT '机构编码',
    org_name    VARCHAR(64)  NOT NULL COMMENT '机构名称',
    region_code VARCHAR(12)  NOT NULL COMMENT '行政区划编码',
    region_name VARCHAR(32)  NOT NULL COMMENT '行政区划名称',
    org_level   TINYINT      NOT NULL DEFAULT 1 COMMENT '层级 1总部 2省级 3市级',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '上级机构ID，0为顶级',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    sort_no     INT          NOT NULL DEFAULT 0 COMMENT '排序号',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_sys_org_deleted (is_deleted),
    UNIQUE KEY uk_sys_org_code (org_code, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_sys_org_region (region_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '机构配置';

CREATE TABLE IF NOT EXISTS sys_department (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    dept_code  VARCHAR(32) NOT NULL COMMENT '部门编码',
    dept_name  VARCHAR(64) NOT NULL COMMENT '部门名称',
    parent_id  BIGINT      NOT NULL DEFAULT 0 COMMENT '上级部门，0为顶级',
    status     TINYINT     NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    sort_no    INT         NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_sys_department_deleted (is_deleted),
    UNIQUE KEY uk_sys_dept_code (dept_code, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '部门配置';

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL COMMENT '登录账号',
    password      VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码散列',
    real_name     VARCHAR(64)  NOT NULL COMMENT '姓名',
    dept_id       BIGINT       NOT NULL COMMENT '所属部门',
    phone         VARCHAR(20)  NULL,
    email         VARCHAR(128) NULL,
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    -- account_type 的 DDL 必须与 db/migration/V10__ai_mcp.sql 的 ADD COLUMN 逐字一致：
    -- 存量库靠 V10 补列，空库靠本文件自建（见 docs/DEC-逻辑删除设计方案.md §10.1 的幂等边界）。
    -- 该列曾**只**加在 V10：空库没有这一列，guarantee-system 的账号类型集成测试
    -- 在 CI 首跑 6/6 全错（BadSqlGrammar: Unknown column 'account_type'，2026-10-08）。
    account_type  VARCHAR(16)  NOT NULL DEFAULT 'HUMAN' COMMENT '账号类型 HUMAN/SERVICE（SERVICE 不参与登录）',
    must_change_password TINYINT NOT NULL DEFAULT 0 COMMENT '首次登录强制改密 1是 0否',
    last_login_at DATETIME     NULL COMMENT '最近登录时间',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_sys_user_deleted (is_deleted),
    UNIQUE KEY uk_sys_user_username (username, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_sys_user_dept (dept_id),
    -- 与 V10 同步：服务账号查询走这个索引（SERVICE 不参与登录）
    KEY idx_sys_user_account_type (account_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户配置';

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    role_code   VARCHAR(32)  NOT NULL COMMENT '角色编码',
    role_name   VARCHAR(64)  NOT NULL COMMENT '角色名称',
    description VARCHAR(255) NULL,
    status      TINYINT      NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_sys_role_deleted (is_deleted),
    UNIQUE KEY uk_sys_role_code (role_code, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '角色配置';

CREATE TABLE IF NOT EXISTS sys_permission (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    perm_code  VARCHAR(64) NOT NULL COMMENT '权限编码',
    perm_name  VARCHAR(64) NOT NULL COMMENT '权限名称',
    perm_type  VARCHAR(16) NOT NULL COMMENT 'MENU/BUTTON/API',
    parent_id  BIGINT      NOT NULL DEFAULT 0,
    path       VARCHAR(128) NULL COMMENT '前端路由或接口路径',
    component  VARCHAR(128) NULL COMMENT '前端组件',
    icon       VARCHAR(64)  NULL,
    sort_no    INT         NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_sys_permission_deleted (is_deleted),
    UNIQUE KEY uk_sys_perm_code (perm_code, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '权限配置';

CREATE TABLE IF NOT EXISTS sys_user_role (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    user_id    BIGINT   NOT NULL,
    role_id    BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_sys_user_role_deleted (is_deleted),
    UNIQUE KEY uk_sys_user_role (user_id, role_id),
    KEY idx_sys_user_role_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户-角色';

CREATE TABLE IF NOT EXISTS sys_role_permission (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    role_id       BIGINT   NOT NULL,
    permission_id BIGINT   NOT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_sys_role_permission_deleted (is_deleted),
    UNIQUE KEY uk_sys_role_perm (role_id, permission_id),
    KEY idx_sys_role_perm_perm (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '角色-权限';

-- =====================================================================
--  行政区划基础信息（地区字典）
--
--  依据 docs/REQ-地区基础信息与区域筛选下拉.md：把"区域编码"从自由文本输入改成地区下拉，
--  并让机构写入按字典校验。表按省/市/区县三级设计（level 1/2/3），
--  本期只导入省（34）+ 市（342），区县留待业务数据细化到区县时纯数据补齐。
--
--  主键用**区划码 code** 而不是代理 id：业务数据（订单/机构/企业/项目）一直用
--  region_code 字符串引用它，再引入代理 id 只会多一个需要对照的标识。国标码稳定，
--  适合做自然主键。若将来要改成代理 id + uk(code)，只影响本表，接口只暴露 code。
--
--  种子数据见 db/seed/region.sql（Spring Boot 启动时按 data-locations 执行，INSERT IGNORE 幂等）。
-- =====================================================================
CREATE TABLE IF NOT EXISTS sys_region (
    code        VARCHAR(12) NOT NULL                COMMENT '行政区划代码（GB/T 2260），省级 6 位',
    name        VARCHAR(64) NOT NULL                COMMENT '名称，例如 浙江省（全称，与业务数据的 region_name 逐字一致）',
    short_name  VARCHAR(32) NULL                    COMMENT '简称，用于下拉搜索，例如 浙江',
    level       TINYINT     NOT NULL                COMMENT '层级 1省 2市 3区县',
    parent_code VARCHAR(12) NOT NULL DEFAULT ''     COMMENT '上级区划代码；省级为空串',
    status      TINYINT     NOT NULL DEFAULT 1      COMMENT '状态 1启用 0停用',
    sort_no     INT         NOT NULL DEFAULT 0      COMMENT '排序号（国标顺序）',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL   COMMENT '删除时间（微秒精度）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB'   COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (code),
    KEY idx_sys_region_parent (parent_code, level),
    KEY idx_sys_region_deleted (is_deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '行政区划基础信息';

CREATE TABLE IF NOT EXISTS insurance_type (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    type_code   VARCHAR(32)   NOT NULL COMMENT '险种编码',
    type_name   VARCHAR(64)   NOT NULL COMMENT '险种名称',
    category    VARCHAR(32)   NOT NULL COMMENT 'TENDER 投标 / PERFORMANCE 履约 / OTHER',
    base_rate   DECIMAL(10,6) NOT NULL COMMENT '基准费率',
    min_amount  DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '最小保额',
    max_amount  DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '最大保额',
    status      TINYINT       NOT NULL DEFAULT 1,
    description VARCHAR(255)  NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_insurance_type_deleted (is_deleted),
    UNIQUE KEY uk_insurance_type_code (type_code, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_insurance_type_category (category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '险种配置';

-- ---------------------------------------------------------------------
-- 业务分析域（企业 / 项目）
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS enterprise (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    ent_code      VARCHAR(32)  NOT NULL COMMENT '企业编码',
    ent_name      VARCHAR(128) NOT NULL COMMENT '企业名称',
    credit_code   VARCHAR(32)  NOT NULL COMMENT '统一社会信用代码',
    region_code   VARCHAR(12)  NOT NULL,
    region_name   VARCHAR(32)  NOT NULL,
    industry      VARCHAR(32)  NOT NULL COMMENT '行业',
    ent_level     VARCHAR(8)   NOT NULL COMMENT 'AAA/AA/A/BBB',
    contact_name  VARCHAR(32)  NULL,
    contact_phone VARCHAR(20)  NULL,
    status        TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_enterprise_deleted (is_deleted),
    UNIQUE KEY uk_enterprise_code (ent_code, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    UNIQUE KEY uk_enterprise_credit (credit_code, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_enterprise_region (region_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '企业';

CREATE TABLE IF NOT EXISTS project (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    project_code  VARCHAR(32)   NOT NULL COMMENT '项目编码',
    project_name  VARCHAR(160)  NOT NULL COMMENT '项目名称',
    enterprise_id BIGINT        NOT NULL COMMENT '业主企业',
    region_code   VARCHAR(12)   NOT NULL,
    region_name   VARCHAR(32)   NOT NULL,
    project_amount DECIMAL(18,2) NOT NULL COMMENT '项目金额',
    project_type  VARCHAR(32)   NOT NULL COMMENT '房建/市政/交通/水利/其他',
    status        VARCHAR(16)   NOT NULL COMMENT 'BIDDING/AWARDED/BUILDING/FINISHED',
    tender_date   DATE          NOT NULL COMMENT '招标日期',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_project_deleted (is_deleted),
    UNIQUE KEY uk_project_code (project_code, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_project_enterprise (enterprise_id),
    KEY idx_project_region (region_code),
    KEY idx_project_tender_date (tender_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '项目';

-- ---------------------------------------------------------------------
-- 订单域
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tender_order (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    order_no          VARCHAR(40)   NOT NULL COMMENT '订单号',
    project_id        BIGINT        NOT NULL,
    enterprise_id     BIGINT        NOT NULL,
    insurance_type_id BIGINT        NOT NULL,
    org_id            BIGINT        NOT NULL COMMENT '承保机构',
    region_code       VARCHAR(12)   NOT NULL,
    region_name       VARCHAR(32)   NOT NULL,
    guarantee_amount  DECIMAL(18,2) NOT NULL COMMENT '保函金额',
    premium_amount    DECIMAL(18,2) NOT NULL COMMENT '保费',
    premium_rate      DECIMAL(10,6) NOT NULL COMMENT '费率',
    status            VARCHAR(16)   NOT NULL COMMENT 'DRAFT/UNDER_REVIEW/EFFECTIVE/EXPIRED/RELEASED',
    apply_date        DATE          NOT NULL COMMENT '申请日期（分析主时间维度）',
    effective_date    DATE          NULL,
    expire_date       DATE          NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_tender_order_deleted (is_deleted),
    UNIQUE KEY uk_tender_order_no (order_no, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_tender_apply_date (apply_date),
    KEY idx_tender_region (region_code),
    KEY idx_tender_org (org_id),
    KEY idx_tender_insurance (insurance_type_id),
    KEY idx_tender_project (project_id),
    KEY idx_tender_enterprise (enterprise_id),
    KEY idx_tender_org_date (org_id, apply_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '投标订单';

CREATE TABLE IF NOT EXISTS performance_order (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    order_no          VARCHAR(40)   NOT NULL COMMENT '订单号',
    contract_no       VARCHAR(40)   NOT NULL COMMENT '合同编号',
    project_id        BIGINT        NOT NULL,
    enterprise_id     BIGINT        NOT NULL,
    insurance_type_id BIGINT        NOT NULL,
    org_id            BIGINT        NOT NULL COMMENT '承保机构',
    region_code       VARCHAR(12)   NOT NULL,
    region_name       VARCHAR(32)   NOT NULL,
    guarantee_amount  DECIMAL(18,2) NOT NULL COMMENT '保函金额',
    premium_amount    DECIMAL(18,2) NOT NULL COMMENT '保费',
    premium_rate      DECIMAL(10,6) NOT NULL COMMENT '费率',
    status            VARCHAR(16)   NOT NULL COMMENT 'DRAFT/UNDER_REVIEW/EFFECTIVE/EXPIRED/RELEASED',
    apply_date        DATE          NOT NULL COMMENT '申请日期（分析主时间维度）',
    effective_date    DATE          NULL,
    expire_date       DATE          NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_performance_order_deleted (is_deleted),
    UNIQUE KEY uk_perf_order_no (order_no, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_perf_apply_date (apply_date),
    KEY idx_perf_region (region_code),
    KEY idx_perf_org (org_id),
    KEY idx_perf_insurance (insurance_type_id),
    KEY idx_perf_project (project_id),
    KEY idx_perf_enterprise (enterprise_id),
    KEY idx_perf_org_date (org_id, apply_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '履约订单';

-- ---------------------------------------------------------------------
-- AI 域
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ai_conversation (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_no VARCHAR(40)  NOT NULL COMMENT '会话编号',
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(128) NOT NULL COMMENT '会话标题',
    model           VARCHAR(64)  NOT NULL COMMENT '模型名',
    prompt_version  INT          NULL COMMENT '本轮回答所用的提示词版本号（ai_prompt_version.version_no；无版本机制时为 NULL）',
    config_version  BIGINT       NULL COMMENT '本轮生效的 AI 配置快照版本（ai_config_item.version 最大值；未配置时为 NULL/0）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED',
    message_count   INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_ai_conversation_deleted (is_deleted),
    UNIQUE KEY uk_ai_conv_no (conversation_no, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_ai_conv_user (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 会话';

CREATE TABLE IF NOT EXISTS ai_message (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT      NOT NULL,
    role            VARCHAR(16) NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM/TOOL',
    content         MEDIUMTEXT  NOT NULL,
    token_count     INT         NOT NULL DEFAULT 0 COMMENT '字数估算，非模型用量；真实 usage 见 ai_turn_metric',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_ai_message_deleted (is_deleted),
    KEY idx_ai_msg_conv (conversation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 消息';

CREATE TABLE IF NOT EXISTS ai_tool_call (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT       NULL COMMENT '关联会话；MCP/评测调用没有会话时为 NULL（V10）',
    message_id      BIGINT       NULL COMMENT '关联的助手消息',
    tool_name       VARCHAR(64)  NOT NULL COMMENT 'Tool 名称',
    tool_type       VARCHAR(8)   NOT NULL DEFAULT 'READ' COMMENT 'READ/WRITE',
    source          VARCHAR(8)   NOT NULL DEFAULT 'CHAT' COMMENT '调用来源 CHAT/MCP/EVAL（T5-01）',
    arguments       TEXT         NULL COMMENT '入参 JSON',
    result          MEDIUMTEXT   NULL COMMENT '执行结果 JSON',
    status          VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED',
    duration_ms     BIGINT       NOT NULL DEFAULT 0 COMMENT '执行耗时(ms)',
    error_message   VARCHAR(512) NULL,
    trace_id        VARCHAR(64)  NULL COMMENT '本次调用的 traceId（与审计、成本日志同源，T5-01）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_ai_tool_call_deleted (is_deleted),
    KEY idx_ai_tool_conv (conversation_id, id),
    KEY idx_ai_tool_name (tool_name),
    KEY idx_ai_tool_call_source (source)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI Tool Call 记录';

CREATE TABLE IF NOT EXISTS ai_audit_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT       NULL,
    user_id         BIGINT       NULL,
    action          VARCHAR(32)  NOT NULL COMMENT 'CHAT/TOOL_CALL/ERROR/PROPOSAL_CREATED/PROPOSAL_CONFIRMED/PROPOSAL_REJECTED/PROPOSAL_EXPIRED/OPERATION_EXECUTED/OPERATION_FAILED',
    detail          TEXT         NULL,
    trace_id        VARCHAR(64)  NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_ai_audit_log_deleted (is_deleted),
    KEY idx_ai_audit_conv (conversation_id),
    KEY idx_ai_audit_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 审计日志';

-- ---------------------------------------------------------------------
-- 二期：变更提案（写工具只产出提案，执行走独立的确认接口）
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ai_operation_proposal (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    proposal_no     VARCHAR(40)  NOT NULL COMMENT '提案编号 OP+时间+随机',
    conversation_id BIGINT       NULL COMMENT '来源会话',
    user_id         BIGINT       NOT NULL COMMENT '提案发起人',
    tool_name       VARCHAR(64)  NOT NULL,
    action          VARCHAR(32)  NOT NULL COMMENT 'CREATE/UPDATE/ENABLE/DISABLE/ASSIGN_ROLES/ASSIGN_PERMISSIONS',
    target_type     VARCHAR(32)  NOT NULL COMMENT 'USER/ORG/DEPT/ROLE/INSURANCE_TYPE',
    target_id       BIGINT       NULL COMMENT '新建时为空',
    target_name     VARCHAR(128) NULL COMMENT '目标展示名，便于确认卡标题',
    request_payload TEXT         NULL COMMENT '模型解析后的参数（已脱敏）',
    preview_payload TEXT         NULL COMMENT 'changes[] + impact + warnings[]',
    target_fingerprint VARCHAR(64) NULL COMMENT '目标版本指纹，执行前比对防并发修改（T-09）',
    required_perms  VARCHAR(512) NOT NULL COMMENT '所需权限码，逗号分隔，确认时复核用',
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/EXECUTING/EXECUTED/REJECTED/EXPIRED/INVALIDATED/FAILED',
    reject_reason   VARCHAR(255) NULL,
    result_message  VARCHAR(512) NULL,
    confirmed_at    DATETIME     NULL,
    executed_at     DATETIME     NULL,
    expires_at      DATETIME     NOT NULL,
    audit_id        BIGINT       NULL COMMENT '关联 ai_operation_audit.id',
    trace_id        VARCHAR(64)  NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_ai_operation_proposal_deleted (is_deleted),
    UNIQUE KEY uk_proposal_no (proposal_no, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_user_status (user_id, status),
    KEY idx_conversation (conversation_id),
    KEY idx_expires (status, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 变更提案';

-- ---------------------------------------------------------------------
-- 二期：操作审计（助手确认 + 页面直连统一追溯）
--
-- 保留期（D-5 / SYS-A-14）：按月 RANGE 分区，在线 24 个月 + 归档 36 个月，
-- 到期分区以 DROP PARTITION 清理（元数据操作、无长事务）。
--
-- 分区键约束：MySQL 要求分区列必须包含在**每一个**唯一键中。本表无业务唯一键，
-- 因此主键改为复合主键 (id, operated_at)——否则建表脚本会直接失败（5.7.1）。
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ai_operation_audit (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    operated_at        DATETIME     NOT NULL COMMENT '操作时间（分区键）',
    operator_user_id   BIGINT       NULL,
    operator_username  VARCHAR(64)  NULL,
    operator_real_name VARCHAR(64)  NULL,
    source             VARCHAR(16)  NOT NULL COMMENT 'AI（助手确认）/ WEB（页面直连）',
    action             VARCHAR(32)  NOT NULL,
    target_type        VARCHAR(32)  NOT NULL,
    target_id          BIGINT       NULL,
    target_name        VARCHAR(128) NULL,
    before_value       TEXT         NULL COMMENT '变更前结构化快照（敏感字段已脱敏）',
    after_value        TEXT         NULL COMMENT '变更后结构化快照（敏感字段已脱敏）',
    changed_fields     VARCHAR(512) NULL,
    truncated          TINYINT      NOT NULL DEFAULT 0 COMMENT '快照超 8KB 被截断',
    result             VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED/REJECTED/EXPIRED/PARTIAL',
    error_message      VARCHAR(512) NULL,
    proposal_id        BIGINT       NULL,
    conversation_id    BIGINT       NULL,
    trace_id           VARCHAR(64)  NULL,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id, operated_at),
    KEY idx_ai_operation_audit_deleted (is_deleted),
    KEY idx_operated_at (operated_at),
    KEY idx_operator (operator_user_id, operated_at),
    KEY idx_target (target_type, target_id),
    KEY idx_result (result)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '操作审计'
--   ⚠️ 分区边界值必须由 **MySQL 自己** 算（TO_DAYS），不要用其它语言的"天数序数"。
--   历史缺陷（2026-09-30 修复，T6-03）：本文件原来的 36 个边界值用的是 **Python `date.toordinal()`
--   那一套序数**（例：739648 = toordinal(2026-02-01)），而 MySQL 的 TO_DAYS() 与它相差 **365 天**
--   （TO_DAYS('2025-02-01') = 739648）。后果：每个分区的**真实覆盖比名字早一年**——
--   名为 p202709 的分区实际覆盖 2026-09（实测：当月数据正落在该分区，2475 行）。
--   该缺陷只误导"名字"，不影响写入与归档：scripts/archive-operation-audit.ps1 一直按
--   FROM_DAYS(PARTITION_DESCRIPTION) 的**真实上界**判定（见该脚本 §1 注释），数据是安全的。
--
--   正确写法（新增/重建分区时照抄，`TO_DAYS` 必须由 MySQL 求值）：
--     PARTITION p<YYYY><MM> VALUES LESS THAN (TO_DAYS(DATE_ADD('<YYYY>-<MM>-01', INTERVAL 1 MONTH)))
--   例：2029-01 分区 = PARTITION p202901 VALUES LESS THAN (TO_DAYS('2029-02-01'))
--   口径：**名字写它覆盖的那个月，边界写"下月 1 日"的 TO_DAYS**。
--
--   既有共享库的分区不在这里改名（CREATE TABLE IF NOT EXISTS 对已存在的表是空操作）：
--   要校准既有库的分区名，用 scripts/repartition-operation-audit.ps1（默认 DRY-RUN，不执行）。
PARTITION BY RANGE (TO_DAYS(operated_at)) (
    PARTITION p202601 VALUES LESS THAN (740013),  -- 2026-01
    PARTITION p202602 VALUES LESS THAN (740041),  -- 2026-02
    PARTITION p202603 VALUES LESS THAN (740072),  -- 2026-03
    PARTITION p202604 VALUES LESS THAN (740102),  -- 2026-04
    PARTITION p202605 VALUES LESS THAN (740133),  -- 2026-05
    PARTITION p202606 VALUES LESS THAN (740163),  -- 2026-06
    PARTITION p202607 VALUES LESS THAN (740194),  -- 2026-07
    PARTITION p202608 VALUES LESS THAN (740225),  -- 2026-08
    PARTITION p202609 VALUES LESS THAN (740255),  -- 2026-09
    PARTITION p202610 VALUES LESS THAN (740286),  -- 2026-10
    PARTITION p202611 VALUES LESS THAN (740316),  -- 2026-11
    PARTITION p202612 VALUES LESS THAN (740347),  -- 2026-12
    PARTITION p202701 VALUES LESS THAN (740378),  -- 2027-01
    PARTITION p202702 VALUES LESS THAN (740406),  -- 2027-02
    PARTITION p202703 VALUES LESS THAN (740437),  -- 2027-03
    PARTITION p202704 VALUES LESS THAN (740467),  -- 2027-04
    PARTITION p202705 VALUES LESS THAN (740498),  -- 2027-05
    PARTITION p202706 VALUES LESS THAN (740528),  -- 2027-06
    PARTITION p202707 VALUES LESS THAN (740559),  -- 2027-07
    PARTITION p202708 VALUES LESS THAN (740590),  -- 2027-08
    PARTITION p202709 VALUES LESS THAN (740620),  -- 2027-09
    PARTITION p202710 VALUES LESS THAN (740651),  -- 2027-10
    PARTITION p202711 VALUES LESS THAN (740681),  -- 2027-11
    PARTITION p202712 VALUES LESS THAN (740712),  -- 2027-12
    PARTITION p202801 VALUES LESS THAN (740743),  -- 2028-01
    PARTITION p202802 VALUES LESS THAN (740772),  -- 2028-02
    PARTITION p202803 VALUES LESS THAN (740803),  -- 2028-03
    PARTITION p202804 VALUES LESS THAN (740833),  -- 2028-04
    PARTITION p202805 VALUES LESS THAN (740864),  -- 2028-05
    PARTITION p202806 VALUES LESS THAN (740894),  -- 2028-06
    PARTITION p202807 VALUES LESS THAN (740925),  -- 2028-07
    PARTITION p202808 VALUES LESS THAN (740956),  -- 2028-08
    PARTITION p202809 VALUES LESS THAN (740986),  -- 2028-09
    PARTITION p202810 VALUES LESS THAN (741017),  -- 2028-10
    PARTITION p202811 VALUES LESS THAN (741047),  -- 2028-11
    PARTITION p202812 VALUES LESS THAN (741078),  -- 2028-12
    PARTITION pmax VALUES LESS THAN MAXVALUE
);

-- 存量库兜底：若 ai_operation_audit 已以非分区形态存在（历史环境），
-- 上面的 CREATE TABLE IF NOT EXISTS 不会生效。这里显式提示需要人工按 D-5 重建，
-- 避免"以为已分区、实际永久在线"的静默退化（RK-14）。
-- 巡检 SQL（可放入巡检作业）：
--   SELECT PARTITION_NAME FROM information_schema.PARTITIONS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_audit';

-- ---------------------------------------------------------------------
-- 二期：提案敏感参数的一次性加密暂存（D-4 与"提案可执行"之间的取舍）
--
-- 背景：D-4 / SYS-A-09 要求 ai_operation_proposal.request_payload 与
-- ai_operation_audit 中不得出现敏感字段明文；但"把手机号改成 X"这类提案
-- 必须在确认时知道 X 才能执行，否则功能不成立。
--
-- 解法：敏感值不写进提案表，而是单独加密成密文存本表，只在**确认执行的那一刻**
-- 解密一次用于执行；执行完成或提案过期即删除。AES-GCM 密文即使被 SQL 直查也读不出明文，
-- 因此"审计/提案表不得出现明文"与功能可用性同时成立。
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ai_operation_secret (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    proposal_id BIGINT   NOT NULL COMMENT '关联提案',
    cipher_text TEXT     NOT NULL COMMENT 'AES-256-GCM 密文（Base64），密钥由 guarantee.auth.jwt.secret 派生',
    key_version INT      NOT NULL DEFAULT 1 COMMENT '密钥版本，便于轮换',
    expires_at  DATETIME NOT NULL COMMENT '与提案同生命周期',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_secret_proposal (proposal_id),
    KEY idx_ai_secret_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '提案敏感参数加密暂存';

-- ---------------------------------------------------------------------
-- 三期：业务知识底座（RAG → 业务知识，REQ-RAG-01 / 02）
--
-- 真源在仓库：guarantee-ai/src/main/resources/knowledge/<domain>/<no>-<slug>.md
-- （YAML front-matter，可评审、可 diff、可回滚）；本表是它在运行期的**投影**，
-- 供检索、按权限裁剪与审计使用。启动时由 KnowledgeImporter 幂等导入：
--   内容未变 → 不动；内容变化 → version+1 并写 ai_knowledge_import_log；
--   真源文件消失 → 置 RETIRED（**不物理删除**），保留可追溯性。
--
-- 为什么没有向量列：本机 .m2 无任何 vector-store 构件（RK-RAG-01），
-- 第一版按"结构化条目 + 关键词/标签"检索；向量化留接口不落库（Q-RAG-01 已拍板 B）。
--
-- status 只有 PUBLISHED 参与检索；permission_code 为空表示登录即可见
-- （裁剪发生在 Service 层，不在提示词层）。
-- 唯一键沿用逻辑删除的函数索引写法（见 V3 说明）：未删除行只有一个
-- deleted_at=NULL → IFNULL 后是同一个哨兵值，因此同一编号只能有一条有效行。
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ai_knowledge_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    knowledge_no    VARCHAR(32)  NOT NULL COMMENT '稳定编号 KB-<DOMAIN>-NNNN（人工固定，全生命周期不变）',
    domain          VARCHAR(16)  NOT NULL COMMENT 'ORDER/SYSTEM/CONCEPT/POLICY',
    title           VARCHAR(120) NOT NULL COMMENT '条目标题（中文，≤60 字；溯源行逐字使用）',
    content         TEXT         NOT NULL COMMENT '条目正文（Markdown 纯文本，≤2KB）',
    keywords        VARCHAR(512) NOT NULL DEFAULT '' COMMENT '检索标签，逗号分隔（5~15 个，主力命中面）',
    effective_from  DATE         NULL COMMENT '生效起始日（NULL=不限）',
    effective_to    DATE         NULL COMMENT '生效截止日（NULL=长期有效）',
    version         INT          NOT NULL DEFAULT 1 COMMENT '内容版本号：内容变化即 +1，编号不变',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT/PUBLISHED/RETIRED',
    permission_code VARCHAR(64)  NULL COMMENT '可见所需权限码；NULL/空=登录即可见',
    source_ref      VARCHAR(255) NULL COMMENT '来源说明（原文出处/对应代码类/文档章节）',
    is_deleted      TINYINT      NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at      DATETIME(6)  NULL     COMMENT '删除时间（微秒精度，唯一键分量）；禁止默认值',
    deleted_by      VARCHAR(64)  NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ai_knowledge_item_deleted (is_deleted),
    UNIQUE KEY uk_ai_knowledge_no (knowledge_no, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_ai_knowledge_lookup (domain, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '业务知识条目（Markdown 真源的运行期投影）';

CREATE TABLE IF NOT EXISTS ai_knowledge_import_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    knowledge_no VARCHAR(32)  NOT NULL COMMENT '条目编号',
    old_version  INT          NULL COMMENT '变更前版本（首次导入为 NULL）',
    new_version  INT          NOT NULL COMMENT '变更后版本',
    content_hash CHAR(64)     NOT NULL COMMENT '变更后内容的 SHA-256（规范化后）',
    action       VARCHAR(16)  NOT NULL COMMENT 'CREATED/UPDATED/RESTORED/RETIRED',
    source_file  VARCHAR(255) NULL COMMENT '真源文件（classpath 相对路径）；RETIRED 时记录最后已知路径',
    imported_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '导入时间',
    PRIMARY KEY (id),
    KEY idx_ai_knowledge_log_no (knowledge_no, imported_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '知识真源导入留痕（只追加，不修改）';

-- ---------------------------------------------------------------------
-- 四期：AI 配置底座（T4-00 / REQ-CFG-01、REQ-CFG-02）
--
-- 与 db/migration/V8__ai_config.sql **逐字同源**（V8 供存量库手工执行，这里供新库自举）。
-- 两处内容必须一致：改一处就要改另一处，否则新库/存量库会长出两套结构。
--
--   ai_config_item     配置项的"当前值"。元数据（类型/默认值/范围/危险标记）以
--                      AiConfigCatalog 为唯一真源，行上的 *_value/type/category 列是
--                      写库时生成的**投影**，只为运维直接读表时看得懂，应用读回时不依赖。
--                      version = 配置版本号，快照版本取未删除行的最大值（"版本变化→重载"的判据）。
--   ai_prompt_version  提示词版本（DRAFT → PUBLISHED → ARCHIVED）；"同一时刻只有一个 PUBLISHED"
--                      由服务层保证（MySQL 无部分唯一索引）。已发布版本只读。
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ai_config_item (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    config_key    VARCHAR(64)   NOT NULL COMMENT '配置键（唯一，点号分层，如 budget.max-rounds）',
    config_value  TEXT          NULL     COMMENT '当前值；NULL 表示无显式值（回落 AiConfigCatalog 默认值）',
    value_type    VARCHAR(16)   NOT NULL DEFAULT 'STRING' COMMENT '值类型投影 STRING/INT/DECIMAL/BOOLEAN/ENUM',
    default_value TEXT          NULL     COMMENT '默认值投影（NULL 表示未设置、沿用框架默认）',
    min_value     DECIMAL(20,6) NULL     COMMENT '允许范围下界投影（含）',
    max_value     DECIMAL(20,6) NULL     COMMENT '允许范围上界投影（含）',
    enum_options  VARCHAR(512)  NULL     COMMENT 'ENUM 可选值投影（逗号分隔）',
    category      VARCHAR(16)   NOT NULL DEFAULT 'MODEL' COMMENT '分类投影 MODEL/SWITCH/BUDGET/PROMPT',
    dangerous     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否危险配置 1是 0否（页面二次确认）',
    description   VARCHAR(255)  NULL     COMMENT '影响面说明投影',
    version       BIGINT        NOT NULL DEFAULT 0 COMMENT '配置版本号；快照版本取最大值',
    updated_by    VARCHAR(64)   NULL     COMMENT '最后修改人：应用写 sys_user.id',
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_ai_config_item_deleted (is_deleted),
    KEY idx_ai_config_category (category),
    UNIQUE KEY uk_ai_config_item_key (config_key, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 配置项当前值';

CREATE TABLE IF NOT EXISTS ai_prompt_version (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    version_no   INT          NOT NULL COMMENT '版本号（递增、唯一）',
    content      LONGTEXT     NOT NULL COMMENT '提示词正文（可能超 8KB，审计侧按既有截断规则处理）',
    content_hash VARCHAR(64)  NOT NULL COMMENT '正文 SHA-256 十六进制，用于审计与 diff 判定',
    status       VARCHAR(16)  NOT NULL COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
    note         VARCHAR(512) NULL     COMMENT '版本说明（谁、为什么改）',
    created_by   VARCHAR(64)  NULL     COMMENT '创建人',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by VARCHAR(64)  NULL     COMMENT '发布人',
    published_at DATETIME     NULL     COMMENT '发布时间',
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_ai_prompt_version_deleted (is_deleted),
    KEY idx_ai_prompt_version_status (status),
    UNIQUE KEY uk_ai_prompt_version_no (version_no, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 提示词版本';

-- ---------------------------------------------------------------------
-- 五期：单轮 AI 指标（一次问答一行，只追加）
--
-- 与 db/migration/V9__ai_observability.sql 保持**同一份定义**（存量库走 V9，全新库走本文件）。
--   · 只追加的流水表：**不带**逻辑删除三列，也不进 LogicalDeleteTables.MANAGED
--     （与 ai_knowledge_import_log 同策略）；
--   · input_tokens/output_tokens 是模型**真实 usage**；ai_message.token_count 只是字数估算；
--   · outcome 是对 REQ §5.3.2 的补列（SUCCESS/ERROR/CAPPED），用于算失败率（AC-MCP-09）；
--   · 只放可枚举维度与数值，不放问题正文 / 用户输入 / 参数值（红线 §2.3-5）。
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
-- 五期：MCP 机器凭据（与 db/migration/V10__ai_mcp.sql 保持同一份定义）
--
-- 明文（mcp_<Base64URL(32B)>）**只在签发时返回一次**；库里只有 SHA-256 哈希与展示前缀。
-- 失效两套并存：revoked_at（显式撤销，即时生效）与逻辑删除三列（行级下架）。
-- ⚠️ 本表带逻辑删除三列，必须登记进 LogicalDeleteTables.MANAGED（已登记）。
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
    KEY idx_ai_mcp_token_deleted (is_deleted),
    UNIQUE KEY uk_ai_mcp_token_hash (token_hash, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_ai_mcp_token_account (service_account_id, revoked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'MCP 机器凭据（明文不入库）';


