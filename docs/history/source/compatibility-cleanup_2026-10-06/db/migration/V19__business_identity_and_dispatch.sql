CREATE TABLE app_user (
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  password_hash VARCHAR(256) NOT NULL,
  companies_json TEXT NOT NULL,
  permissions_json TEXT NOT NULL,
  is_admin BOOLEAN NOT NULL DEFAULT FALSE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY(tenant_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE app_session (
  token_hash CHAR(64) NOT NULL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_session_user(tenant_id,user_id),
  INDEX idx_session_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE business_dispatch_request (
  tenant_id VARCHAR(64) NOT NULL,
  request_id VARCHAR(160) NOT NULL,
  operator_id VARCHAR(64) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  report_id VARCHAR(64) NOT NULL,
  record_id VARCHAR(128) NOT NULL,
  status VARCHAR(20) NOT NULL,
  error_code VARCHAR(64),
  message VARCHAR(500),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
