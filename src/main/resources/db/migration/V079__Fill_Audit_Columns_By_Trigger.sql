-- The database fills every audit column, so JPA, jOOQ, triggers, migrations and manual SQL all
-- name the same actor. The application names the actor once per transaction:
--
--     SELECT set_config('streamarr.actor_id', '<account id or System actor id>', true);
--
-- A request made without an account names 'request-without-account' and stores an empty actor.
-- A write in a transaction that names no actor is rejected. A later migration that writes audited
-- rows must name the System actor (b634c755-3c6a-4ff1-a0a7-3e812d0ed1b9) first.

CREATE FUNCTION fill_audit_columns()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
DECLARE
    actor_setting TEXT := NULLIF(current_setting('streamarr.actor_id', true), '');
    actor UUID;
BEGIN
    IF actor_setting IS NULL THEN
        RAISE EXCEPTION 'transaction names no audit actor for a write to %', TG_TABLE_NAME
            USING HINT = 'SELECT set_config(''streamarr.actor_id'', <actor>, true) first';
    END IF;

    IF actor_setting <> 'request-without-account' THEN
        actor := actor_setting::UUID;
    END IF;

    IF TG_OP = 'INSERT' THEN
        NEW.created_by := actor;
        NEW.created_on := now();
    ELSE
        NEW.created_by := OLD.created_by;
        NEW.created_on := OLD.created_on;
    END IF;

    NEW.last_modified_by := actor;
    NEW.last_modified_on := now();
    RETURN NEW;
END;
$$;

-- BEFORE ROW triggers fire in name order. fill_audit_columns sorts before the invalidate_* and
-- resolve_* triggers, which clear the modifier when the system invalidates a credential.
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON account_invitation
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON account_invitation_reoffer
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON auth_session
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON base_collectable
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON company
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON device_authorization
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON device_registration
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON esn_block
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON external_identifier
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON genre
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON household
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON image
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON library
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON media_file
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON password_reset_code
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON person
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON profile
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON profile_household_share
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON profile_manager
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON profile_manager_invitation
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON rating
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON refresh_token
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON review
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON server_bootstrap
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON session_progress
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON user_account
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON watch_history
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();

-- Movies, series, seasons and episodes keep their audit columns in base_collectable. A change
-- that touches only the subtype table still names its modifier there.
CREATE FUNCTION touch_collectable_when_subtype_changes()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    UPDATE base_collectable
    SET last_modified_on = now()
    WHERE id = NEW.id
      AND last_modified_on IS DISTINCT FROM now();
    RETURN NULL;
END;
$$;

CREATE TRIGGER touch_collectable_when_subtype_changes AFTER UPDATE ON movie
    FOR EACH ROW EXECUTE FUNCTION touch_collectable_when_subtype_changes();
CREATE TRIGGER touch_collectable_when_subtype_changes AFTER UPDATE ON series
    FOR EACH ROW EXECUTE FUNCTION touch_collectable_when_subtype_changes();
CREATE TRIGGER touch_collectable_when_subtype_changes AFTER UPDATE ON season
    FOR EACH ROW EXECUTE FUNCTION touch_collectable_when_subtype_changes();
CREATE TRIGGER touch_collectable_when_subtype_changes AFTER UPDATE ON episode
    FOR EACH ROW EXECUTE FUNCTION touch_collectable_when_subtype_changes();
