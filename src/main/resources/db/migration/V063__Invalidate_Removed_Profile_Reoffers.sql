-- A reoffer captures a Household's decision at invitation issue time. Once that Household
-- removes the Profile, the old invitation must not restore its offer after a later re-share.
CREATE FUNCTION invalidate_account_invitation_reoffers_when_share_ends()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF OLD.status <> 'ACTIVE' THEN
        RETURN COALESCE(NEW, OLD);
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.status = 'ACTIVE' THEN
        RETURN NEW;
    END IF;

    DELETE FROM account_invitation_reoffer reoffer
    USING account_invitation invitation
    WHERE reoffer.invitation_id = invitation.id
      AND invitation.profile_id = OLD.profile_id
      AND reoffer.household_id = OLD.household_id;

    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER invalidate_account_invitation_reoffers_when_share_ends
    AFTER UPDATE OF status OR DELETE
    ON profile_household_share
    FOR EACH ROW
EXECUTE FUNCTION invalidate_account_invitation_reoffers_when_share_ends();
