-- Direct ProfileManager invitations (ADR 0024 §ProfileManager; server PR #12).
-- A manager invitation names an existing Account, so issue/accept/decline/cancel are
-- authenticated GraphQL mutations decided by Cedar — never a REST ceremony. The code uses the
-- shared publicId.secret format; only the digest is stored.

CREATE TYPE profile_manager_invitation_status AS ENUM
    ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELED', 'EXPIRED', 'INVALIDATED');

CREATE TABLE profile_manager_invitation
(
    id                   UUID                              NOT NULL DEFAULT gen_random_uuid(),
    created_on           TIMESTAMP WITH TIME ZONE          NOT NULL DEFAULT NOW(),
    created_by           UUID,
    last_modified_on     TIMESTAMP WITH TIME ZONE          NOT NULL DEFAULT NOW(),
    last_modified_by     UUID,
    profile_id           UUID,
    profile_name         TEXT                              NOT NULL,
    inviter_account_id   UUID,
    inviter_display_name TEXT                              NOT NULL,
    recipient_account_id UUID,
    recipient_email      TEXT                              NOT NULL,
    status               profile_manager_invitation_status NOT NULL DEFAULT 'PENDING',
    expires_at           TIMESTAMP WITH TIME ZONE          NOT NULL,
    decided_at           TIMESTAMP WITH TIME ZONE,
    invalidation_reason  TEXT,
    public_id            TEXT                              NOT NULL,
    secret_digest        BYTEA                             NOT NULL,
    CONSTRAINT profile_manager_invitation_pkey PRIMARY KEY (id),
    CONSTRAINT uq_profile_manager_invitation_public_id UNIQUE (public_id),
    CONSTRAINT fk_pm_invitation_profile FOREIGN KEY (profile_id)
        REFERENCES profile (id) ON DELETE SET NULL,
    CONSTRAINT fk_pm_invitation_inviter FOREIGN KEY (inviter_account_id)
        REFERENCES user_account (id) ON DELETE SET NULL,
    CONSTRAINT fk_pm_invitation_recipient FOREIGN KEY (recipient_account_id)
        REFERENCES user_account (id) ON DELETE SET NULL
);

-- At most one PENDING invitation per Profile and recipient.
CREATE UNIQUE INDEX uq_pm_invitation_live
    ON profile_manager_invitation (profile_id, recipient_account_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_pm_invitation_recipient ON profile_manager_invitation (recipient_account_id);
CREATE INDEX idx_pm_invitation_profile ON profile_manager_invitation (profile_id);
