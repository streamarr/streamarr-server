-- CONNECT invitations (ADR 0024 §Profile creation and Personal Profiles; server PR #11): an
-- Account invitation may connect an existing unlinked Profile instead of creating one. The
-- reoffer table records which Households should be offered the Profile afresh the moment the
-- invitation is accepted — their old shares admitted a Profile; once it is a person's, the same
-- share would admit the person, which those hosts never consented to.

CREATE TYPE account_invitation_mode AS ENUM ('CREATE', 'CONNECT');

ALTER TABLE account_invitation
    ADD COLUMN mode account_invitation_mode NOT NULL DEFAULT 'CREATE',
    ADD COLUMN profile_id UUID,
    ADD CONSTRAINT fk_account_invitation_profile FOREIGN KEY (profile_id)
        REFERENCES profile (id) ON DELETE SET NULL,
    ADD CONSTRAINT chk_account_invitation_connect_names_profile
        CHECK (mode <> 'CONNECT' OR profile_id IS NOT NULL OR status <> 'PENDING');

CREATE TABLE account_invitation_reoffer
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    invitation_id    UUID                     NOT NULL,
    household_id     UUID,
    household_name   TEXT                     NOT NULL,
    CONSTRAINT account_invitation_reoffer_pkey PRIMARY KEY (id),
    CONSTRAINT fk_account_invitation_reoffer_invitation FOREIGN KEY (invitation_id)
        REFERENCES account_invitation (id) ON DELETE CASCADE,
    CONSTRAINT fk_account_invitation_reoffer_household FOREIGN KEY (household_id)
        REFERENCES household (id) ON DELETE SET NULL
);

CREATE INDEX idx_account_invitation_reoffer_invitation
    ON account_invitation_reoffer (invitation_id);
