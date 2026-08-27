-- A pending credential cannot be fulfilled after its target Household, required manager, or
-- issuer is deleted. The foreign keys retain the row for reporting by setting the reference to
-- NULL; these triggers record why the pending row became terminal in the same statement.

CREATE FUNCTION invalidate_account_invitation_when_target_disappears()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
DECLARE
    reason TEXT;
BEGIN
    IF OLD.status <> 'PENDING' OR OLD.expires_at <= NOW() THEN
        RETURN NEW;
    END IF;

    IF OLD.household_id IS NOT NULL AND NEW.household_id IS NULL THEN
        reason := 'target Household deleted';
    ELSIF OLD.local_manager_account_id IS NOT NULL AND NEW.local_manager_account_id IS NULL THEN
        reason := 'required manager deleted';
    ELSIF OLD.issuer_account_id IS NOT NULL AND NEW.issuer_account_id IS NULL THEN
        reason := 'issuer deleted';
    ELSE
        RETURN NEW;
    END IF;

    NEW.status := 'INVALIDATED';
    NEW.decided_at := NOW();
    NEW.invalidation_reason := reason;
    NEW.last_modified_on := NOW();
    NEW.last_modified_by := NULL;
    RETURN NEW;
END;
$$;

CREATE TRIGGER invalidate_account_invitation_when_target_disappears
    BEFORE UPDATE OF household_id, local_manager_account_id, issuer_account_id
    ON account_invitation
    FOR EACH ROW
EXECUTE FUNCTION invalidate_account_invitation_when_target_disappears();

-- A reset code is deleted with its Account; only its issuer can disappear underneath it.
CREATE FUNCTION invalidate_password_reset_code_when_issuer_disappears()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF OLD.status <> 'PENDING'
        OR OLD.expires_at <= NOW()
        OR OLD.issuer_account_id IS NULL
        OR NEW.issuer_account_id IS NOT NULL THEN
        RETURN NEW;
    END IF;

    NEW.status := 'INVALIDATED';
    NEW.invalidation_reason := 'issuer deleted';
    NEW.last_modified_on := NOW();
    NEW.last_modified_by := NULL;
    RETURN NEW;
END;
$$;

CREATE TRIGGER invalidate_password_reset_code_when_issuer_disappears
    BEFORE UPDATE OF issuer_account_id
    ON password_reset_code
    FOR EACH ROW
EXECUTE FUNCTION invalidate_password_reset_code_when_issuer_disappears();
