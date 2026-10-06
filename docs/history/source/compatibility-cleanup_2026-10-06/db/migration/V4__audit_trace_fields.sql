-- =====================================================================
-- V4 关键审计字段（P0-09）与会话租户（P0-08）
-- 一条审计记录要能单独回答：谁（租户 / 用户）、在哪个会话、对哪张报表（report_id + 当时名称）、
-- 依据哪个预览 / 清单 / 清单条目、命中哪条规则（id + 版本）、预览时的目录 / 规则 / 权限版本、结果与失败原因。
-- 存量记录：租户按 Demo 唯一租户 T001 回填，report_id 按映射表回填，outcome 由 success 推出。
-- =====================================================================

ALTER TABLE dispatch_audit
    ADD COLUMN tenant_id           VARCHAR(64)  NULL COMMENT '租户' AFTER id,
    ADD COLUMN report_id           VARCHAR(64)  NULL COMMENT '报表目录中的稳定标识' AFTER source,
    ADD COLUMN report_name         VARCHAR(256) NULL COMMENT '派单时的报表名称' AFTER report_id,
    ADD COLUMN plan_item_id        BIGINT       NULL COMMENT '对应的清单条目' AFTER plan_id,
    ADD COLUMN rule_id             BIGINT       NULL COMMENT '命中的规则' AFTER amount,
    ADD COLUMN catalog_version     BIGINT       NULL COMMENT '预览时该报表的目录版本' AFTER rule_version,
    ADD COLUMN rule_fingerprint    VARCHAR(128) NULL COMMENT '预览时范围内生效规则的指纹' AFTER catalog_version,
    ADD COLUMN permission_version  VARCHAR(128) NULL COMMENT '预览时用户权限的版本' AFTER rule_fingerprint,
    ADD COLUMN outcome             VARCHAR(16)  NULL COMMENT 'SUCCESS / FAILED / SKIPPED（执行前复核不通过，未调用派单接口）' AFTER success,
    ADD COLUMN error_code          VARCHAR(64)  NULL COMMENT '失败原因编码' AFTER outcome,
    ADD COLUMN external_request_id VARCHAR(128) NULL COMMENT '发给派单接口的幂等请求号' AFTER error_code,
    ADD COLUMN trace_id            VARCHAR(64)  NULL COMMENT '请求链路号，关联应用日志' AFTER message;

UPDATE dispatch_audit SET tenant_id = 'T001' WHERE tenant_id IS NULL;

UPDATE dispatch_audit a
    JOIN report_code_mapping m ON m.legacy_code = a.report_type
SET a.report_id = m.report_id;

UPDATE dispatch_audit
SET report_id = CONCAT('legacy-', report_type)
WHERE report_id IS NULL;

UPDATE dispatch_audit SET outcome = IF(success = 1, 'SUCCESS', 'FAILED') WHERE outcome IS NULL;

ALTER TABLE dispatch_audit
    RENAME COLUMN report_type TO legacy_report_type;

ALTER TABLE dispatch_audit
    MODIFY COLUMN tenant_id          VARCHAR(64)   NOT NULL COMMENT '租户',
    MODIFY COLUMN report_id          VARCHAR(64)   NOT NULL COMMENT '报表目录中的稳定标识',
    MODIFY COLUMN legacy_report_type VARCHAR(32)   NULL COMMENT '迁移前的 reportType，仅用于追溯；新记录为空',
    MODIFY COLUMN record_id          VARCHAR(128)  NULL,
    MODIFY COLUMN doc_no             VARCHAR(128)  NULL,
    MODIFY COLUMN company_code       VARCHAR(64)   NULL,
    MODIFY COLUMN outcome            VARCHAR(16)   NOT NULL COMMENT 'SUCCESS / FAILED / SKIPPED',
    MODIFY COLUMN message            VARCHAR(1024) NULL,
    ADD KEY idx_audit_tenant_user (tenant_id, user_id, created_at),
    ADD KEY idx_audit_report (report_id, created_at),
    ADD KEY idx_audit_preview (preview_id);

ALTER TABLE agent_conversation
    ADD COLUMN tenant_id VARCHAR(64) NULL COMMENT '租户' AFTER id;

UPDATE agent_conversation SET tenant_id = 'T001' WHERE tenant_id IS NULL;

ALTER TABLE agent_conversation
    MODIFY COLUMN tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
    DROP INDEX idx_user_last,
    ADD KEY idx_owner_last (tenant_id, user_id, status, last_message_at);
