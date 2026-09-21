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
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_org_code (org_code),
    KEY idx_sys_org_region (region_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '机构配置';

CREATE TABLE IF NOT EXISTS sys_department (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    dept_code  VARCHAR(32) NOT NULL COMMENT '部门编码',
    dept_name  VARCHAR(64) NOT NULL COMMENT '部门名称',
    org_id     BIGINT      NOT NULL COMMENT '所属机构',
    parent_id  BIGINT      NOT NULL DEFAULT 0 COMMENT '上级部门，0为顶级',
    status     TINYINT     NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    sort_no    INT         NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_dept_code (dept_code),
    KEY idx_sys_dept_org (org_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '部门配置';

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL COMMENT '登录账号',
    password      VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码散列',
    real_name     VARCHAR(64)  NOT NULL COMMENT '姓名',
    org_id        BIGINT       NOT NULL COMMENT '所属机构',
    dept_id       BIGINT       NULL COMMENT '所属部门',
    phone         VARCHAR(20)  NULL,
    email         VARCHAR(128) NULL,
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    last_login_at DATETIME     NULL COMMENT '最近登录时间',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_org (org_id),
    KEY idx_sys_user_dept (dept_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户配置';

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    role_code   VARCHAR(32)  NOT NULL COMMENT '角色编码',
    role_name   VARCHAR(64)  NOT NULL COMMENT '角色名称',
    description VARCHAR(255) NULL,
    status      TINYINT      NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (role_code)
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
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_perm_code (perm_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '权限配置';

CREATE TABLE IF NOT EXISTS sys_user_role (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    user_id    BIGINT   NOT NULL,
    role_id    BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_role (user_id, role_id),
    KEY idx_sys_user_role_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户-角色';

CREATE TABLE IF NOT EXISTS sys_role_permission (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    role_id       BIGINT   NOT NULL,
    permission_id BIGINT   NOT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_perm (role_id, permission_id),
    KEY idx_sys_role_perm_perm (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '角色-权限';

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
    PRIMARY KEY (id),
    UNIQUE KEY uk_insurance_type_code (type_code),
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
    PRIMARY KEY (id),
    UNIQUE KEY uk_enterprise_code (ent_code),
    UNIQUE KEY uk_enterprise_credit (credit_code),
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
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_code (project_code),
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
    PRIMARY KEY (id),
    UNIQUE KEY uk_tender_order_no (order_no),
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
    PRIMARY KEY (id),
    UNIQUE KEY uk_perf_order_no (order_no),
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
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED',
    message_count   INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_conv_no (conversation_no),
    KEY idx_ai_conv_user (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 会话';

CREATE TABLE IF NOT EXISTS ai_message (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT      NOT NULL,
    role            VARCHAR(16) NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM/TOOL',
    content         MEDIUMTEXT  NOT NULL,
    token_count     INT         NOT NULL DEFAULT 0,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_msg_conv (conversation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 消息';

CREATE TABLE IF NOT EXISTS ai_tool_call (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT       NOT NULL,
    message_id      BIGINT       NULL COMMENT '关联的助手消息',
    tool_name       VARCHAR(64)  NOT NULL COMMENT 'Tool 名称',
    tool_type       VARCHAR(8)   NOT NULL DEFAULT 'READ' COMMENT 'READ/WRITE',
    arguments       TEXT         NULL COMMENT '入参 JSON',
    result          MEDIUMTEXT   NULL COMMENT '执行结果 JSON',
    status          VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED',
    duration_ms     BIGINT       NOT NULL DEFAULT 0 COMMENT '执行耗时(ms)',
    error_message   VARCHAR(512) NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_tool_conv (conversation_id, id),
    KEY idx_ai_tool_name (tool_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI Tool Call 记录';

CREATE TABLE IF NOT EXISTS ai_audit_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT       NULL,
    user_id         BIGINT       NULL,
    action          VARCHAR(32)  NOT NULL COMMENT 'CHAT/TOOL_CALL/ERROR',
    detail          TEXT         NULL,
    trace_id        VARCHAR(64)  NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_audit_conv (conversation_id),
    KEY idx_ai_audit_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 审计日志';
