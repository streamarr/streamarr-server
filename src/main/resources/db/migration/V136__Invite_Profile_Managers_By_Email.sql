-- A ProfileManager invites by email address (ADR 0024 §Invitations). An address that no Account can
-- answer for still gets a pending invitation, which names no Account and which nobody can accept,
-- so a pending invitation no longer needs a named Account. Only the newest invitation to one
-- address may wait for a Profile, whatever the address's case.
SELECT set_config('streamarr.actor_id', 'b634c755-3c6a-4ff1-a0a7-3e812d0ed1b9', true);

-- While invitations named Accounts, two could wait for one Profile under one address only through
-- addresses that differ in case; keep the newest so the index below can be built.
UPDATE profile_manager_invitation older
SET status              = 'INVALIDATED',
    invalidation_reason = 'replaced by a newer invitation',
    decided_at          = NOW()
WHERE older.status = 'PENDING'
  AND EXISTS (SELECT 1
              FROM profile_manager_invitation newer
              WHERE newer.status = 'PENDING'
                AND newer.profile_id = older.profile_id
                AND LOWER(newer.recipient_email) = LOWER(older.recipient_email)
                AND (newer.created_on, newer.id) > (older.created_on, older.id));

ALTER TABLE profile_manager_invitation
    DROP CONSTRAINT chk_pm_invitation_pending_has_parties;

ALTER TABLE profile_manager_invitation
    ADD CONSTRAINT chk_pm_invitation_pending_has_parties CHECK (
        status <> 'PENDING'
            OR (profile_id IS NOT NULL AND inviter_account_id IS NOT NULL)
        );

CREATE UNIQUE INDEX uq_pm_invitation_pending_email
    ON profile_manager_invitation (profile_id, LOWER(recipient_email))
    WHERE status = 'PENDING';

-- The expiry sweep finds the pending invitations whose time ran out.
CREATE INDEX idx_pm_invitation_pending_expiry
    ON profile_manager_invitation (expires_at)
    WHERE status = 'PENDING';
