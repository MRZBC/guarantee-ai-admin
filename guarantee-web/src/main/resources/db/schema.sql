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
    org_id     BIGINT      NOT NULL COMMENT '所属机构',
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
    UNIQUE KEY uk_sys_dept_code (dept_code, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
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
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_sys_user_deleted (is_deleted),
    UNIQUE KEY uk_sys_user_username (username, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
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
    token_count     INT         NOT NULL DEFAULT 0,
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
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_ai_tool_call_deleted (is_deleted),
    KEY idx_ai_tool_conv (conversation_id, id),
    KEY idx_ai_tool_name (tool_name)
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
    operator_org_id    BIGINT       NULL COMMENT '操作人机构，供数据范围过滤',
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
PARTITION BY RANGE (TO_DAYS(operated_at)) (
    PARTITION p202601 VALUES LESS THAN (739648),
    PARTITION p202602 VALUES LESS THAN (739676),
    PARTITION p202603 VALUES LESS THAN (739707),
    PARTITION p202604 VALUES LESS THAN (739737),
    PARTITION p202605 VALUES LESS THAN (739768),
    PARTITION p202606 VALUES LESS THAN (739798),
    PARTITION p202607 VALUES LESS THAN (739829),
    PARTITION p202608 VALUES LESS THAN (739860),
    PARTITION p202609 VALUES LESS THAN (739890),
    PARTITION p202610 VALUES LESS THAN (739921),
    PARTITION p202611 VALUES LESS THAN (739951),
    PARTITION p202612 VALUES LESS THAN (739982),
    PARTITION p202701 VALUES LESS THAN (740013),
    PARTITION p202702 VALUES LESS THAN (740041),
    PARTITION p202703 VALUES LESS THAN (740072),
    PARTITION p202704 VALUES LESS THAN (740102),
    PARTITION p202705 VALUES LESS THAN (740133),
    PARTITION p202706 VALUES LESS THAN (740163),
    PARTITION p202707 VALUES LESS THAN (740194),
    PARTITION p202708 VALUES LESS THAN (740225),
    PARTITION p202709 VALUES LESS THAN (740255),
    PARTITION p202710 VALUES LESS THAN (740286),
    PARTITION p202711 VALUES LESS THAN (740316),
    PARTITION p202712 VALUES LESS THAN (740347),
    PARTITION p202801 VALUES LESS THAN (740378),
    PARTITION p202802 VALUES LESS THAN (740407),
    PARTITION p202803 VALUES LESS THAN (740438),
    PARTITION p202804 VALUES LESS THAN (740468),
    PARTITION p202805 VALUES LESS THAN (740499),
    PARTITION p202806 VALUES LESS THAN (740529),
    PARTITION p202807 VALUES LESS THAN (740560),
    PARTITION p202808 VALUES LESS THAN (740591),
    PARTITION p202809 VALUES LESS THAN (740621),
    PARTITION p202810 VALUES LESS THAN (740652),
    PARTITION p202811 VALUES LESS THAN (740682),
    PARTITION p202812 VALUES LESS THAN (740713),
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


