-- How long an Account invitation, a ProfileManager invitation, or an offer of a Profile waits for
-- its answer after it is made (ADR 0024 §Invitations): seven days by default, between one hour and
-- thirty days. A ServerAdmin sets it; a change applies to what is issued after it.
ALTER TABLE server_settings
    ADD COLUMN invitation_lifetime_hours INTEGER NOT NULL DEFAULT 168,
    ADD CONSTRAINT chk_server_settings_invitation_lifetime_hours
        CHECK (invitation_lifetime_hours BETWEEN 1 AND 720);
