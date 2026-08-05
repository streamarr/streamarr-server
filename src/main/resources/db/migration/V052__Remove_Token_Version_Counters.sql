ALTER TABLE auth_session DROP COLUMN session_version;
ALTER TABLE household_membership DROP COLUMN membership_version;
ALTER TABLE profile DROP COLUMN policy_version;
DROP SEQUENCE household_membership_version_seq;
