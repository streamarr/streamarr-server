-- Account invitations and password recovery (ADR 0024 §Invitations, §Account; server PR #9).
-- Invitation records are retained for reporting; deleted targets go SET NULL while snapshot
-- columns keep what the historical row meant. Codes are opaque publicId.secret pairs; only the
-- SHA-256 digest of the secret is stored.

CREATE TYPE account_invitation_status AS ENUM
    ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELED', 'EXPIRED', 'INVALIDATED');
CREATE TYPE password_reset_code_status AS ENUM
    ('PENDING', 'REDEEMED', 'CANCELED', 'EXPIRED', 'INVALIDATED');

CREATE TABLE account_invitation
(
    id                        UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by                UUID,
    last_modified_on          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by          UUID,
    recipient_email           TEXT                     NOT NULL,
    household_id              UUID,
    household_name            TEXT                     NOT NULL,
    household_role            household_role           NOT NULL,
    profile_name              TEXT                     NOT NULL,
    profile_kind              profile_kind             NOT NULL,
    maximum_allowed_rating_age INTEGER,
    local_manager_account_id  UUID,
    issuer_account_id         UUID,
    status                    account_invitation_status NOT NULL DEFAULT 'PENDING',
    expires_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at                TIMESTAMP WITH TIME ZONE,
    invalidation_reason       TEXT,
    public_id                 TEXT                     NOT NULL,
    secret_digest             BYTEA                    NOT NULL,
    CONSTRAINT account_invitation_pkey PRIMARY KEY (id),
    CONSTRAINT uq_account_invitation_public_id UNIQUE (public_id),
    CONSTRAINT chk_account_invitation_email_not_blank CHECK (btrim(recipient_email) <> ''),
    CONSTRAINT chk_account_invitation_profile_name_not_blank CHECK (btrim(profile_name) <> ''),
    CONSTRAINT fk_account_invitation_household FOREIGN KEY (household_id)
        REFERENCES household (id) ON DELETE SET NULL,
    CONSTRAINT fk_account_invitation_local_manager FOREIGN KEY (local_manager_account_id)
        REFERENCES user_account (id) ON DELETE SET NULL,
    CONSTRAINT fk_account_invitation_issuer FOREIGN KEY (issuer_account_id)
        REFERENCES user_account (id) ON DELETE SET NULL
);

-- KISS: only the newest invitation for an email may remain pending.
CREATE UNIQUE INDEX uq_account_invitation_pending_email
    ON account_invitation (lower(recipient_email))
    WHERE status = 'PENDING';
CREATE INDEX idx_account_invitation_issuer ON account_invitation (issuer_account_id)
    WHERE status = 'PENDING';

CREATE TABLE password_reset_code
(
    id                  UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by          UUID,
    last_modified_on    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by    UUID,
    account_id          UUID                     NOT NULL,
    issuer_account_id   UUID,
    status              password_reset_code_status NOT NULL DEFAULT 'PENDING',
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    redeemed_at         TIMESTAMP WITH TIME ZONE,
    invalidation_reason TEXT,
    public_id           TEXT                     NOT NULL,
    secret_digest       BYTEA                    NOT NULL,
    CONSTRAINT password_reset_code_pkey PRIMARY KEY (id),
    CONSTRAINT uq_password_reset_code_public_id UNIQUE (public_id),
    -- A reset code is deleted with its Account; it never outlives the secret's subject.
    CONSTRAINT fk_password_reset_code_account FOREIGN KEY (account_id)
        REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_password_reset_code_issuer FOREIGN KEY (issuer_account_id)
        REFERENCES user_account (id) ON DELETE SET NULL
);

-- Only one reset code per Account is current.
CREATE UNIQUE INDEX uq_password_reset_code_pending_account
    ON password_reset_code (account_id)
    WHERE status = 'PENDING';
CREATE INDEX idx_password_reset_code_issuer ON password_reset_code (issuer_account_id)
    WHERE status = 'PENDING';
