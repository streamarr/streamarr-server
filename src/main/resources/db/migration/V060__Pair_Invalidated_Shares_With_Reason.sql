-- Profile sharing (server PR #313): an INVALIDATED share always records why, and only then — the
-- pairing account_invitation and password_reset_code already enforce. Separate from V059 because
-- PostgreSQL refuses to reference an enum label in the transaction that added it.

ALTER TABLE profile_household_share
    ADD CONSTRAINT chk_profile_household_share_invalidation_reason
        CHECK ((status = 'INVALIDATED') = (invalidation_reason IS NOT NULL));
