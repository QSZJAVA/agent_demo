CREATE TABLE dispatch_preview_job (
    id VARCHAR(32) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(32),
    status VARCHAR(16) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    message VARCHAR(512),
    preview_id VARCHAR(32),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_job_owner (tenant_id, user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
