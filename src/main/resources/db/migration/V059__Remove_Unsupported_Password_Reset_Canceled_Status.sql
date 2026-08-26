-- Password-reset codes have no cancellation operation. Preserve any externally written legacy
-- value as an explicit invalidation before narrowing the enum to real domain transitions.

UPDATE password_reset_code
SET status = 'INVALIDATED',
    redeemed_at = COALESCE(redeemed_at, NOW()),
    invalidation_reason = COALESCE(invalidation_reason, 'unsupported legacy cancellation status'),
    last_modified_on = NOW(),
    last_modified_by = NULL
WHERE status = 'CANCELED';

DROP INDEX uq_password_reset_code_pending_account;
DROP INDEX idx_password_reset_code_issuer;

ALTER TABLE password_reset_code ALTER COLUMN status DROP DEFAULT;
ALTER TYPE password_reset_code_status RENAME TO password_reset_code_status_old;
CREATE TYPE password_reset_code_status AS ENUM
    ('PENDING', 'REDEEMED', 'EXPIRED', 'INVALIDATED');
ALTER TABLE password_reset_code
    ALTER COLUMN status TYPE password_reset_code_status
    USING status::TEXT::password_reset_code_status;
ALTER TABLE password_reset_code ALTER COLUMN status SET DEFAULT 'PENDING';

CREATE UNIQUE INDEX uq_password_reset_code_pending_account
    ON password_reset_code (account_id)
    WHERE status = 'PENDING';
CREATE INDEX idx_password_reset_code_issuer ON password_reset_code (issuer_account_id)
    WHERE status = 'PENDING';

DROP TYPE password_reset_code_status_old;
