CREATE TABLE semantic_dialogue (
 conversation_id VARCHAR(32) NOT NULL PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL, user_id VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, state_json JSON NOT NULL,
 lease_token VARCHAR(32), lease_until DATETIME(3), updated_at DATETIME(3) NOT NULL,
 KEY idx_semantic_owner(tenant_id,user_id,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE semantic_turn (
 request_id VARCHAR(32) NOT NULL PRIMARY KEY,
 conversation_id VARCHAR(32) NOT NULL, tenant_id VARCHAR(64) NOT NULL, user_id VARCHAR(64) NOT NULL,
 mode VARCHAR(16) NOT NULL, state_version BIGINT NOT NULL,
 utterance VARCHAR(2000) NOT NULL, intent_json JSON,
 outcome VARCHAR(32) NOT NULL, reason VARCHAR(512), model VARCHAR(128),
 latency_ms BIGINT NOT NULL, created_at DATETIME(3) NOT NULL,
 KEY idx_semantic_turn_conversation(tenant_id,conversation_id,created_at),
 KEY idx_semantic_turn_outcome(tenant_id,mode,outcome,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
