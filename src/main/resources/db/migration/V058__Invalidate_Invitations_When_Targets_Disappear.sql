-- A pending invitation cannot be fulfilled after its target Household or required manager is
-- deleted. The foreign keys retain the invitation for reporting by setting the target to NULL;
-- this trigger records why that pending invitation became terminal in the same statement.

CREATE FUNCTION invalidate_account_invitation_when_target_disappears()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF OLD.status <> 'PENDING' THEN
        RETURN NEW;
    END IF;

    IF OLD.household_id IS NOT NULL AND NEW.household_id IS NULL THEN
        NEW.status := 'INVALIDATED';
        NEW.decided_at := NOW();
        NEW.invalidation_reason := 'target Household deleted';
        NEW.last_modified_on := NOW();
        NEW.last_modified_by := NULL;
        RETURN NEW;
    END IF;

    IF OLD.local_manager_account_id IS NOT NULL AND NEW.local_manager_account_id IS NULL THEN
        NEW.status := 'INVALIDATED';
        NEW.decided_at := NOW();
        NEW.invalidation_reason := 'required manager deleted';
        NEW.last_modified_on := NOW();
        NEW.last_modified_by := NULL;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER invalidate_account_invitation_when_target_disappears
    BEFORE UPDATE OF household_id, local_manager_account_id
    ON account_invitation
    FOR EACH ROW
EXECUTE FUNCTION invalidate_account_invitation_when_target_disappears();
