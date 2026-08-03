CREATE TYPE device_authorization_status AS ENUM ('PENDING', 'APPROVED', 'DENIED', 'CONSUMED');

CREATE TABLE device_authorization
(
    id                    UUID                       NOT NULL DEFAULT gen_random_uuid(),
    created_on            TIMESTAMP WITH TIME ZONE   NOT NULL DEFAULT NOW(),
    created_by            UUID,
    last_modified_on      TIMESTAMP WITH TIME ZONE   NOT NULL DEFAULT NOW(),
    last_modified_by      UUID,
    -- The polled credential: 256-bit, never stored raw. Asymmetric with user_code on purpose.
    device_code_digest    TEXT                       NOT NULL,
    -- A low-entropy display handle a human types, so the web lookup must be able to find it by
    -- value; digesting it would forbid the lookup. Single use, a short TTL, and the account-keyed
    -- guessing budget carry its safety instead.
    user_code             TEXT                       NOT NULL,
    status                device_authorization_status NOT NULL DEFAULT 'PENDING',
    device_name           TEXT                       NOT NULL,
    -- Records who approved OR denied. SET NULL rather than CASCADE: deleting an account must not
    -- erase the decision history of CONSUMED and DENIED rows, and the sweeper already bounds how
    -- long any row lives.
    decided_by_account_id UUID,
    -- Expiry is a predicate over this column, never a stored status: an expired row needs no
    -- writer to become expired.
    expires_at            TIMESTAMP WITH TIME ZONE   NOT NULL,
    decided_at            TIMESTAMP WITH TIME ZONE,
    next_poll_at          TIMESTAMP WITH TIME ZONE   NOT NULL,
    poll_interval_seconds INTEGER                    NOT NULL,
    CONSTRAINT device_authorization_pkey PRIMARY KEY (id),
    CONSTRAINT uq_device_authorization_device_code_digest UNIQUE (device_code_digest),
    CONSTRAINT uq_device_authorization_user_code UNIQUE (user_code),
    CONSTRAINT fk_device_authorization_decided_by FOREIGN KEY (decided_by_account_id)
        REFERENCES user_account (id) ON DELETE SET NULL,
    CONSTRAINT chk_device_authorization_poll_interval
        CHECK (poll_interval_seconds >= 5)
);

CREATE INDEX idx_device_authorization_expires_at ON device_authorization (expires_at);
