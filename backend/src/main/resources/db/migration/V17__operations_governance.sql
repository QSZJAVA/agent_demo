CREATE TABLE catalog_revision (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id VARCHAR(64) NOT NULL, report_id VARCHAR(64) NOT NULL,
 catalog_version BIGINT NOT NULL, definition_json JSON NOT NULL, aliases_json JSON NOT NULL,
 created_by VARCHAR(64) NOT NULL, created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_catalog_revision(tenant_id,report_id,catalog_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE operations_policy (
 tenant_id VARCHAR(64) NOT NULL, policy_key VARCHAR(128) NOT NULL,
 version BIGINT NOT NULL, payload JSON NOT NULL, updated_by VARCHAR(64) NOT NULL,
 updated_at DATETIME(3) NOT NULL, PRIMARY KEY(tenant_id,policy_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE operations_policy_revision (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,
 policy_key VARCHAR(128) NOT NULL, version BIGINT NOT NULL, payload JSON NOT NULL,
 created_by VARCHAR(64) NOT NULL, created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_policy_revision(tenant_id,policy_key,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE operations_audit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,
 actor_id VARCHAR(64) NOT NULL, action VARCHAR(64) NOT NULL,
 resource_id VARCHAR(128) NOT NULL, outcome VARCHAR(32) NOT NULL,
 reason VARCHAR(512), created_at DATETIME(3) NOT NULL,
 KEY idx_ops_audit(tenant_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE business_metric (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,
 operation VARCHAR(32) NOT NULL, report_id VARCHAR(64) NOT NULL,
 version VARCHAR(128) NOT NULL, outcome VARCHAR(32) NOT NULL,
 duration_ms BIGINT NOT NULL, created_at DATETIME(3) NOT NULL,
 KEY idx_metric_scope(tenant_id,operation,created_at), KEY idx_metric_retention(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE conversation_erasure (
 conversation_id VARCHAR(32) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
 requested_by VARCHAR(64) NOT NULL, requested_at DATETIME(3) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', completed_at DATETIME(3),
 KEY idx_erasure_pending(status,requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_ops_plan ON dispatch_plan(tenant_id,updated_at,id);

CREATE TABLE resolver_evaluation_run (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, total_count INT NOT NULL, passed_count INT NOT NULL,
 result_json JSON NOT NULL, created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_evaluation_version(tenant_id,fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
