ALTER TABLE transcode_enrollment_grant
    ADD CONSTRAINT uq_transcode_enrollment_grant_binding
        UNIQUE (grant_id, installation_id, worker_id, trust_bundle_version);

ALTER TABLE transcode_trust_certificate
    ADD CONSTRAINT uq_transcode_trust_certificate_kind_digest
        UNIQUE (installation_id, bundle_version, kind, certificate_sha256);

CREATE TABLE transcode_worker_certificate_issuance
(
    request_id                    UUID                     NOT NULL,
    grant_id                      UUID                     NOT NULL,
    installation_id               UUID                     NOT NULL,
    worker_id                     UUID                     NOT NULL,
    trust_bundle_version          BIGINT                   NOT NULL,
    subject_public_key_info_der   BYTEA                    NOT NULL,
    subject_public_key_sha256     BYTEA                    NOT NULL,
    issuer_certificate_kind       transcode_trust_certificate_kind NOT NULL DEFAULT 'ISSUER',
    issuer_certificate_sha256     BYTEA                    NOT NULL,
    serial_number                 BYTEA                    NOT NULL,
    certificate_profile_version   SMALLINT                 NOT NULL,
    not_before                    TIMESTAMP WITH TIME ZONE NOT NULL,
    not_after                     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT statement_timestamp(),
    claimed_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT statement_timestamp(),
    signing_fencing_epoch         BIGINT                   NOT NULL,
    certificate_der               BYTEA,
    certificate_sha256            BYTEA,
    completed_at                  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT transcode_worker_certificate_issuance_pkey PRIMARY KEY (request_id),
    CONSTRAINT uq_transcode_worker_certificate_issuance_grant UNIQUE (grant_id),
    CONSTRAINT uq_transcode_worker_certificate_issuance_serial
        UNIQUE (installation_id, issuer_certificate_sha256, serial_number),
    CONSTRAINT chk_transcode_worker_certificate_issuance_bundle_version
        CHECK (trust_bundle_version > 0),
    CONSTRAINT chk_transcode_worker_certificate_issuance_public_key_der
        CHECK (octet_length(subject_public_key_info_der) BETWEEN 1 AND 4096),
    CONSTRAINT chk_transcode_worker_certificate_issuance_public_key_digest
        CHECK (octet_length(subject_public_key_sha256) = 32),
    CONSTRAINT chk_transcode_worker_certificate_issuance_issuer_digest
        CHECK (octet_length(issuer_certificate_sha256) = 32),
    CONSTRAINT chk_transcode_worker_certificate_issuance_issuer_kind
        CHECK (issuer_certificate_kind = 'ISSUER'),
    CONSTRAINT chk_transcode_worker_certificate_issuance_serial CHECK (
        CASE
            WHEN octet_length(serial_number) BETWEEN 1 AND 20
                THEN get_byte(serial_number, 0) <> 0
            ELSE FALSE
        END
    ),
    CONSTRAINT chk_transcode_worker_certificate_issuance_profile
        CHECK (certificate_profile_version = 1),
    CONSTRAINT chk_transcode_worker_certificate_issuance_validity CHECK (not_after > not_before),
    CONSTRAINT chk_transcode_worker_certificate_issuance_claim CHECK (
        claimed_at >= created_at AND signing_fencing_epoch > 0
    ),
    CONSTRAINT chk_transcode_worker_certificate_issuance_certificate_der CHECK (
        certificate_der IS NULL OR octet_length(certificate_der) BETWEEN 1 AND 65536
    ),
    CONSTRAINT chk_transcode_worker_certificate_issuance_certificate_digest CHECK (
        certificate_sha256 IS NULL OR octet_length(certificate_sha256) = 32
    ),
    CONSTRAINT chk_transcode_worker_certificate_issuance_completion CHECK (
        (certificate_der IS NULL AND certificate_sha256 IS NULL AND completed_at IS NULL)
        OR
        (
            certificate_der IS NOT NULL
            AND certificate_sha256 IS NOT NULL
            AND completed_at IS NOT NULL
            AND completed_at >= claimed_at
        )
    ),
    CONSTRAINT fk_transcode_worker_certificate_issuance_grant
        FOREIGN KEY (grant_id, installation_id, worker_id, trust_bundle_version)
        REFERENCES transcode_enrollment_grant
            (grant_id, installation_id, worker_id, trust_bundle_version)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transcode_worker_certificate_issuance_issuer
        FOREIGN KEY (
            installation_id,
            trust_bundle_version,
            issuer_certificate_kind,
            issuer_certificate_sha256
        )
        REFERENCES transcode_trust_certificate
            (installation_id, bundle_version, kind, certificate_sha256)
        ON DELETE RESTRICT
);
