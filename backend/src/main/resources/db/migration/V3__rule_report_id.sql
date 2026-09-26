-- =====================================================================
-- V3 规则主键从 reportType 升级为 report_id（P0-06）
-- 旧的 report_type 改名为 legacy_report_type 保留，旧规则和历史仍能按 Demo 时期的编码追溯；
-- 新规则只写 report_id。映射表中没有的旧编码（正常不会出现）保留 legacy- 前缀的占位 ID，
-- 目录里没有对应报表，这类规则不会生效，但记录不丢。
-- =====================================================================

ALTER TABLE dispatch_rule
    ADD COLUMN report_id VARCHAR(64) NULL COMMENT '报表目录中的稳定标识' AFTER id;

UPDATE dispatch_rule r
    JOIN report_code_mapping m ON m.legacy_code = r.report_type
SET r.report_id = m.report_id;

UPDATE dispatch_rule
SET report_id = CONCAT('legacy-', report_type)
WHERE report_id IS NULL;

ALTER TABLE dispatch_rule
    DROP INDEX uk_rule,
    RENAME COLUMN report_type TO legacy_report_type;

ALTER TABLE dispatch_rule
    MODIFY COLUMN report_id VARCHAR(64) NOT NULL COMMENT '报表目录中的稳定标识',
    MODIFY COLUMN legacy_report_type VARCHAR(32) NULL COMMENT '迁移前的 reportType，仅用于追溯；新规则为空',
    MODIFY COLUMN company_code VARCHAR(64) NOT NULL DEFAULT '*' COMMENT '* 通配；具体公司的规则优先于通配',
    ADD UNIQUE KEY uk_rule_report (report_id, company_code, version);

ALTER TABLE dispatch_rule_history
    ADD COLUMN report_id VARCHAR(64) NULL COMMENT '报表目录中的稳定标识' AFTER rule_id;

UPDATE dispatch_rule_history h
    JOIN report_code_mapping m ON m.legacy_code = h.report_type
SET h.report_id = m.report_id;

UPDATE dispatch_rule_history
SET report_id = CONCAT('legacy-', report_type)
WHERE report_id IS NULL;

ALTER TABLE dispatch_rule_history
    DROP INDEX idx_scope,
    RENAME COLUMN report_type TO legacy_report_type;

ALTER TABLE dispatch_rule_history
    MODIFY COLUMN report_id VARCHAR(64) NOT NULL COMMENT '报表目录中的稳定标识',
    MODIFY COLUMN legacy_report_type VARCHAR(32) NULL COMMENT '迁移前的 reportType，仅用于追溯；新记录为空',
    MODIFY COLUMN company_code VARCHAR(64) NOT NULL,
    ADD KEY idx_scope (report_id, company_code, id);
