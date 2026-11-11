-- Share codes (ADR 0024 §Share codes). A HouseholdAdmin, or a ServerAdmin, issues a share code for
-- one Household, and whoever holds it may offer a Profile to that Household. The code has the form
-- of the code a person types to link a Device. Only the HMAC-SHA-256 of the normalised code, under
-- a server key kept outside the database, is stored: the code itself is returned once, when it is
-- issued, and the stored digests alone do not let a reader try every code. A code admits one offer
-- and is then spent; it also stops when it expires, when it is withdrawn, or when the system
-- invalidates it because its issuer may no longer issue it. A LIVE row past expires_at reads as
-- EXPIRED. Codes are deleted with their Household.

CREATE TYPE share_code_status AS ENUM ('LIVE', 'SPENT', 'WITHDRAWN', 'EXPIRED', 'INVALIDATED');

CREATE TABLE share_code
(
    id                UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by        UUID,
    last_modified_on  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by  UUID,
    household_id      UUID                     NOT NULL,
    issuer_account_id UUID,
    code_digest       BYTEA                    NOT NULL,
    status            share_code_status        NOT NULL DEFAULT 'LIVE',
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT share_code_pkey PRIMARY KEY (id),
    -- A presented code is recognised by its digest alone, so no two codes share one.
    CONSTRAINT uq_share_code_code_digest UNIQUE (code_digest),
    CONSTRAINT chk_share_code_code_digest_length CHECK (octet_length(code_digest) = 32),
    -- A code leaves LIVE exactly when it ends; each transition writes both in one statement.
    CONSTRAINT chk_share_code_ended_at CHECK ((status = 'LIVE') = (ended_at IS NULL)),
    CONSTRAINT fk_share_code_household FOREIGN KEY (household_id)
        REFERENCES household (id) ON DELETE CASCADE,
    CONSTRAINT fk_share_code_issuer FOREIGN KEY (issuer_account_id)
        REFERENCES user_account (id) ON DELETE SET NULL
);

CREATE INDEX idx_share_code_household_created_id ON share_code (household_id, created_on DESC, id);
CREATE INDEX idx_share_code_issuer ON share_code (issuer_account_id);

CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON share_code
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();

-- Every request that presents a share code is a credential attempt of its own kind (ADR 0028).
ALTER TYPE credential_kind ADD VALUE 'SHARE_CODE';
