CREATE TYPE session_revocation_reason AS ENUM ('LOGOUT', 'TOKEN_REUSE', 'PASSWORD_CHANGE', 'ADMIN');
CREATE TYPE refresh_token_status AS ENUM ('ACTIVE', 'ROTATED', 'REVOKED');

CREATE TABLE auth_session
(
    id                  UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by          UUID,
    last_modified_on    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by    UUID,
    account_id          UUID                     NOT NULL,
    device_name         TEXT,
    session_version     BIGINT                   NOT NULL DEFAULT 0,
    active_household_id UUID,
    active_profile_id   UUID,
    revoked_at          TIMESTAMP WITH TIME ZONE,
    revoked_reason      session_revocation_reason,
    last_used_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT auth_session_pkey PRIMARY KEY (id),
    CONSTRAINT fk_auth_session_account FOREIGN KEY (account_id)
        REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_auth_session_active_household FOREIGN KEY (active_household_id)
        REFERENCES household (id) ON DELETE SET NULL,
    CONSTRAINT fk_auth_session_active_profile FOREIGN KEY (active_profile_id)
        REFERENCES profile (id) ON DELETE SET NULL
);

CREATE INDEX idx_auth_session_account_id ON auth_session (account_id);
CREATE INDEX idx_auth_session_active_household_id ON auth_session (active_household_id);
CREATE INDEX idx_auth_session_active_profile_id ON auth_session (active_profile_id);

CREATE TABLE refresh_token
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    session_id       UUID                     NOT NULL,
    digest           TEXT                     NOT NULL,
    status           refresh_token_status     NOT NULL,
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    rotated_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT refresh_token_pkey PRIMARY KEY (id),
    CONSTRAINT fk_refresh_token_session FOREIGN KEY (session_id)
        REFERENCES auth_session (id) ON DELETE CASCADE,
    CONSTRAINT uq_refresh_token_digest UNIQUE (digest)
);

CREATE UNIQUE INDEX uq_refresh_token_active_session ON refresh_token (session_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_refresh_token_session_id ON refresh_token (session_id);
