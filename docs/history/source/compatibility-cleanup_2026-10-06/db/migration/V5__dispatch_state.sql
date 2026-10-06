-- =====================================================================
-- V5 预览快照与待确认清单的服务端状态（P0-07）
-- 业务事实落 MySQL，不再只依赖 Redis 里的“最新一份”：刷新页面、换设备、重启服务后状态一致。
-- 预览：ACTIVE → SUPERSEDED（同会话出现新预览）/ EXPIRED（超时或目录、规则、权限版本变化）/ CONSUMED（已据此执行派单）
-- 清单：PENDING → EXECUTING → EXECUTED；PENDING → CANCELLED / EXPIRED（超时、出现新预览或新清单、版本变化）
-- 状态迁移全部是带原状态条件的 UPDATE（CAS），并发请求只有一个能成功。
-- 生成列 + 唯一索引兜底：同一会话同一时刻最多一份 ACTIVE 预览、一份 PENDING 清单（NULL 不参与唯一约束）。
-- =====================================================================

CREATE TABLE dispatch_preview
(
    id                 VARCHAR(32)    NOT NULL,
    tenant_id          VARCHAR(64)    NOT NULL,
    user_id            VARCHAR(64)    NOT NULL,
    conversation_id    VARCHAR(32)             COMMENT '通过 REST 直接创建时可为空',
    source             VARCHAR(16)    NOT NULL COMMENT 'agent / fallback / selection / api',
    report_ids         JSON           NOT NULL COMMENT '预览范围内的 report_id，按目录顺序',
    company_codes      JSON           NOT NULL COMMENT '预览时生效的公司（组织）范围',
    query_json         JSON           NOT NULL COMMENT '统一预览请求：operation / reportQuery / matchType / reportIds / filters / excludes / scopeMode',
    catalog_version    VARCHAR(128)   NOT NULL COMMENT '范围内报表目录版本的指纹',
    rule_version       VARCHAR(128)   NOT NULL COMMENT '范围内生效规则（报表 x 公司）的指纹',
    permission_version VARCHAR(128)   NOT NULL COMMENT '用户权限版本',
    status             VARCHAR(16)    NOT NULL COMMENT 'ACTIVE / SUPERSEDED / EXPIRED / CONSUMED',
    status_reason      VARCHAR(32)             COMMENT 'NEW_PREVIEW / TTL / RULE_CHANGED / CATALOG_CHANGED / PERMISSION_CHANGED / EXECUTED ...',
    total_count        INT            NOT NULL DEFAULT 0,
    total_amount       DECIMAL(18, 2) NOT NULL DEFAULT 0,
    expires_at         DATETIME(3)    NOT NULL,
    created_at         DATETIME(3)    NOT NULL,
    updated_at         DATETIME(3)    NOT NULL,
    active_guard       VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN conversation_id END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_preview_active (active_guard),
    KEY idx_preview_owner (tenant_id, user_id, conversation_id, created_at),
    KEY idx_preview_conv (conversation_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '派单预览快照';

CREATE TABLE dispatch_preview_item
(
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    preview_id       VARCHAR(32)    NOT NULL,
    seq              INT            NOT NULL COMMENT '预览内顺序',
    report_id        VARCHAR(64)    NOT NULL,
    report_name      VARCHAR(256)   NOT NULL,
    catalog_version  BIGINT         NOT NULL COMMENT '预览时该报表的目录版本',
    record_id        VARCHAR(128)   NOT NULL,
    doc_no           VARCHAR(128),
    company_code     VARCHAR(64),
    label            VARCHAR(512),
    counterparty_json JSON COMMENT '交易对方实体快照JSON，结构为id、name、aliases；标识在所属租户和公司内稳定；空表示来源未提供客户实体',
    fields_json JSON NOT NULL COMMENT '已配置标量字段快照JSON数组，元素为name、type、value；value为空表示来源空值；随预览或清单保留，不重新读取来源',
    amount           DECIMAL(18, 2),
    biz_date         DATE,
    rule_id          BIGINT,
    rule_name        VARCHAR(64),
    rule_version     INT,
    rule_description VARCHAR(512),
    PRIMARY KEY (id),
    UNIQUE KEY uk_preview_record (preview_id, report_id, record_id),
    KEY idx_preview_seq (preview_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '预览快照记录';

CREATE TABLE dispatch_plan
(
    id              VARCHAR(32)  NOT NULL,
    preview_id      VARCHAR(32)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(32),
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING / EXECUTING / EXECUTED / CANCELLED / EXPIRED',
    status_reason   VARCHAR(32)           COMMENT 'NEW_PREVIEW / NEW_PLAN / TTL / RULE_CHANGED / CATALOG_CHANGED / PERMISSION_CHANGED / USER_CANCELLED ...',
    exclude_json    JSON                  COMMENT '用户排除的单据号',
    item_count      INT          NOT NULL,
    success_count   INT          NOT NULL DEFAULT 0,
    failed_count    INT          NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(128) NOT NULL COMMENT '创建请求的幂等键；同租户同键只生成一份清单',
    created_at      DATETIME(3)  NOT NULL,
    expires_at      DATETIME(3)  NOT NULL,
    confirmed_at    DATETIME(3),
    confirmed_by    VARCHAR(64),
    finished_at     DATETIME(3),
    updated_at      DATETIME(3)  NOT NULL,
    pending_guard   VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN conversation_id END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_pending (pending_guard),
    UNIQUE KEY uk_plan_idempotency (tenant_id, idempotency_key),
    KEY idx_plan_owner (tenant_id, user_id, conversation_id, status),
    KEY idx_plan_preview (preview_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '待确认派单清单';

CREATE TABLE dispatch_plan_item
(
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    plan_id             VARCHAR(32)    NOT NULL,
    seq                 INT            NOT NULL,
    report_id           VARCHAR(64)    NOT NULL,
    report_name         VARCHAR(256)   NOT NULL,
    catalog_version     BIGINT         NOT NULL,
    record_id           VARCHAR(128)   NOT NULL,
    doc_no              VARCHAR(128),
    company_code        VARCHAR(64),
    label               VARCHAR(512),
    counterparty_json JSON COMMENT '交易对方实体快照JSON，结构为id、name、aliases；标识在所属租户和公司内稳定；空表示来源未提供客户实体',
    fields_json JSON NOT NULL COMMENT '已配置标量字段快照JSON数组，元素为name、type、value；value为空表示来源空值；随预览或清单保留，不重新读取来源',
    amount              DECIMAL(18, 2),
    biz_date            DATE,
    rule_id             BIGINT,
    rule_name           VARCHAR(64),
    rule_version        INT,
    status              VARCHAR(16)    NOT NULL COMMENT 'PENDING / SUCCESS / FAILED / SKIPPED',
    attempt_count       INT            NOT NULL DEFAULT 0,
    external_request_id VARCHAR(128)            COMMENT '发给派单接口的幂等请求号',
    error_code          VARCHAR(64),
    error_message       VARCHAR(1024),
    updated_at          DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_record (plan_id, report_id, record_id),
    KEY idx_plan_seq (plan_id, seq),
    KEY idx_item_retry (status, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '待确认清单条目与逐条执行结果';
