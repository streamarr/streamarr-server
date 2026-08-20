-- Devices (ADR 0024 §Devices; server PR #13). ADR 0021's pairing transport contract is unchanged:
-- codes, budgets, poll cadence, and expiry stay as they are. This adds what ADR 0024 binds to the
-- pairing: the TV's ESN, the chosen Household, the durable registration the winning poll creates,
-- and ESN blocks. T9: a registered Device's authorizing Account may still use its Household and
-- the ESN is not blocked. T10: an ESN block leaves no matching registered Device or refreshable
-- device session.

ALTER TABLE device_authorization
    ADD COLUMN esn                 TEXT,
    ADD COLUMN chosen_household_id UUID,
    ADD CONSTRAINT fk_device_authorization_chosen_household FOREIGN KEY (chosen_household_id)
        REFERENCES household (id) ON DELETE SET NULL;

CREATE TYPE device_registration_status AS ENUM ('ACTIVE', 'REVOKED');

CREATE TABLE device_registration
(
    id                    UUID                       NOT NULL DEFAULT gen_random_uuid(),
    created_on            TIMESTAMP WITH TIME ZONE   NOT NULL DEFAULT NOW(),
    created_by            UUID,
    last_modified_on      TIMESTAMP WITH TIME ZONE   NOT NULL DEFAULT NOW(),
    last_modified_by      UUID,
    esn                   TEXT                       NOT NULL,
    display_name          TEXT                       NOT NULL,
    household_id          UUID,
    authorizing_account_id UUID,
    authorization_id      UUID,
    status                device_registration_status NOT NULL DEFAULT 'ACTIVE',
    revoked_at            TIMESTAMP WITH TIME ZONE,
    revoked_by_account_id UUID,
    revocation_reason     TEXT,
    last_used_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT device_registration_pkey PRIMARY KEY (id),
    CONSTRAINT fk_device_registration_household FOREIGN KEY (household_id)
        REFERENCES household (id) ON DELETE SET NULL,
    CONSTRAINT fk_device_registration_account FOREIGN KEY (authorizing_account_id)
        REFERENCES user_account (id) ON DELETE SET NULL,
    CONSTRAINT fk_device_registration_authorization FOREIGN KEY (authorization_id)
        REFERENCES device_authorization (id) ON DELETE SET NULL,
    CONSTRAINT fk_device_registration_revoked_by FOREIGN KEY (revoked_by_account_id)
        REFERENCES user_account (id) ON DELETE SET NULL
);

-- One TV, one live Household context.
CREATE UNIQUE INDEX uq_device_registration_live ON device_registration (esn)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_device_registration_household ON device_registration (household_id);
CREATE INDEX idx_device_registration_account ON device_registration (authorizing_account_id);

CREATE TABLE esn_block
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    esn              TEXT                     NOT NULL,
    -- NULL scopes the block server-wide.
    household_id     UUID,
    reason           TEXT                     NOT NULL,
    CONSTRAINT esn_block_pkey PRIMARY KEY (id),
    CONSTRAINT fk_esn_block_household FOREIGN KEY (household_id)
        REFERENCES household (id) ON DELETE CASCADE,
    CONSTRAINT uq_esn_block_scope UNIQUE NULLS NOT DISTINCT (esn, household_id)
);

ALTER TABLE auth_session
    ADD COLUMN registration_id UUID,
    ADD CONSTRAINT fk_auth_session_registration FOREIGN KEY (registration_id)
        REFERENCES device_registration (id) ON DELETE SET NULL;
CREATE INDEX idx_auth_session_registration ON auth_session (registration_id);

-- Whether the ESN is blocked for that Household (its own block or a server-wide one).
CREATE FUNCTION esn_is_blocked(candidate_esn TEXT, candidate_household_id UUID)
    RETURNS BOOLEAN
    LANGUAGE sql
    STABLE
AS
$$
SELECT EXISTS (SELECT 1
               FROM esn_block b
               WHERE b.esn = candidate_esn
                 AND (b.household_id IS NULL OR b.household_id = candidate_household_id))
$$;

-- T9: every ACTIVE registration keeps a live path — its authorizing Account enabled and still
-- able to use the registered Household (member, or visitor via an active Personal Profile
-- share), and its ESN unblocked there.
CREATE FUNCTION assert_device_registrations_supported(candidate_account_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
DECLARE
    registration RECORD;
BEGIN
    IF candidate_account_id IS NULL THEN
        RETURN;
    END IF;
    FOR registration IN SELECT r.id, r.esn, r.household_id
                        FROM device_registration r
                        WHERE r.authorizing_account_id = candidate_account_id
                          AND r.status = 'ACTIVE'
        LOOP
            IF registration.household_id IS NULL THEN
                RAISE EXCEPTION 'Registration % lost its Household', registration.id
                    USING ERRCODE = '23514', CONSTRAINT = 'chk_device_registration_supported';
            END IF;
            IF NOT EXISTS (SELECT 1
                           FROM user_account ua
                           WHERE ua.id = candidate_account_id
                             AND ua.enabled
                             AND (ua.household_id = registration.household_id
                                 OR EXISTS (SELECT 1
                                            FROM profile_household_share s
                                            WHERE s.profile_id = ua.personal_profile_id
                                              AND s.household_id = registration.household_id
                                              AND s.status = 'ACTIVE'))) THEN
                RAISE EXCEPTION 'Registration % lost its authorizing Account''s Household access', registration.id
                    USING ERRCODE = '23514', CONSTRAINT = 'chk_device_registration_supported';
            END IF;
            IF esn_is_blocked(registration.esn, registration.household_id) THEN
                RAISE EXCEPTION 'Registration % uses a blocked ESN', registration.id
                    USING ERRCODE = '23514', CONSTRAINT = 'chk_device_registration_supported';
            END IF;
        END LOOP;
END;
$$;

-- T10: a block admits no matching ACTIVE registration and no refreshable device session.
CREATE FUNCTION enforce_esn_block_invariants()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF EXISTS (SELECT 1
               FROM device_registration r
               WHERE r.esn = NEW.esn
                 AND r.status = 'ACTIVE'
                 AND (NEW.household_id IS NULL OR r.household_id = NEW.household_id)) THEN
        RAISE EXCEPTION 'ESN block on % leaves an active registration', NEW.esn
            USING ERRCODE = '23514', CONSTRAINT = 'chk_esn_block_leaves_no_device';
    END IF;
    IF EXISTS (SELECT 1
               FROM auth_session s
                        JOIN device_registration r ON r.id = s.registration_id
               WHERE r.esn = NEW.esn
                 AND (NEW.household_id IS NULL OR r.household_id = NEW.household_id)
                 AND s.revoked_at IS NULL) THEN
        RAISE EXCEPTION 'ESN block on % leaves a refreshable device session', NEW.esn
            USING ERRCODE = '23514', CONSTRAINT = 'chk_esn_block_leaves_no_device';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_esn_block_invariants
    AFTER INSERT OR UPDATE
    ON esn_block
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_esn_block_invariants();

-- The registration trigger re-checks T9 for the touched authorizing Account.
CREATE FUNCTION enforce_device_registration_invariants()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP IN ('INSERT', 'UPDATE') AND NEW.status = 'ACTIVE' THEN
        PERFORM assert_device_registrations_supported(NEW.authorizing_account_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_device_registration_invariants
    AFTER INSERT OR UPDATE
    ON device_registration
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION enforce_device_registration_invariants();

-- Account changes (disable, demotion, deletion) re-check T9: the disable/transfer paths revoke
-- affected registrations in the same transaction, and this refuses any path that forgot.
CREATE OR REPLACE FUNCTION enforce_user_account_invariants()
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
        PERFORM assert_restricted_shares_supervised(hid);
    END LOOP;
    FOREACH aid IN ARRAY accounts LOOP
        PERFORM assert_personal_profile_structurally_shared(aid);
        PERFORM assert_restricted_account_holds_no_authority(aid);
        PERFORM assert_device_registrations_supported(aid);
    END LOOP;
    FOREACH pid IN ARRAY profiles LOOP
        PERFORM assert_profile_home_anchor(pid);
    END LOOP;
    PERFORM assert_enabled_server_admin_remains();
    RETURN NULL;
END;
$$;

-- Ending a visit re-checks T9 for the visiting Account: the unshare path revokes that
-- Household's registrations authorized through the ended share, and this refuses a miss.
CREATE OR REPLACE FUNCTION enforce_profile_household_share_invariants()
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
            PERFORM assert_device_registrations_supported(linked);
        END IF;
    END IF;
    FOREACH hid IN ARRAY households LOOP
        PERFORM assert_household_profile_names_unique(hid);
        PERFORM assert_restricted_shares_supervised(hid);
    END LOOP;
    RETURN NULL;
END;
$$;
