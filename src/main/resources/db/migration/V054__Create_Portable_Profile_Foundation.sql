-- ADR 0022 intentionally resets the pre-production identity model. Streamarr has no production
-- instances, so preserving the V044/V045 family rows would add an unused migration workflow.
-- CASCADE erases dependent identity state including auth_session, refresh_token,
-- session_progress, and watch_history before rebuilding the portable-profile model.
TRUNCATE TABLE user_account, household, profile CASCADE;

ALTER TABLE user_account
    ADD COLUMN home_household_id UUID NOT NULL,
    ADD COLUMN household_role household_role NOT NULL,
    ADD CONSTRAINT fk_user_account_home_household FOREIGN KEY (home_household_id)
        REFERENCES household (id);

CREATE UNIQUE INDEX uq_user_account_household_owner
    ON user_account (home_household_id)
    WHERE household_role = 'OWNER';

CREATE FUNCTION assert_household_has_exactly_one_owner(candidate_household_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF candidate_household_id IS NULL
        OR NOT EXISTS (SELECT 1 FROM household WHERE id = candidate_household_id) THEN
        RETURN;
    END IF;

    IF (SELECT COUNT(*)
        FROM user_account
        WHERE home_household_id = candidate_household_id
          AND household_role = 'OWNER') = 1 THEN
        RETURN;
    END IF;

    RAISE EXCEPTION 'Household % must have exactly one owner', candidate_household_id
        USING ERRCODE = '23514', CONSTRAINT = 'chk_household_exactly_one_owner';
END;
$$;

CREATE FUNCTION enforce_account_household_owner()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM assert_household_has_exactly_one_owner(OLD.home_household_id);
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM assert_household_has_exactly_one_owner(NEW.home_household_id);
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_user_account_household_owner
    AFTER INSERT OR UPDATE OR DELETE
    ON user_account
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_account_household_owner();

CREATE FUNCTION enforce_new_household_owner()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    PERFORM assert_household_has_exactly_one_owner(NEW.id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_household_exactly_one_owner
    AFTER INSERT
    ON household
    DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_new_household_owner();

CREATE TYPE profile_classification AS ENUM ('KID', 'ADULT');

DROP INDEX idx_auth_session_active_household_id;

ALTER TABLE auth_session
    DROP CONSTRAINT fk_auth_session_active_account_profile,
    DROP CONSTRAINT fk_auth_session_active_membership,
    DROP CONSTRAINT fk_auth_session_active_profile_household,
    DROP CONSTRAINT fk_auth_session_active_household,
    DROP COLUMN active_household_id;
DROP TABLE account_profile;
DROP TABLE household_membership;

ALTER TABLE profile
    DROP CONSTRAINT fk_profile_household,
    DROP CONSTRAINT uq_profile_household_name,
    DROP CONSTRAINT uq_profile_id_household,
    DROP COLUMN household_id,
    ADD COLUMN classification profile_classification NOT NULL DEFAULT 'ADULT',
    ADD COLUMN maximum_allowed_rating_age INTEGER,
    ADD COLUMN pin_hash TEXT,
    ADD COLUMN management_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_profile_maximum_allowed_rating_age
        CHECK (maximum_allowed_rating_age IS NULL OR maximum_allowed_rating_age >= 0);

ALTER TABLE household
    ADD COLUMN safety_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE profile_manager
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    account_id       UUID                     NOT NULL,
    profile_id       UUID                     NOT NULL,
    CONSTRAINT profile_manager_pkey PRIMARY KEY (id),
    CONSTRAINT fk_profile_manager_account FOREIGN KEY (account_id)
        REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_manager_profile FOREIGN KEY (profile_id)
        REFERENCES profile (id) ON DELETE CASCADE,
    CONSTRAINT uq_profile_manager_account_profile UNIQUE (account_id, profile_id)
);

CREATE INDEX idx_profile_manager_profile_id ON profile_manager (profile_id);

CREATE TYPE profile_manager_invitation_status AS ENUM
    ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELED');

CREATE TABLE profile_manager_invitation
(
    id                 UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by         UUID,
    last_modified_on   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by   UUID,
    profile_id         UUID                     NOT NULL,
    inviting_account_id UUID                    NOT NULL,
    invited_account_id UUID                     NOT NULL,
    status             profile_manager_invitation_status NOT NULL,
    CONSTRAINT profile_manager_invitation_pkey PRIMARY KEY (id),
    CONSTRAINT fk_profile_manager_invitation_profile FOREIGN KEY (profile_id)
        REFERENCES profile (id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_manager_invitation_inviter FOREIGN KEY (inviting_account_id)
        REFERENCES user_account (id),
    CONSTRAINT fk_profile_manager_invitation_invitee FOREIGN KEY (invited_account_id)
        REFERENCES user_account (id)
);

CREATE UNIQUE INDEX uq_profile_manager_invitation_pending
    ON profile_manager_invitation (profile_id, invited_account_id)
    WHERE status = 'PENDING';

CREATE TYPE security_audit_operation AS ENUM
    ('PROFILE_CREATED', 'PROFILE_RENAMED', 'PROFILE_POLICY_CHANGED',
     'PROFILE_MANAGER_INVITED', 'PROFILE_MANAGER_ACCEPTED',
     'PROFILE_MANAGER_INVITATION_REJECTED', 'PROFILE_MANAGER_INVITATION_CANCELED',
     'PROFILE_MANAGER_RELINQUISHED', 'PROFILE_SHARE_OFFERED', 'PROFILE_SHARE_ACCEPTED',
     'PROFILE_SHARE_REJECTED', 'PROFILE_SHARE_CANCELED',
     'PROFILE_UNSHARED_BY_HOUSEHOLD', 'PROFILE_LEFT_HOME', 'PROFILE_DELETED',
     'PROFILE_FORCE_DELETED', 'PROFILE_MANAGER_OVERRIDDEN', 'PROFILE_FORCE_UNSHARED',
     'PROFILE_SELECTION_CLEARED', 'ACCOUNT_TRANSFERRED',
     'HOUSEHOLD_OWNERSHIP_TRANSFERRED');

CREATE TABLE security_audit_event
(
    id                  UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by          UUID,
    last_modified_on    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by    UUID,
    acting_account_id   UUID                     NOT NULL,
    target_account_id   UUID,
    target_household_id UUID,
    target_profile_id   UUID,
    operation           security_audit_operation NOT NULL,
    reason              TEXT,
    CONSTRAINT security_audit_event_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_security_audit_event_actor ON security_audit_event (acting_account_id);
CREATE INDEX idx_security_audit_event_profile ON security_audit_event (target_profile_id);

CREATE TYPE profile_share_status AS ENUM ('PENDING', 'ACTIVE');

CREATE TABLE profile_household_share
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    profile_id       UUID                     NOT NULL,
    household_id     UUID                     NOT NULL,
    status           profile_share_status     NOT NULL,
    CONSTRAINT profile_household_share_pkey PRIMARY KEY (id),
    CONSTRAINT fk_profile_household_share_profile FOREIGN KEY (profile_id)
        REFERENCES profile (id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_household_share_household FOREIGN KEY (household_id)
        REFERENCES household (id),
    CONSTRAINT uq_profile_household_share_profile_household UNIQUE (profile_id, household_id)
);

CREATE INDEX idx_profile_household_share_household_id
    ON profile_household_share (household_id);

CREATE TYPE profile_deletion_mode AS ENUM ('ORDINARY', 'FORCE');

CREATE TABLE profile_deletion_authorization
(
    id                  UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by          UUID,
    last_modified_on    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by    UUID,
    profile_id          UUID                     NOT NULL,
    acting_account_id   UUID                     NOT NULL,
    mode                profile_deletion_mode    NOT NULL,
    CONSTRAINT profile_deletion_authorization_pkey PRIMARY KEY (id),
    CONSTRAINT uq_profile_deletion_authorization_profile UNIQUE (profile_id),
    CONSTRAINT fk_profile_deletion_authorization_profile FOREIGN KEY (profile_id)
        REFERENCES profile (id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_deletion_authorization_account FOREIGN KEY (acting_account_id)
        REFERENCES user_account (id)
);

CREATE FUNCTION enforce_profile_deletion_authority()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    deletion_grant profile_deletion_authorization%ROWTYPE;
BEGIN
    SELECT *
    INTO deletion_grant
    FROM profile_deletion_authorization
    WHERE profile_id = OLD.id;

    IF deletion_grant.id IS NULL THEN
        RAISE EXCEPTION 'Profile % deletion requires explicit authorization', OLD.id
            USING ERRCODE = '23514', CONSTRAINT = 'chk_profile_deletion_authorized';
    END IF;

    IF deletion_grant.mode = 'FORCE' THEN
        IF EXISTS (
            SELECT 1
            FROM user_account account
            WHERE account.id = deletion_grant.acting_account_id
              AND account.enabled
              AND account.account_role = 'ADMIN') THEN
            RETURN OLD;
        END IF;

        RAISE EXCEPTION 'Force deletion requires a live ServerAdmin account'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_profile_force_deletion_admin';
    END IF;

    RETURN OLD;
END;
$$;

CREATE TRIGGER chk_profile_deletion_authorized
    BEFORE DELETE
    ON profile
    FOR EACH ROW
EXECUTE FUNCTION enforce_profile_deletion_authority();

CREATE FUNCTION assert_profile_has_manager(candidate_profile_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF candidate_profile_id IS NULL
        OR NOT EXISTS (SELECT 1 FROM profile WHERE id = candidate_profile_id) THEN
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM profile_manager WHERE profile_id = candidate_profile_id) THEN
        RETURN;
    END IF;

    RAISE EXCEPTION 'Profile % must have at least one manager', candidate_profile_id
        USING ERRCODE = '23514', CONSTRAINT = 'chk_profile_has_manager';
END;
$$;

CREATE FUNCTION enforce_new_profile_manager()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    PERFORM assert_profile_has_manager(NEW.id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_new_profile_has_manager
    AFTER INSERT
    ON profile
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_new_profile_manager();

CREATE FUNCTION enforce_profile_manager_presence()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    profile_ids UUID[] := ARRAY[]::UUID[];
    household_ids UUID[];
    candidate_profile_id UUID;
    candidate_household_id UUID;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        profile_ids := array_append(profile_ids, OLD.profile_id);
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        profile_ids := array_append(profile_ids, NEW.profile_id);
    END IF;

    SELECT COALESCE(array_agg(DISTINCT share.household_id ORDER BY share.household_id),
                    ARRAY[]::UUID[])
    INTO household_ids
    FROM profile_household_share share
    WHERE share.profile_id = ANY (profile_ids)
      AND share.status = 'ACTIVE';

    PERFORM guard_portable_identity(profile_ids, household_ids);

    FOR candidate_profile_id IN
        SELECT DISTINCT id
        FROM unnest(profile_ids) AS ids(id)
        ORDER BY id
    LOOP
        PERFORM assert_profile_has_manager(candidate_profile_id);
    END LOOP;

    FOR candidate_profile_id, candidate_household_id IN
        SELECT DISTINCT share.profile_id, share.household_id
        FROM profile_household_share share
        WHERE share.profile_id = ANY (profile_ids)
          AND share.status = 'ACTIVE'
        ORDER BY share.profile_id, share.household_id
    LOOP
        PERFORM assert_local_kid_manager(candidate_profile_id, candidate_household_id);
    END LOOP;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_profile_has_manager
    AFTER INSERT OR UPDATE OR DELETE
    ON profile_manager
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_profile_manager_presence();

-- Every caller acquires profile guards before household guards, with UUIDs sorted in each group.
CREATE FUNCTION guard_portable_identity(
    candidate_profile_ids UUID[], candidate_household_ids UUID[])
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
DECLARE
    candidate_id UUID;
BEGIN
    FOR candidate_id IN
        SELECT DISTINCT id
        FROM unnest(COALESCE(candidate_profile_ids, ARRAY[]::UUID[])) AS ids(id)
        WHERE id IS NOT NULL
        ORDER BY id
    LOOP
        UPDATE profile
        SET management_version = management_version + 1
        WHERE profile.id = candidate_id;
    END LOOP;

    FOR candidate_id IN
        SELECT DISTINCT id
        FROM unnest(COALESCE(candidate_household_ids, ARRAY[]::UUID[])) AS ids(id)
        WHERE id IS NOT NULL
        ORDER BY id
    LOOP
        UPDATE household
        SET safety_version = safety_version + 1
        WHERE household.id = candidate_id;
    END LOOP;
END;
$$;

CREATE FUNCTION guard_profile_deletion_authorization()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF NEW.mode = 'ORDINARY'
        AND EXISTS (
            SELECT 1 FROM profile_household_share WHERE profile_id = NEW.profile_id) THEN
        RAISE EXCEPTION 'Ordinary profile deletion requires no household shares'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_profile_ordinary_deletion_shares';
    END IF;

    IF NEW.mode = 'ORDINARY'
        AND EXISTS (
            SELECT 1
            FROM profile_manager_invitation
            WHERE profile_id = NEW.profile_id
              AND status = 'PENDING') THEN
        RAISE EXCEPTION 'Ordinary profile deletion requires no pending manager invitations'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_profile_ordinary_deletion_invitations';
    END IF;

    IF NEW.mode = 'ORDINARY'
        AND ((SELECT COUNT(*) FROM profile_manager WHERE profile_id = NEW.profile_id) <> 1
            OR NOT EXISTS (
                SELECT 1
                FROM profile_manager
                WHERE profile_id = NEW.profile_id
                  AND account_id = NEW.acting_account_id)) THEN
        RAISE EXCEPTION 'Ordinary profile deletion requires its sole manager'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_profile_ordinary_deletion_manager';
    END IF;

    PERFORM guard_portable_identity(ARRAY[NEW.profile_id], ARRAY[]::UUID[]);
    RETURN NULL;
END;
$$;

CREATE TRIGGER guard_profile_deletion_authorization
    AFTER INSERT OR UPDATE
    ON profile_deletion_authorization
    FOR EACH ROW
EXECUTE FUNCTION guard_profile_deletion_authorization();

CREATE FUNCTION enforce_profile_manager_invitation_invariants()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    profile_ids UUID[] := ARRAY[]::UUID[];
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        profile_ids := array_append(profile_ids, OLD.profile_id);
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        profile_ids := array_append(profile_ids, NEW.profile_id);
    END IF;

    PERFORM guard_portable_identity(profile_ids, ARRAY[]::UUID[]);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_profile_manager_invitation_invariants
    AFTER INSERT OR UPDATE OR DELETE
    ON profile_manager_invitation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_profile_manager_invitation_invariants();

CREATE FUNCTION assert_local_kid_manager(candidate_profile_id UUID, candidate_household_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM profile_household_share share
        JOIN profile ON profile.id = share.profile_id
        WHERE share.profile_id = candidate_profile_id
          AND share.household_id = candidate_household_id
          AND share.status = 'ACTIVE'
          AND profile.classification = 'KID') THEN
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM profile_manager manager
        JOIN user_account account ON account.id = manager.account_id
        WHERE manager.profile_id = candidate_profile_id
          AND account.enabled
          AND account.home_household_id = candidate_household_id
          AND account.household_role IN ('OWNER', 'PARENT')) THEN
        RETURN;
    END IF;

    RAISE EXCEPTION 'Kid profile % requires a local owner or parent manager in household %',
        candidate_profile_id, candidate_household_id
        USING ERRCODE = '23514', CONSTRAINT = 'chk_kid_profile_local_manager';
END;
$$;

CREATE FUNCTION assert_household_profile_safety(candidate_household_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
DECLARE
    unprotected_profile_id UUID;
BEGIN
    SELECT protected_profile.id
    INTO unprotected_profile_id
    FROM profile_household_share protected_share
    JOIN profile protected_profile ON protected_profile.id = protected_share.profile_id
    WHERE protected_share.household_id = candidate_household_id
      AND protected_share.status = 'ACTIVE'
      AND NULLIF(BTRIM(protected_profile.pin_hash), '') IS NULL
      AND EXISTS (
          SELECT 1
          FROM profile_household_share kid_share
          JOIN profile kid_profile ON kid_profile.id = kid_share.profile_id
          WHERE kid_share.household_id = candidate_household_id
            AND kid_share.status = 'ACTIVE'
            AND kid_profile.classification = 'KID'
            AND (
                protected_profile.classification = 'ADULT'
                OR (
                    protected_profile.classification = 'KID'
                    AND (
                        (protected_profile.maximum_allowed_rating_age IS NULL
                            AND kid_profile.maximum_allowed_rating_age IS NOT NULL)
                        OR protected_profile.maximum_allowed_rating_age
                            > kid_profile.maximum_allowed_rating_age))))
    ORDER BY protected_profile.id
    LIMIT 1;

    IF unprotected_profile_id IS NULL THEN
        RETURN;
    END IF;

    RAISE EXCEPTION 'Profile % requires an effective PIN in household %',
        unprotected_profile_id, candidate_household_id
        USING ERRCODE = '23514', CONSTRAINT = 'chk_household_profile_safety';
END;
$$;

CREATE FUNCTION assert_household_unique_profile_names(candidate_household_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
DECLARE
    duplicate_name TEXT;
BEGIN
    SELECT LOWER(BTRIM(profile.name))
    INTO duplicate_name
    FROM profile_household_share share
    JOIN profile ON profile.id = share.profile_id
    WHERE share.household_id = candidate_household_id
      AND share.status = 'ACTIVE'
    GROUP BY LOWER(BTRIM(profile.name))
    HAVING COUNT(*) > 1
    ORDER BY LOWER(BTRIM(profile.name))
    LIMIT 1;

    IF duplicate_name IS NULL THEN
        RETURN;
    END IF;

    RAISE EXCEPTION 'Active profile name must be unique within household %',
        candidate_household_id
        USING ERRCODE = '23514', CONSTRAINT = 'chk_household_unique_profile_name';
END;
$$;

CREATE FUNCTION enforce_profile_share_invariants()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    profile_ids UUID[] := ARRAY[]::UUID[];
    household_ids UUID[] := ARRAY[]::UUID[];
    candidate_profile_id UUID;
    candidate_household_id UUID;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        profile_ids := array_append(profile_ids, OLD.profile_id);
        household_ids := array_append(household_ids, OLD.household_id);
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        profile_ids := array_append(profile_ids, NEW.profile_id);
        household_ids := array_append(household_ids, NEW.household_id);
    END IF;

    PERFORM guard_portable_identity(profile_ids, household_ids);

    FOR candidate_profile_id, candidate_household_id IN
        SELECT DISTINCT share.profile_id, share.household_id
        FROM profile_household_share share
        WHERE share.profile_id = ANY (profile_ids)
          AND share.household_id = ANY (household_ids)
          AND share.status = 'ACTIVE'
        ORDER BY share.profile_id, share.household_id
    LOOP
        PERFORM assert_local_kid_manager(candidate_profile_id, candidate_household_id);
    END LOOP;

    FOR candidate_household_id IN
        SELECT DISTINCT id
        FROM unnest(household_ids) AS ids(id)
        WHERE id IS NOT NULL
        ORDER BY id
    LOOP
        PERFORM assert_household_profile_safety(candidate_household_id);
        PERFORM assert_household_unique_profile_names(candidate_household_id);
    END LOOP;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_profile_share_invariants
    AFTER INSERT OR UPDATE OR DELETE
    ON profile_household_share
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_profile_share_invariants();

CREATE FUNCTION enforce_profile_policy_invariants()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    household_ids UUID[];
    candidate_household_id UUID;
BEGIN
    SELECT COALESCE(array_agg(share.household_id ORDER BY share.household_id), ARRAY[]::UUID[])
    INTO household_ids
    FROM profile_household_share share
    WHERE share.profile_id = NEW.id
      AND share.status = 'ACTIVE';

    PERFORM guard_portable_identity(ARRAY[NEW.id], household_ids);

    FOR candidate_household_id IN
        SELECT DISTINCT id
        FROM unnest(household_ids) AS ids(id)
        ORDER BY id
    LOOP
        PERFORM assert_local_kid_manager(NEW.id, candidate_household_id);
        PERFORM assert_household_profile_safety(candidate_household_id);
    END LOOP;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_profile_policy_invariants
    AFTER UPDATE OF classification, maximum_allowed_rating_age, pin_hash
    ON profile
    DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_profile_policy_invariants();

CREATE FUNCTION enforce_profile_name_uniqueness()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    household_ids UUID[];
    candidate_household_id UUID;
BEGIN
    SELECT COALESCE(array_agg(share.household_id ORDER BY share.household_id), ARRAY[]::UUID[])
    INTO household_ids
    FROM profile_household_share share
    WHERE share.profile_id = NEW.id
      AND share.status = 'ACTIVE';

    PERFORM guard_portable_identity(ARRAY[]::UUID[], household_ids);

    FOR candidate_household_id IN
        SELECT DISTINCT id
        FROM unnest(household_ids) AS ids(id)
        ORDER BY id
    LOOP
        PERFORM assert_household_unique_profile_names(candidate_household_id);
    END LOOP;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_profile_name_uniqueness
    AFTER UPDATE OF name
    ON profile
    DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_profile_name_uniqueness();

CREATE FUNCTION enforce_account_profile_manager_invariants()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    account_ids UUID[] := ARRAY[]::UUID[];
    profile_ids UUID[];
    household_ids UUID[];
    candidate_profile_id UUID;
    candidate_household_id UUID;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        account_ids := array_append(account_ids, OLD.id);
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        account_ids := array_append(account_ids, NEW.id);
    END IF;

    SELECT COALESCE(array_agg(DISTINCT manager.profile_id ORDER BY manager.profile_id),
                    ARRAY[]::UUID[])
    INTO profile_ids
    FROM profile_manager manager
    WHERE manager.account_id = ANY (account_ids);

    SELECT COALESCE(array_agg(DISTINCT share.household_id ORDER BY share.household_id),
                    ARRAY[]::UUID[])
    INTO household_ids
    FROM profile_household_share share
    WHERE share.profile_id = ANY (profile_ids)
      AND share.status = 'ACTIVE';

    PERFORM guard_portable_identity(profile_ids, household_ids);

    FOR candidate_profile_id, candidate_household_id IN
        SELECT DISTINCT share.profile_id, share.household_id
        FROM profile_household_share share
        WHERE share.profile_id = ANY (profile_ids)
          AND share.status = 'ACTIVE'
        ORDER BY share.profile_id, share.household_id
    LOOP
        PERFORM assert_local_kid_manager(candidate_profile_id, candidate_household_id);
    END LOOP;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_account_profile_manager_invariants
    AFTER INSERT OR UPDATE OR DELETE
    ON user_account
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_account_profile_manager_invariants();
