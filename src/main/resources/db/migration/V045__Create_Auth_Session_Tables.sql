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
        REFERENCES profile (id) ON DELETE SET NULL,
    -- Enforced only when both columns are set (MATCH SIMPLE): a selected profile must belong
    -- to the selected household; deleting the profile clears only the profile column. A
    -- profile-only selection (household NULL) is NOT blocked here — and cannot be blocked by
    -- a CHECK, because the membership/household delete cascades clear the two columns in
    -- separate statements and the intermediate row would violate it. The profile-requires-
    -- household pairing is an application-layer rule.
    CONSTRAINT fk_auth_session_active_profile_household FOREIGN KEY (active_profile_id, active_household_id)
        REFERENCES profile (id, household_id) ON DELETE SET NULL (active_profile_id),
    -- A selected household must be one of the account's memberships (the authorization
    -- boundary from V044); revoking the membership clears the selection instead of blocking
    -- the revoke.
    CONSTRAINT fk_auth_session_active_membership FOREIGN KEY (account_id, active_household_id)
        REFERENCES household_membership (account_id, household_id) ON DELETE SET NULL (active_household_id),
    -- A selected profile must be linked to the session's account; revoking the account_profile
    -- link downgrades the session to household scope at the database level.
    CONSTRAINT fk_auth_session_active_account_profile FOREIGN KEY (account_id, active_profile_id)
        REFERENCES account_profile (account_id, profile_id) ON DELETE SET NULL (active_profile_id),
    -- A session is revoked iff both revocation fields are set; half-revoked states are
    -- unrepresentable.
    CONSTRAINT chk_auth_session_revocation_pair
        CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
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
    CONSTRAINT uq_refresh_token_digest UNIQUE (digest),
    -- One-directional on purpose: a later ROTATED -> REVOKED transition (token-reuse handling)
    -- legitimately keeps rotated_at set on a REVOKED row.
    CONSTRAINT chk_refresh_token_rotated_at
        CHECK (status <> 'ROTATED' OR rotated_at IS NOT NULL)
);

CREATE UNIQUE INDEX uq_refresh_token_active_session ON refresh_token (session_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_refresh_token_session_id ON refresh_token (session_id);
