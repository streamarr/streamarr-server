-- ADR 0024 rebuilds the identity model around relationships. Streamarr has no production data,
-- so the pre-ADR identity rows are reset here in one forward migration (checksums of V044/V045
-- stay intact). CASCADE clears dependent state: auth_session, refresh_token, session_progress,
-- watch_history, device_authorization.
TRUNCATE TABLE user_account, household, profile CASCADE;

-- ---------------------------------------------------------------------------------------------
-- Retire the old model: memberships, per-account profile grants, Household scope, account roles.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE auth_session
    DROP CONSTRAINT fk_auth_session_active_account_profile,
    DROP CONSTRAINT fk_auth_session_active_membership,
    DROP CONSTRAINT fk_auth_session_active_profile_household,
    DROP CONSTRAINT fk_auth_session_active_household;
ALTER TABLE auth_session DROP COLUMN active_household_id;

DROP TABLE account_profile;
DROP TABLE household_membership;
DROP SEQUENCE IF EXISTS household_membership_version_seq;

ALTER TABLE user_account DROP COLUMN account_role;
DROP TYPE account_role;

DROP TYPE household_role;
CREATE TYPE household_role AS ENUM ('ADMIN', 'MEMBER');
CREATE TYPE profile_kind AS ENUM ('KID', 'ADULT');
CREATE TYPE profile_share_status AS ENUM ('PENDING', 'ACTIVE', 'REJECTED', 'CANCELED', 'EXPIRED', 'ENDED');

-- ---------------------------------------------------------------------------------------------
-- Profile: a portable viewing identity that belongs to exactly one Household.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE profile
    DROP CONSTRAINT uq_profile_household_name,
    ADD COLUMN kind profile_kind NOT NULL DEFAULT 'ADULT',
    ADD COLUMN maximum_allowed_rating_age INTEGER,
    ADD COLUMN pin_hash TEXT,
    ADD COLUMN picture TEXT,
    ADD COLUMN restricted BOOLEAN GENERATED ALWAYS AS (kind = 'KID' OR maximum_allowed_rating_age IS NOT NULL) STORED,
    ADD CONSTRAINT chk_profile_maximum_allowed_rating_age
        CHECK (maximum_allowed_rating_age IS NULL OR maximum_allowed_rating_age >= 0),
    -- The effective-PIN rule: a PIN exists only when the hash is non-null and non-blank.
    ADD CONSTRAINT chk_profile_pin_hash_not_blank
        CHECK (pin_hash IS NULL OR btrim(pin_hash) <> ''),
    ADD CONSTRAINT chk_profile_name_not_blank CHECK (btrim(name) <> '');

-- ---------------------------------------------------------------------------------------------
-- Account: one membership Household with a role, optional ServerAdmin, one Personal Profile.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE user_account
    ADD COLUMN server_admin        BOOLEAN        NOT NULL DEFAULT FALSE,
    ADD COLUMN household_id        UUID           NOT NULL,
    ADD COLUMN household_role      household_role NOT NULL DEFAULT 'MEMBER',
    ADD COLUMN personal_profile_id UUID           NOT NULL,
    ADD CONSTRAINT fk_user_account_household FOREIGN KEY (household_id) REFERENCES household (id),
    ADD CONSTRAINT fk_user_account_personal_profile FOREIGN KEY (personal_profile_id) REFERENCES profile (id),
    ADD CONSTRAINT uq_user_account_personal_profile UNIQUE (personal_profile_id);

CREATE INDEX idx_user_account_household_id ON user_account (household_id);

-- ---------------------------------------------------------------------------------------------
-- Relationships: direct managers and shares.
-- ---------------------------------------------------------------------------------------------
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

CREATE TABLE profile_household_share
(
    id                    UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by            UUID,
    last_modified_on      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by      UUID,
    profile_id            UUID                     NOT NULL,
    household_id          UUID                     NOT NULL,
    status                profile_share_status     NOT NULL,
    -- The Personal Profile's share into its Account's own Household: created with the Account,
    -- never ended while the Account remains a member (T3).
    structural            BOOLEAN                  NOT NULL DEFAULT FALSE,
    offered_by_account_id UUID,
    expires_at            TIMESTAMP WITH TIME ZONE,
    decided_at            TIMESTAMP WITH TIME ZONE,
    ended_at              TIMESTAMP WITH TIME ZONE,
    CONSTRAINT profile_household_share_pkey PRIMARY KEY (id),
    CONSTRAINT fk_profile_household_share_profile FOREIGN KEY (profile_id)
        REFERENCES profile (id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_household_share_household FOREIGN KEY (household_id)
        REFERENCES household (id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_household_share_offered_by FOREIGN KEY (offered_by_account_id)
        REFERENCES user_account (id) ON DELETE SET NULL,
    CONSTRAINT chk_profile_household_share_structural_status
        CHECK (NOT structural OR status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT chk_profile_household_share_ended_at
        CHECK ((status = 'ENDED') = (ended_at IS NOT NULL))
);

-- At most one live (pending or active) share per Profile and Household.
CREATE UNIQUE INDEX uq_profile_household_share_live
    ON profile_household_share (profile_id, household_id)
    WHERE status IN ('PENDING', 'ACTIVE');
CREATE INDEX idx_profile_household_share_household_status
    ON profile_household_share (household_id, status);

-- ---------------------------------------------------------------------------------------------
-- Household guard: one row per Household, locked in ascending household_id order by every
-- multi-Household write and bumped by the deferred invariant triggers before they re-query.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE household_guard
(
    household_id UUID   NOT NULL,
    version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT household_guard_pkey PRIMARY KEY (household_id),
    CONSTRAINT fk_household_guard_household FOREIGN KEY (household_id)
        REFERENCES household (id) ON DELETE CASCADE
);

CREATE FUNCTION create_household_guard()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    INSERT INTO household_guard (household_id) VALUES (NEW.id);
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_household_guard_create
    AFTER INSERT
    ON household
    FOR EACH ROW
EXECUTE FUNCTION create_household_guard();

-- Locks the guard rows in ascending PostgreSQL uuid order, then bumps them. Callers re-query
-- the relationships AFTER this returns; a snapshot taken before the guard is never validated.
CREATE FUNCTION bump_household_guards(household_ids UUID[])
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF household_ids IS NULL OR cardinality(household_ids) = 0 THEN
        RETURN;
    END IF;
    PERFORM household_id
    FROM household_guard
    WHERE household_id = ANY (household_ids)
    ORDER BY household_id
        FOR UPDATE;
    UPDATE household_guard SET version = version + 1 WHERE household_id = ANY (household_ids);
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- Security audit: actor, operation, resources, outcome, reason, time. No foreign keys: an audit
-- row outlives the rows it describes and never carries a secret.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE security_audit_event
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    occurred_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    actor_account_id UUID,
    operation        TEXT                     NOT NULL,
    outcome          TEXT                     NOT NULL,
    reason           TEXT,
    resources        JSONB                    NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT security_audit_event_pkey PRIMARY KEY (id),
    CONSTRAINT chk_security_audit_event_operation_not_blank CHECK (btrim(operation) <> ''),
    CONSTRAINT chk_security_audit_event_outcome_not_blank CHECK (btrim(outcome) <> '')
);

CREATE INDEX idx_security_audit_event_occurred_at ON security_audit_event (occurred_at DESC, id DESC);

-- ---------------------------------------------------------------------------------------------
-- Sessions: one context Household (membership by default, a visited Household, or later the
-- Device Household) and the selected Profile.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE auth_session
    RENAME COLUMN active_profile_id TO selected_profile_id;
ALTER INDEX idx_auth_session_active_profile_id RENAME TO idx_auth_session_selected_profile_id;
ALTER TABLE auth_session
    ADD COLUMN context_household_id UUID,
    ADD CONSTRAINT fk_auth_session_context_household FOREIGN KEY (context_household_id)
        REFERENCES household (id) ON DELETE SET NULL;
CREATE INDEX idx_auth_session_context_household_id ON auth_session (context_household_id);

-- Bootstrap records the first claim; the claiming Account may later be deleted (teardown,
-- transfer disposition) without blocking on this row.
ALTER TABLE server_bootstrap
    DROP CONSTRAINT fk_server_bootstrap_admin_account,
    ALTER COLUMN admin_account_id DROP NOT NULL,
    ADD CONSTRAINT fk_server_bootstrap_admin_account FOREIGN KEY (admin_account_id)
        REFERENCES user_account (id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------------------------
-- Invariants (deferred, evaluated at commit after bumping the affected Household guards).
-- Every violation raises SQLSTATE 23514 with a stable constraint name.
-- ---------------------------------------------------------------------------------------------

-- An Account is eligible for authority when its Personal Profile is an unrestricted Adult.
CREATE FUNCTION account_is_eligible(candidate_account_id UUID)
    RETURNS BOOLEAN
    LANGUAGE sql
    STABLE
AS
$$
SELECT EXISTS (SELECT 1
               FROM user_account ua
                        JOIN profile p ON p.id = ua.personal_profile_id
               WHERE ua.id = candidate_account_id
                 AND NOT p.restricted);
$$;

-- T1: after its first Account, a Household keeps at least one Account and one HouseholdAdmin.
CREATE FUNCTION assert_household_retains_admin(candidate_household_id UUID, had_account BOOLEAN)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
DECLARE
    account_count INTEGER;
    admin_count   INTEGER;
BEGIN
    IF candidate_household_id IS NULL
        OR NOT EXISTS (SELECT 1 FROM household WHERE id = candidate_household_id) THEN
        RETURN;
    END IF;
    SELECT COUNT(*), COUNT(*) FILTER (WHERE household_role = 'ADMIN')
    INTO account_count, admin_count
    FROM user_account
    WHERE household_id = candidate_household_id;
    IF account_count = 0 AND had_account THEN
        RAISE EXCEPTION 'Household % must retain its final Account until teardown', candidate_household_id
            USING ERRCODE = '23514', CONSTRAINT = 'chk_household_retains_account';
    END IF;
    IF account_count > 0 AND admin_count = 0 THEN
        RAISE EXCEPTION 'Household % must retain a HouseholdAdmin', candidate_household_id
            USING ERRCODE = '23514', CONSTRAINT = 'chk_household_retains_admin';
    END IF;
END;
$$;

-- T2: an Account's Personal Profile is actively and structurally shared into the Account's Household.
CREATE FUNCTION assert_personal_profile_structurally_shared(candidate_account_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
DECLARE
    acct user_account%ROWTYPE;
BEGIN
    SELECT * INTO acct FROM user_account WHERE id = candidate_account_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1
                   FROM profile_household_share s
                   WHERE s.profile_id = acct.personal_profile_id
                     AND s.household_id = acct.household_id
                     AND s.status = 'ACTIVE'
                     AND s.structural) THEN
        RAISE EXCEPTION 'Account % must keep its Personal Profile structurally shared into its Household', candidate_account_id
            USING ERRCODE = '23514', CONSTRAINT = 'chk_personal_profile_structural_share';
    END IF;
    IF EXISTS (SELECT 1 FROM profile p WHERE p.id = acct.personal_profile_id AND p.household_id <> acct.household_id) THEN
        RAISE EXCEPTION 'Account % and its Personal Profile must belong to the same Household', candidate_account_id
            USING ERRCODE = '23514', CONSTRAINT = 'chk_personal_profile_same_household';
    END IF;
END;
$$;

-- T4: after bootstrap, at least one enabled ServerAdmin remains.
CREATE FUNCTION assert_enabled_server_admin_remains()
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
BEGIN
    -- T4 is server-global: serialize its cross-Household check on the singleton bootstrap row.
    -- The following statement gets a fresh READ COMMITTED snapshot after a competing writer exits.
    PERFORM id FROM server_bootstrap FOR UPDATE;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM user_account WHERE server_admin AND enabled) THEN
        RAISE EXCEPTION 'At least one enabled ServerAdmin must remain'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_enabled_server_admin_remains';
    END IF;
END;
$$;

-- T5: a restricted Personal Profile carries no authority.
CREATE FUNCTION assert_restricted_account_holds_no_authority(candidate_account_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
DECLARE
    acct user_account%ROWTYPE;
BEGIN
    SELECT * INTO acct FROM user_account WHERE id = candidate_account_id;
    IF NOT FOUND OR account_is_eligible(candidate_account_id) THEN
        RETURN;
    END IF;
    IF acct.household_role = 'ADMIN' OR acct.server_admin
        OR EXISTS (SELECT 1 FROM profile_manager WHERE account_id = candidate_account_id) THEN
        RAISE EXCEPTION 'Account % has a restricted Personal Profile and cannot hold authority', candidate_account_id
            USING ERRCODE = '23514', CONSTRAINT = 'chk_restricted_account_holds_no_authority';
    END IF;
END;
$$;

-- T6: every Profile keeps its home anchor in the Household it belongs to.
CREATE FUNCTION assert_profile_home_anchor(candidate_profile_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
DECLARE
    prof              profile%ROWTYPE;
    linked_account    user_account%ROWTYPE;
    anchored          BOOLEAN;
BEGIN
    SELECT * INTO prof FROM profile WHERE id = candidate_profile_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT * INTO linked_account FROM user_account WHERE personal_profile_id = prof.id;
    IF FOUND AND NOT prof.restricted THEN
        -- An unrestricted Adult Personal Profile is anchored by its own Account at home.
        IF linked_account.household_id <> prof.household_id THEN
            RAISE EXCEPTION 'Profile % must belong to its Account''s Household', candidate_profile_id
                USING ERRCODE = '23514', CONSTRAINT = 'chk_profile_home_anchor';
        END IF;
        RETURN;
    END IF;
    IF prof.restricted THEN
        SELECT EXISTS (SELECT 1
                       FROM profile_manager pm
                                JOIN user_account ua ON ua.id = pm.account_id
                       WHERE pm.profile_id = prof.id
                         AND ua.household_id = prof.household_id
                         AND ua.household_role = 'ADMIN'
                         AND account_is_eligible(ua.id))
        INTO anchored;
    ELSE
        SELECT EXISTS (SELECT 1
                       FROM profile_manager pm
                                JOIN user_account ua ON ua.id = pm.account_id
                       WHERE pm.profile_id = prof.id
                         AND ua.household_id = prof.household_id
                         AND account_is_eligible(ua.id))
        INTO anchored;
    END IF;
    IF NOT anchored THEN
        RAISE EXCEPTION 'Profile % must keep an eligible home anchor in its Household', candidate_profile_id
            USING ERRCODE = '23514', CONSTRAINT = 'chk_profile_home_anchor';
    END IF;
END;
$$;

-- T8: Profiles actively available in one Household have distinct names, ignoring case.
CREATE FUNCTION assert_household_profile_names_unique(candidate_household_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF EXISTS (SELECT lower(p.name)
               FROM profile_household_share s
                        JOIN profile p ON p.id = s.profile_id
               WHERE s.household_id = candidate_household_id
                 AND s.status = 'ACTIVE'
               GROUP BY lower(p.name)
               HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION 'Household % has two available Profiles with the same name', candidate_household_id
            USING ERRCODE = '23514', CONSTRAINT = 'chk_household_profile_names_unique';
    END IF;
END;
$$;

-- Trigger bodies: gather affected ids from OLD/NEW, bump the guards, then re-query.

CREATE FUNCTION enforce_user_account_invariants()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    households UUID[] := ARRAY []::UUID[];
    accounts   UUID[] := ARRAY []::UUID[];
    profiles   UUID[] := ARRAY []::UUID[];
    hid        UUID;
    aid        UUID;
    pid        UUID;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        households := array_append(households, OLD.household_id);
        accounts := array_append(accounts, OLD.id);
        profiles := array_append(profiles, OLD.personal_profile_id);
        SELECT array_agg(profile_id) INTO profiles FROM (SELECT unnest(profiles) AS profile_id UNION SELECT profile_id FROM profile_manager WHERE account_id = OLD.id) managed;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        households := array_append(households, NEW.household_id);
        accounts := array_append(accounts, NEW.id);
        profiles := array_append(profiles, NEW.personal_profile_id);
        SELECT array_agg(profile_id) INTO profiles FROM (SELECT unnest(profiles) AS profile_id UNION SELECT profile_id FROM profile_manager WHERE account_id = NEW.id) managed;
    END IF;
    PERFORM bump_household_guards(households);
    FOREACH hid IN ARRAY households LOOP
        PERFORM assert_household_retains_admin(hid, TG_OP IN ('UPDATE', 'DELETE'));
    END LOOP;
    FOREACH aid IN ARRAY accounts LOOP
        PERFORM assert_personal_profile_structurally_shared(aid);
        PERFORM assert_restricted_account_holds_no_authority(aid);
    END LOOP;
    FOREACH pid IN ARRAY profiles LOOP
        PERFORM assert_profile_home_anchor(pid);
    END LOOP;
    PERFORM assert_enabled_server_admin_remains();
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_user_account_invariants
    AFTER INSERT OR UPDATE OR DELETE
    ON user_account
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_user_account_invariants();

CREATE FUNCTION enforce_profile_invariants()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    households UUID[] := ARRAY []::UUID[];
    hid        UUID;
    linked     UUID;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        households := array_append(households, OLD.household_id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        households := array_append(households, NEW.household_id);
        SELECT array_agg(DISTINCT household_id) INTO households
        FROM (SELECT unnest(households) AS household_id
              UNION
              SELECT household_id FROM profile_household_share WHERE profile_id = NEW.id AND status = 'ACTIVE') affected;
    END IF;
    PERFORM bump_household_guards(households);
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM assert_profile_home_anchor(NEW.id);
        SELECT id INTO linked FROM user_account WHERE personal_profile_id = NEW.id;
        IF FOUND THEN
            PERFORM assert_restricted_account_holds_no_authority(linked);
            PERFORM assert_personal_profile_structurally_shared(linked);
        END IF;
        FOREACH hid IN ARRAY households LOOP
            PERFORM assert_household_profile_names_unique(hid);
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_profile_invariants
    AFTER INSERT OR UPDATE OR DELETE
    ON profile
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_profile_invariants();

CREATE FUNCTION enforce_profile_manager_invariants()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    profiles UUID[] := ARRAY []::UUID[];
    accounts UUID[] := ARRAY []::UUID[];
    homes    UUID[];
    pid      UUID;
    aid      UUID;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        profiles := array_append(profiles, OLD.profile_id);
        accounts := array_append(accounts, OLD.account_id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        profiles := array_append(profiles, NEW.profile_id);
        accounts := array_append(accounts, NEW.account_id);
    END IF;
    SELECT array_agg(DISTINCT household_id) INTO homes FROM profile WHERE id = ANY (profiles);
    PERFORM bump_household_guards(homes);
    FOREACH pid IN ARRAY profiles LOOP
        PERFORM assert_profile_home_anchor(pid);
    END LOOP;
    FOREACH aid IN ARRAY accounts LOOP
        PERFORM assert_restricted_account_holds_no_authority(aid);
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_profile_manager_invariants
    AFTER INSERT OR UPDATE OR DELETE
    ON profile_manager
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_profile_manager_invariants();

-- T3 plus the share-side of T2 and T8.
CREATE FUNCTION enforce_profile_household_share_invariants()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    households UUID[] := ARRAY []::UUID[];
    hid        UUID;
    linked     UUID;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        households := array_append(households, OLD.household_id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        households := array_append(households, NEW.household_id);
    END IF;
    PERFORM bump_household_guards(households);
    IF TG_OP IN ('UPDATE', 'DELETE') AND OLD.structural AND OLD.status = 'ACTIVE'
        AND (TG_OP = 'DELETE' OR NEW.status <> 'ACTIVE') THEN
        IF EXISTS (SELECT 1
                   FROM user_account
                   WHERE personal_profile_id = OLD.profile_id
                     AND household_id = OLD.household_id) THEN
            RAISE EXCEPTION 'Structural share of Profile % into Household % cannot end while the Account remains a member', OLD.profile_id, OLD.household_id
                USING ERRCODE = '23514', CONSTRAINT = 'chk_structural_share_persists';
        END IF;
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT id INTO linked FROM user_account WHERE personal_profile_id = OLD.profile_id;
        IF FOUND THEN
            PERFORM assert_personal_profile_structurally_shared(linked);
        END IF;
    END IF;
    FOREACH hid IN ARRAY households LOOP
        PERFORM assert_household_profile_names_unique(hid);
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER chk_profile_household_share_invariants
    AFTER INSERT OR UPDATE OR DELETE
    ON profile_household_share
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_profile_household_share_invariants();
