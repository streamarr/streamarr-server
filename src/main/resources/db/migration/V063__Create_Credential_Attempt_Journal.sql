CREATE TYPE credential_kind AS ENUM (
    'ACCOUNT_LOGIN',
    'ACCOUNT_PASSWORD_VERIFICATION',
    'PROFILE_PIN',
    'ACCOUNT_INVITATION_CODE',
    'PASSWORD_RESET_CODE',
    'PROFILE_MANAGER_INVITATION_CODE',
    'DEVICE_PAIRING_CODE'
    );

CREATE TYPE credential_attempt_result AS ENUM ('FAILED', 'SUCCEEDED');

-- Security history must survive deletion of its subjects: no foreign keys to accounts,
-- profiles, or credential rows (ADR 0028).
CREATE TABLE credential_attempt
(
    id              UUID                      NOT NULL DEFAULT gen_random_uuid(),
    credential_kind credential_kind           NOT NULL,
    account_id      UUID,
    profile_id      UUID,
    credential_id   UUID,
    -- Observational only: no index and no admission policy (ADR 0028).
    ip_address      INET                      NOT NULL,
    attempted_at    TIMESTAMP WITH TIME ZONE  NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,
    result          credential_attempt_result,
    CONSTRAINT credential_attempt_pkey PRIMARY KEY (id),
    CONSTRAINT chk_credential_attempt_completion
        CHECK ((completed_at IS NULL) = (result IS NULL)),
    CONSTRAINT chk_credential_attempt_completion_order
        CHECK (completed_at IS NULL OR completed_at >= attempted_at)
);

CREATE INDEX idx_credential_attempt_target_completed
    ON credential_attempt
        (credential_kind, account_id, profile_id, credential_id, completed_at DESC)
    WHERE completed_at IS NOT NULL;
CREATE INDEX idx_credential_attempt_target_pending
    ON credential_attempt
        (credential_kind, account_id, profile_id, credential_id, attempted_at DESC)
    WHERE completed_at IS NULL;
CREATE INDEX idx_credential_attempt_attempted_at
    ON credential_attempt (attempted_at);
