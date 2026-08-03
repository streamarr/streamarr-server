ALTER TABLE refresh_token
    ADD COLUMN predecessor_id UUID;

-- SET NULL, never CASCADE: deleting a predecessor must never delete the active successor that
-- replaced it. Losing the link degrades a recoverable retry to the same terminal 401 an unknown
-- token gets; cascading would revoke a live credential.
ALTER TABLE refresh_token
    ADD CONSTRAINT fk_refresh_token_predecessor FOREIGN KEY (predecessor_id)
        REFERENCES refresh_token (id) ON DELETE SET NULL;

-- At most one successor per predecessor — the invariant that makes an exact {predecessor,
-- proposal} pair unambiguous. Server-generated tokens (first issuance, cookie rotation) carry
-- NULL, and Postgres allows unlimited NULLs under a UNIQUE constraint.
ALTER TABLE refresh_token
    ADD CONSTRAINT uq_refresh_token_predecessor UNIQUE (predecessor_id);
