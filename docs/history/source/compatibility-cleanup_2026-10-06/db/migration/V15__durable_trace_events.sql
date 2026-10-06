-- 业务状态与证据事件在同一事务提交；展示表可从事件幂等重建。
CREATE TABLE trace_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_key VARCHAR(191) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(32) NULL,
    preview_id VARCHAR(32) NULL,
    plan_id VARCHAR(32) NULL,
    event_type VARCHAR(32) NOT NULL,
    plan_item_id BIGINT NULL,
    execution_version BIGINT NULL,
    attempt_count INT NULL,
    phase VARCHAR(32) NULL,
    outcome VARCHAR(16) NULL,
    payload JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    delivery_attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    delivered_at DATETIME(3) NULL,
    last_error VARCHAR(1024) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace_event (event_key),
    KEY idx_trace_delivery (delivery_status, next_attempt_at, id),
    KEY idx_trace_plan (tenant_id, plan_id, id),
    KEY idx_trace_item (plan_id, plan_item_id, attempt_count, outcome),
    KEY idx_trace_conversation (tenant_id, conversation_id, event_type, delivery_status, id),
    KEY idx_trace_preview (tenant_id, preview_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变追溯事件及持久化补写队列';

ALTER TABLE dispatch_audit
    ADD COLUMN evidence_id BIGINT NULL,
    ADD COLUMN execution_version BIGINT NULL,
    ADD COLUMN attempt_count INT NULL,
    ADD COLUMN phase VARCHAR(32) NULL,
    ADD COLUMN rule_snapshot JSON NULL,
    ADD UNIQUE KEY uk_audit_evidence (evidence_id);

ALTER TABLE agent_message
    ADD COLUMN evidence_id BIGINT NULL,
    ADD COLUMN trace_id VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_message_evidence (evidence_id);

ALTER TABLE dispatch_plan ADD COLUMN evidence_version INT NOT NULL DEFAULT 0;
ALTER TABLE dispatch_plan_item ADD COLUMN rule_snapshot JSON NULL;

-- 老清单尽量补回对应版本的规则，无法核实的保留 NULL，由追溯接口明确提示。
UPDATE dispatch_plan_item i
JOIN dispatch_plan p ON p.id=i.plan_id
JOIN dispatch_rule r ON r.id=i.rule_id AND r.version=i.rule_version
    AND r.tenant_id=p.tenant_id AND r.report_id=i.report_id
SET i.rule_snapshot=JSON_OBJECT('ruleId', r.id, 'version', r.version, 'reportId', r.report_id,
    'companyCode', r.company_code, 'name', r.name, 'expression', r.expression,
    'description', r.description, 'provenance', 'MIGRATION_EXACT_VERSION');
