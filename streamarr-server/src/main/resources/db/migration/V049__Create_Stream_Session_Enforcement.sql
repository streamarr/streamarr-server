CREATE TYPE stream_session_status AS ENUM ('PROVISIONING', 'ACTIVE', 'TERMINATING');
CREATE TYPE stream_session_terminal_reason AS ENUM (
    'STARTUP_FAILURE',
    'OWNER_DESTROY',
    'RETENTION_EXPIRED',
    'PROVISIONING_TIMEOUT',
    'AUTH_REVOKED',
    'SOURCE_DELETED'
);

ALTER TABLE auth_session
    ADD CONSTRAINT uq_auth_session_id_account UNIQUE (id, account_id);

CREATE TABLE stream_session
(
    id                UUID                           NOT NULL DEFAULT gen_random_uuid(),
    auth_session_id   UUID                           NOT NULL,
    account_id        UUID                           NOT NULL,
    household_id      UUID                           NOT NULL,
    profile_id        UUID                           NOT NULL,
    media_file_id     UUID                           NOT NULL,
    status            stream_session_status          NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE       NOT NULL DEFAULT statement_timestamp(),
    last_accessed_at  TIMESTAMP WITH TIME ZONE       NOT NULL DEFAULT statement_timestamp(),
    terminal_at       TIMESTAMP WITH TIME ZONE,
    terminal_reason   stream_session_terminal_reason,
    CONSTRAINT stream_session_pkey PRIMARY KEY (id),
    CONSTRAINT fk_stream_session_auth_account FOREIGN KEY (auth_session_id, account_id)
        REFERENCES auth_session (id, account_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stream_session_account FOREIGN KEY (account_id)
        REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stream_session_membership FOREIGN KEY (account_id, household_id)
        REFERENCES household_membership (account_id, household_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stream_session_profile_household FOREIGN KEY (profile_id, household_id)
        REFERENCES profile (id, household_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stream_session_account_profile FOREIGN KEY (account_id, profile_id)
        REFERENCES account_profile (account_id, profile_id) ON DELETE RESTRICT,
    CONSTRAINT chk_stream_session_terminal_fields CHECK (
        (status = 'TERMINATING' AND terminal_at IS NOT NULL AND terminal_reason IS NOT NULL)
        OR
        (status <> 'TERMINATING' AND terminal_at IS NULL AND terminal_reason IS NULL)
    )
);

CREATE TABLE stream_session_termination_intent
(
    stream_session_id UUID                           NOT NULL,
    terminal_at       TIMESTAMP WITH TIME ZONE       NOT NULL,
    terminal_reason   stream_session_terminal_reason NOT NULL,
    replay_after      TIMESTAMP WITH TIME ZONE       NOT NULL,
    armed             BOOLEAN                        NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP WITH TIME ZONE       NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT stream_session_termination_intent_pkey PRIMARY KEY (stream_session_id),
    CONSTRAINT fk_stream_session_termination_intent_session FOREIGN KEY (stream_session_id)
        REFERENCES stream_session (id) ON DELETE CASCADE
);

CREATE INDEX idx_stream_session_auth_account
    ON stream_session (auth_session_id, account_id);
CREATE INDEX idx_stream_session_account_household
    ON stream_session (account_id, household_id);
CREATE INDEX idx_stream_session_profile_household
    ON stream_session (profile_id, household_id);
CREATE INDEX idx_stream_session_account_profile
    ON stream_session (account_id, profile_id);
CREATE INDEX idx_stream_session_media_file
    ON stream_session (media_file_id);
CREATE INDEX idx_stream_session_active_retention
    ON stream_session (last_accessed_at, id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_stream_session_provisioning_timeout
    ON stream_session (created_at, id)
    WHERE status = 'PROVISIONING';
CREATE INDEX idx_stream_session_terminating_cleanup
    ON stream_session (terminal_at, id)
    WHERE status = 'TERMINATING';
CREATE INDEX idx_stream_session_termination_intent_replay
    ON stream_session_termination_intent (armed, replay_after, stream_session_id);
