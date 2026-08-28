-- CONNECT invitations (ADR 0024 §Profile creation and Personal Profiles; server PR #314): an
-- Account invitation may connect an existing unlinked Profile instead of creating one. The
-- reoffer table records which Households should be offered the Profile afresh the moment the
-- invitation is accepted — their old shares admitted a Profile; once it is a person's, the same
-- share would admit the person, which those hosts never consented to.

CREATE TYPE account_invitation_mode AS ENUM ('CREATE', 'CONNECT');

ALTER TABLE account_invitation
    ADD COLUMN mode account_invitation_mode NOT NULL DEFAULT 'CREATE',
    ADD COLUMN profile_id UUID,
    ADD CONSTRAINT fk_account_invitation_profile FOREIGN KEY (profile_id)
        REFERENCES profile (id) ON DELETE SET NULL NOT VALID,
    ADD CONSTRAINT chk_account_invitation_connect_names_profile
        CHECK (mode <> 'CONNECT' OR profile_id IS NOT NULL OR status <> 'PENDING') NOT VALID;

-- SET NULL must resolve the credential before the CONNECT check sees a missing Profile. Expired
-- rows materialize their effective state; a live row records why it can no longer be accepted.
CREATE FUNCTION resolve_connect_invitation_when_profile_disappears()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF OLD.status <> 'PENDING'
        OR OLD.profile_id IS NULL
        OR NEW.profile_id IS NOT NULL THEN
        RETURN NEW;
    END IF;

    NEW.decided_at := NOW();
    NEW.last_modified_on := NOW();
    NEW.last_modified_by := NULL;
    IF OLD.expires_at <= NOW() THEN
        NEW.status := 'EXPIRED';
        RETURN NEW;
    END IF;

    NEW.status := 'INVALIDATED';
    NEW.invalidation_reason := 'Profile deleted';
    RETURN NEW;
END;
$$;

CREATE TRIGGER resolve_connect_invitation_when_profile_disappears
    BEFORE UPDATE OF profile_id
    ON account_invitation
    FOR EACH ROW
EXECUTE FUNCTION resolve_connect_invitation_when_profile_disappears();

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
    CONSTRAINT uq_account_invitation_reoffer_household UNIQUE (invitation_id, household_id),
    CONSTRAINT fk_account_invitation_reoffer_invitation FOREIGN KEY (invitation_id)
        REFERENCES account_invitation (id) ON DELETE CASCADE,
    CONSTRAINT fk_account_invitation_reoffer_household FOREIGN KEY (household_id)
        REFERENCES household (id) ON DELETE SET NULL
);
