-- A manager invitation that the server invalidates because of what became of its invited Account
-- still waits, to the Profile's ProfileManagers, exactly as an invitation that nobody can answer
-- does (ADR 0024 §Invitations). Its status keeps what they read; these columns keep the
-- invalidation that only the invited Account and ServerAdmin read.
ALTER TABLE profile_manager_invitation
    ADD COLUMN recipient_invalidated_at      TIMESTAMP WITH TIME ZONE,
    ADD COLUMN recipient_invalidation_reason TEXT;

ALTER TABLE profile_manager_invitation
    ADD CONSTRAINT chk_pm_invitation_recipient_invalidation_has_reason CHECK (
        (recipient_invalidated_at IS NULL) = (recipient_invalidation_reason IS NULL)
        );

-- Two pending invitations may name one Account for one Profile: one invalidated for its Account
-- still waits, and the Account's email may change between two invitations to two addresses.
DROP INDEX uq_pm_invitation_live;

-- Deleting the invited Account invalidates a pending invitation for that Account alone; deleting
-- its Profile or its inviter still ends it for everyone.
CREATE OR REPLACE FUNCTION resolve_profile_manager_invitation_when_party_disappears()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
DECLARE
    profile_gone BOOLEAN := OLD.profile_id IS NOT NULL AND NEW.profile_id IS NULL;
    inviter_gone BOOLEAN := OLD.inviter_account_id IS NOT NULL AND NEW.inviter_account_id IS NULL;
    recipient_gone BOOLEAN := OLD.recipient_account_id IS NOT NULL
        AND NEW.recipient_account_id IS NULL;
BEGIN
    IF OLD.status <> 'PENDING' OR NOT (profile_gone OR inviter_gone OR recipient_gone) THEN
        RETURN NEW;
    END IF;

    NEW.last_modified_on := NOW();
    NEW.last_modified_by := NULL;
    IF OLD.expires_at <= NOW() THEN
        NEW.decided_at := NOW();
        NEW.status := 'EXPIRED';
        NEW.invalidation_reason := NULL;
        RETURN NEW;
    END IF;

    IF profile_gone OR inviter_gone THEN
        NEW.decided_at := NOW();
        NEW.status := 'INVALIDATED';
        NEW.invalidation_reason := CASE WHEN profile_gone THEN 'Profile deleted' ELSE 'inviter deleted' END;
        RETURN NEW;
    END IF;

    NEW.recipient_invalidated_at := COALESCE(OLD.recipient_invalidated_at, NOW());
    NEW.recipient_invalidation_reason := COALESCE(OLD.recipient_invalidation_reason, 'recipient deleted');
    RETURN NEW;
END;
$$;
