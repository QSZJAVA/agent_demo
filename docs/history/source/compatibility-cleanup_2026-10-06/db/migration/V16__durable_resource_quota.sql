-- Redis remains the rate limiter. Concurrent work must survive Redis lease-key loss.
CREATE TABLE resource_quota_scope (
    scope_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY
) ENGINE=InnoDB;

CREATE TABLE resource_quota_lease (
    token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    scope_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    KEY idx_quota_scope_expiry (scope_key, expires_at)
) ENGINE=InnoDB;
