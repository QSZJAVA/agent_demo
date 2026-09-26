-- Existing installations have one demo tenant. Report IDs remain globally unique;
-- report codes, rules and all access paths are scoped to the owning tenant.
ALTER TABLE report_definition ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'T001';
ALTER TABLE report_alias ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'T001';
ALTER TABLE dispatch_rule ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'T001';
ALTER TABLE dispatch_rule_history ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'T001';
ALTER TABLE agent_message ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'T001';

UPDATE agent_message m JOIN agent_conversation c ON c.id = m.conversation_id
SET m.tenant_id = c.tenant_id;

ALTER TABLE report_definition ALTER COLUMN tenant_id DROP DEFAULT,
    DROP INDEX uk_report_code, ADD UNIQUE KEY uk_report_code (tenant_id, report_code);
ALTER TABLE report_alias ALTER COLUMN tenant_id DROP DEFAULT,
    ADD KEY idx_alias_tenant (tenant_id, report_id);
ALTER TABLE dispatch_rule ALTER COLUMN tenant_id DROP DEFAULT,
    DROP INDEX uk_rule_report, ADD UNIQUE KEY uk_rule_report (tenant_id, report_id, company_code, version);
ALTER TABLE dispatch_rule_history ALTER COLUMN tenant_id DROP DEFAULT,
    ADD KEY idx_history_tenant (tenant_id, report_id, company_code, id);
ALTER TABLE agent_message ALTER COLUMN tenant_id DROP DEFAULT,
    ADD KEY idx_message_tenant (tenant_id, conversation_id, id);

-- Only migrate known demo configurations. Custom business tables must explicitly
-- supply their tenant column; missing configuration now fails closed.
UPDATE report_definition
SET query_config = JSON_SET(query_config, '$.tenantColumn', 'tenant_id'),
    catalog_version = catalog_version + 1
WHERE tenant_id = 'T001' AND query_mode = 'STANDARD'
  AND report_id IN ('rpt-sales-order', 'rpt-ar-invoice', 'rpt-expense-claim')
  AND JSON_EXTRACT(query_config, '$.tenantColumn') IS NULL;
