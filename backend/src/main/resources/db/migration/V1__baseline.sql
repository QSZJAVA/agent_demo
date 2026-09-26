-- =====================================================================
-- V1 基线：Demo 引入 Flyway 之前由 schema.sql 创建的、属于本应用的表，结构保持原样。
-- 全部 IF NOT EXISTS：已有库（baseline-version=0）会执行本脚本，只补缺失的表，不动已有数据；
-- 新库从这里开始，后续版本再把它们迁移到报表目录 / report_id 模型。
-- 报表业务表（report_sales 等）不属于本应用，不在迁移里维护；演示数据见 db/demo/demo-data.sql。
-- =====================================================================

CREATE TABLE IF NOT EXISTS dispatch_rule
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    report_type    VARCHAR(32)  NOT NULL COMMENT 'sales / receivable / expense',
    company_code   VARCHAR(10)  NOT NULL DEFAULT '*' COMMENT '* 通配；具体公司的规则优先于通配',
    name           VARCHAR(64)  NOT NULL,
    description    VARCHAR(512)          COMMENT '给人和模型看的规则说明',
    expression     TEXT         NOT NULL COMMENT 'Aviator 表达式，在事实模型上求值',
    version        INT          NOT NULL,
    status         VARCHAR(16)  NOT NULL COMMENT 'draft / published / disabled',
    effective_from DATETIME,
    effective_to   DATETIME,
    created_at     DATETIME     NOT NULL,
    updated_by     VARCHAR(64),
    updated_at     DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule (report_type, company_code, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '派单规则';

CREATE TABLE IF NOT EXISTS dispatch_rule_history
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    rule_id      BIGINT       NOT NULL,
    report_type  VARCHAR(32)  NOT NULL,
    company_code VARCHAR(10)  NOT NULL,
    version      INT          NOT NULL,
    name         VARCHAR(64),
    expression   TEXT         NOT NULL,
    description  VARCHAR(512),
    action       VARCHAR(16)  NOT NULL COMMENT 'publish / disable / rollback',
    operated_by  VARCHAR(64),
    operated_at  DATETIME,
    PRIMARY KEY (id),
    KEY idx_scope (report_type, company_code, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '规则变更历史';

CREATE TABLE IF NOT EXISTS dispatch_audit
(
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(64)    NOT NULL,
    conversation_id VARCHAR(32),
    preview_id      VARCHAR(32),
    plan_id         VARCHAR(32),
    source          VARCHAR(16)    NOT NULL COMMENT 'agent / manual',
    report_type     VARCHAR(32)    NOT NULL,
    record_id       BIGINT,
    doc_no          VARCHAR(32),
    company_code    VARCHAR(10),
    amount          DECIMAL(18, 2),
    rule_name       VARCHAR(64),
    rule_version    INT,
    success         TINYINT(1)     NOT NULL,
    message         VARCHAR(255),
    created_at      DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, created_at),
    KEY idx_conv (conversation_id),
    KEY idx_plan (plan_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '派单审计';

CREATE TABLE IF NOT EXISTS agent_conversation
(
    id              VARCHAR(32)  NOT NULL COMMENT '服务端生成',
    user_id         VARCHAR(64)  NOT NULL,
    title           VARCHAR(64)           COMMENT '默认取首条用户消息前 30 字，可改名',
    model           VARCHAR(64)           COMMENT '会话使用的模型名',
    message_count   INT          NOT NULL DEFAULT 0,
    last_message_at DATETIME(3),
    status          VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active / deleted',
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_last (user_id, status, last_message_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'Agent 会话';

CREATE TABLE IF NOT EXISTS agent_message
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id   VARCHAR(32)  NOT NULL,
    user_id           VARCHAR(64)  NOT NULL COMMENT '冗余，便于按用户查询与清理',
    role              VARCHAR(16)  NOT NULL COMMENT 'user / assistant / tool_call / tool_result / card',
    content           MEDIUMTEXT            COMMENT '文本内容；tool_call 时为工具参数 JSON；tool_result 时为返回摘要',
    card_type         VARCHAR(16)           COMMENT 'preview / plan / result，仅 role = card',
    payload           JSON                  COMMENT '卡片载荷：预览记录列表、待确认清单、逐条派单结果',
    tool_name         VARCHAR(64),
    preview_id        VARCHAR(32),
    plan_id           VARCHAR(32),
    model             VARCHAR(64),
    prompt_tokens     INT,
    completion_tokens INT,
    latency_ms        INT,
    created_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conv (conversation_id, id),
    KEY idx_user_time (user_id, created_at),
    KEY idx_preview (preview_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'Agent 消息';
