-- PR #315: a ProfileManager write and a restriction of that manager's Personal Profile previously locked
-- different Household guards. Both deferred T5 checks could therefore validate before either
-- transaction committed. Lock the managed Profile homes and manager Account homes together so
-- the second validator re-queries after the first conflicting transaction finishes.
CREATE OR REPLACE FUNCTION enforce_profile_manager_invariants()
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
    SELECT array_agg(DISTINCT household_id)
    INTO homes
    FROM (SELECT household_id FROM profile WHERE id = ANY (profiles)
          UNION
          SELECT household_id FROM user_account WHERE id = ANY (accounts)) affected;
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
