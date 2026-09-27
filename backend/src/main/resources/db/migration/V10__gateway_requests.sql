CREATE TABLE dispatch_gateway_request (
    tenant_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    report_id VARCHAR(64) NOT NULL,
    record_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    message VARCHAR(512),
    created_at DATETIME NOT NULL,
    PRIMARY KEY (tenant_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
