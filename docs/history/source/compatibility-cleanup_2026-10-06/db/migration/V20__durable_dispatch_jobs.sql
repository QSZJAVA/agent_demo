CREATE TABLE dispatch_job_queue (
  tenant_id VARCHAR(64) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dispatch_job (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  plan_id VARCHAR(64),
  action VARCHAR(32) NOT NULL,
  expected_version BIGINT,
  idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_json MEDIUMTEXT NOT NULL,
  trace_id VARCHAR(64),
  status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
  claim_token VARCHAR(64),
  lease_until DATETIME(3),
  expires_at DATETIME(3) NOT NULL,
  result_json MEDIUMTEXT,
  message VARCHAR(512),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  active_plan VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status IN ('QUEUED','RUNNING') THEN plan_id ELSE NULL END) STORED,
  UNIQUE KEY uk_dispatch_job_request(tenant_id,user_id,idempotency_key),
  UNIQUE KEY uk_dispatch_job_active_plan(active_plan),
  INDEX idx_dispatch_job_queue(status,created_at),
  INDEX idx_dispatch_job_tenant_queue(tenant_id,status,user_id),
  INDEX idx_dispatch_job_lease(status,lease_until),
  INDEX idx_dispatch_job_owner(tenant_id,user_id,plan_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
