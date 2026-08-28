-- Validate after V061 commits so PostgreSQL does not retain the initial ALTER TABLE's
-- ACCESS EXCLUSIVE lock while scanning existing invitation rows.
ALTER TABLE account_invitation
    VALIDATE CONSTRAINT fk_account_invitation_profile,
    VALIDATE CONSTRAINT chk_account_invitation_link_names_profile;
