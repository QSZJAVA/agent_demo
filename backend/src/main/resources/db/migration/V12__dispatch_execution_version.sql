ALTER TABLE dispatch_plan ADD COLUMN execution_version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_job_conversation ON dispatch_preview_job (tenant_id, user_id, conversation_id, created_at);
