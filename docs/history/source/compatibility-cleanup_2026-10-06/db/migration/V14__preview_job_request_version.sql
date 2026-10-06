ALTER TABLE dispatch_preview_job
    ADD COLUMN request_version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_job_request_order ON dispatch_preview_job
    (tenant_id, user_id, conversation_id, request_version);
