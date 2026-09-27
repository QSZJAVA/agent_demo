ALTER TABLE agent_conversation
    ADD COLUMN preview_request_version BIGINT NOT NULL DEFAULT 0;
