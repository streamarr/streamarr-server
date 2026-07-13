CREATE TABLE transcode_worker_identity
(
    installation_id UUID                     NOT NULL,
    worker_id       UUID                     NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT transcode_worker_identity_pkey PRIMARY KEY (installation_id, worker_id),
    CONSTRAINT fk_transcode_worker_identity_installation
        FOREIGN KEY (installation_id)
        REFERENCES transcode_installation (installation_id)
        ON DELETE RESTRICT
);

CREATE TABLE transcode_enrollment_grant
(
    grant_id             UUID                     NOT NULL,
    installation_id      UUID                     NOT NULL,
    worker_id            UUID                     NOT NULL,
    trust_bundle_version BIGINT                   NOT NULL,
    token_sha256         BYTEA                    NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT statement_timestamp(),
    expires_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT transcode_enrollment_grant_pkey PRIMARY KEY (grant_id),
    CONSTRAINT uq_transcode_enrollment_grant_token UNIQUE (token_sha256),
    CONSTRAINT chk_transcode_enrollment_grant_bundle_version CHECK (trust_bundle_version > 0),
    CONSTRAINT chk_transcode_enrollment_grant_token_digest CHECK (octet_length(token_sha256) = 32),
    CONSTRAINT chk_transcode_enrollment_grant_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_transcode_enrollment_grant_consumption CHECK (
        consumed_at IS NULL OR consumed_at >= created_at
    ),
    CONSTRAINT fk_transcode_enrollment_grant_worker
        FOREIGN KEY (installation_id, worker_id)
        REFERENCES transcode_worker_identity (installation_id, worker_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transcode_enrollment_grant_bundle
        FOREIGN KEY (installation_id, trust_bundle_version)
        REFERENCES transcode_public_trust_bundle (installation_id, version)
        ON DELETE RESTRICT
);
