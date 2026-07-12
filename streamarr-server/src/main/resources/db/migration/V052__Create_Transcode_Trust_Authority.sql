CREATE TYPE transcode_trust_certificate_kind AS ENUM (
    'TRUST_ANCHOR',
    'ISSUER',
    'REVOCATION_SIGNER'
);

CREATE TYPE transcode_ca_signing_operation AS ENUM (
    'BOOTSTRAP',
    'ISSUANCE',
    'ROTATION'
);

CREATE TABLE transcode_installation
(
    singleton             BOOLEAN                  NOT NULL DEFAULT TRUE,
    installation_id       UUID                     NOT NULL DEFAULT gen_random_uuid(),
    bootstrap_root_sha256 BYTEA,
    initialized_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT transcode_installation_pkey PRIMARY KEY (singleton),
    CONSTRAINT uq_transcode_installation_id UNIQUE (installation_id),
    CONSTRAINT chk_transcode_installation_singleton CHECK (singleton),
    CONSTRAINT chk_transcode_installation_root_digest CHECK (
        bootstrap_root_sha256 IS NULL OR octet_length(bootstrap_root_sha256) = 32
    ),
    CONSTRAINT chk_transcode_installation_initialization CHECK (
        (bootstrap_root_sha256 IS NULL AND initialized_at IS NULL)
        OR
        (bootstrap_root_sha256 IS NOT NULL AND initialized_at IS NOT NULL)
    )
);

INSERT INTO transcode_installation (singleton) VALUES (TRUE);

CREATE TABLE transcode_public_trust_bundle
(
    installation_id UUID                     NOT NULL,
    version         BIGINT                   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT transcode_public_trust_bundle_pkey PRIMARY KEY (installation_id, version),
    CONSTRAINT chk_transcode_public_trust_bundle_version CHECK (version > 0),
    CONSTRAINT fk_transcode_public_trust_bundle_installation
        FOREIGN KEY (installation_id)
        REFERENCES transcode_installation (installation_id)
        ON DELETE RESTRICT
);

CREATE TABLE transcode_trust_certificate
(
    installation_id    UUID                              NOT NULL,
    bundle_version     BIGINT                            NOT NULL,
    kind               transcode_trust_certificate_kind  NOT NULL,
    ordinal            SMALLINT                          NOT NULL,
    certificate_der    BYTEA                             NOT NULL,
    certificate_sha256 BYTEA                             NOT NULL,
    CONSTRAINT transcode_trust_certificate_pkey
        PRIMARY KEY (installation_id, bundle_version, kind, ordinal),
    CONSTRAINT uq_transcode_trust_certificate_digest
        UNIQUE (installation_id, bundle_version, certificate_sha256),
    CONSTRAINT chk_transcode_trust_certificate_ordinal CHECK (ordinal >= 0),
    CONSTRAINT chk_transcode_trust_certificate_der CHECK (
        octet_length(certificate_der) BETWEEN 1 AND 65536
    ),
    CONSTRAINT chk_transcode_trust_certificate_digest CHECK (
        octet_length(certificate_sha256) = 32
    ),
    CONSTRAINT fk_transcode_trust_certificate_bundle
        FOREIGN KEY (installation_id, bundle_version)
        REFERENCES transcode_public_trust_bundle (installation_id, version)
        ON DELETE RESTRICT
);

CREATE TABLE transcode_active_trust_bundle
(
    singleton       BOOLEAN                  NOT NULL DEFAULT TRUE,
    installation_id UUID                     NOT NULL,
    bundle_version  BIGINT,
    activated_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT transcode_active_trust_bundle_pkey PRIMARY KEY (singleton),
    CONSTRAINT uq_transcode_active_trust_bundle_installation UNIQUE (installation_id),
    CONSTRAINT chk_transcode_active_trust_bundle_singleton CHECK (singleton),
    CONSTRAINT chk_transcode_active_trust_bundle_activation CHECK (
        (bundle_version IS NULL AND activated_at IS NULL)
        OR
        (bundle_version IS NOT NULL AND bundle_version > 0 AND activated_at IS NOT NULL)
    ),
    CONSTRAINT fk_transcode_active_trust_bundle_installation
        FOREIGN KEY (installation_id)
        REFERENCES transcode_installation (installation_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transcode_active_trust_bundle_version
        FOREIGN KEY (installation_id, bundle_version)
        REFERENCES transcode_public_trust_bundle (installation_id, version)
        ON DELETE RESTRICT
);

INSERT INTO transcode_active_trust_bundle (singleton, installation_id)
SELECT TRUE, installation_id FROM transcode_installation WHERE singleton = TRUE;

CREATE TABLE transcode_ca_signing_lease
(
    singleton     BOOLEAN                        NOT NULL DEFAULT TRUE,
    operation     transcode_ca_signing_operation,
    owner_id      UUID,
    lease_until   TIMESTAMP WITH TIME ZONE,
    fencing_epoch BIGINT                         NOT NULL DEFAULT 0,
    CONSTRAINT transcode_ca_signing_lease_pkey PRIMARY KEY (singleton),
    CONSTRAINT chk_transcode_ca_signing_lease_singleton CHECK (singleton),
    CONSTRAINT chk_transcode_ca_signing_lease_fence CHECK (fencing_epoch >= 0),
    CONSTRAINT chk_transcode_ca_signing_lease_holder CHECK (
        (operation IS NULL AND owner_id IS NULL AND lease_until IS NULL)
        OR
        (operation IS NOT NULL AND owner_id IS NOT NULL AND lease_until IS NOT NULL)
    )
);

INSERT INTO transcode_ca_signing_lease (singleton) VALUES (TRUE);
