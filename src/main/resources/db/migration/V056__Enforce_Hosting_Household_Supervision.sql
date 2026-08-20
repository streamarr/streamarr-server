-- Profile sharing (ADR 0024 §Restricted Profile supervision; server PR #10).
-- T7: a Household hosting a restricted Profile always holds an eligible HouseholdAdmin — the
-- supervision is a fact about the target Household, not about who clicked accept. Activation
-- checks it, and demotion, deletion, or restriction that would remove the last eligible admin of
-- a hosting Household is rejected while such a share is active.

ALTER TYPE profile_share_status ADD VALUE IF NOT EXISTS 'INVALIDATED' BEFORE 'ENDED';

ALTER TABLE profile_household_share
    ADD COLUMN invalidation_reason TEXT;

-- T7: every hosting Household of a restricted Profile keeps an eligible HouseholdAdmin.
CREATE FUNCTION assert_restricted_shares_supervised(candidate_household_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF candidate_household_id IS NULL THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1
                   FROM profile_household_share s
                            JOIN profile p ON p.id = s.profile_id
                   WHERE s.household_id = candidate_household_id
                     AND s.status = 'ACTIVE'
                     AND (p.kind = 'KID' OR p.maximum_allowed_rating_age IS NOT NULL)) THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1
                   FROM user_account ua
                   WHERE ua.household_id = candidate_household_id
                     AND ua.household_role = 'ADMIN'
                     AND account_is_eligible(ua.id)) THEN
        RAISE EXCEPTION 'Household % hosts a restricted Profile and must retain an eligible HouseholdAdmin', candidate_household_id
            USING ERRCODE = '23514', CONSTRAINT = 'chk_hosting_household_retains_eligible_admin';
    END IF;
END;
$$;

-- The share trigger re-checks supervision for both touched Households.
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
        END IF;
    END IF;
    FOREACH hid IN ARRAY households LOOP
        PERFORM assert_household_profile_names_unique(hid);
        PERFORM assert_restricted_shares_supervised(hid);
    END LOOP;
    RETURN NULL;
END;
$$;

-- Account changes (demotion, deletion, restriction of eligibility) re-check T7 for the Household.
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
    END LOOP;
    FOREACH pid IN ARRAY profiles LOOP
        PERFORM assert_profile_home_anchor(pid);
    END LOOP;
    PERFORM assert_enabled_server_admin_remains();
    RETURN NULL;
END;
$$;

-- Restricting a Profile re-checks supervision in every Household where it is available, and
-- restricting an admin's Personal Profile re-checks the Households that admin supervises.
CREATE OR REPLACE FUNCTION enforce_profile_invariants()
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
            -- The linked person may be the eligible admin some hosting Household relies on.
            PERFORM assert_restricted_shares_supervised(
                    (SELECT household_id FROM user_account WHERE id = linked));
        END IF;
        FOREACH hid IN ARRAY households LOOP
            PERFORM assert_household_profile_names_unique(hid);
            PERFORM assert_restricted_shares_supervised(hid);
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$;
