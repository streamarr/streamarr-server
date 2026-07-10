CREATE TYPE account_role AS ENUM ('ADMIN', 'USER');
CREATE TYPE household_role AS ENUM ('OWNER', 'PARENT', 'MEMBER');
CREATE SEQUENCE household_membership_version_seq AS BIGINT START WITH 1 NO CYCLE;

CREATE TABLE user_account
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    email            TEXT                     NOT NULL,
    display_name     TEXT                     NOT NULL,
    password_hash    TEXT                     NOT NULL,
    account_role     account_role             NOT NULL,
    enabled          BOOLEAN                  NOT NULL DEFAULT TRUE,
    CONSTRAINT user_account_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uq_user_account_email ON user_account (LOWER(email));

CREATE TABLE household
(
    id                    UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by            UUID,
    last_modified_on      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by      UUID,
    name                  TEXT                     NOT NULL,
    default_rating_region TEXT                     NOT NULL DEFAULT 'US',
    CONSTRAINT household_pkey PRIMARY KEY (id)
);

CREATE TABLE household_membership
(
    id                 UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by         UUID,
    last_modified_on   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by   UUID,
    account_id         UUID                     NOT NULL,
    household_id       UUID                     NOT NULL,
    household_role     household_role           NOT NULL,
    membership_version BIGINT                   NOT NULL DEFAULT nextval('household_membership_version_seq'),
    CONSTRAINT household_membership_pkey PRIMARY KEY (id),
    CONSTRAINT fk_household_membership_account FOREIGN KEY (account_id)
        REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_household_membership_household FOREIGN KEY (household_id)
        REFERENCES household (id) ON DELETE CASCADE,
    CONSTRAINT uq_household_membership_account_household UNIQUE (account_id, household_id)
);

CREATE INDEX idx_household_membership_household_id ON household_membership (household_id);

CREATE TABLE profile
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    household_id     UUID                     NOT NULL,
    name             TEXT                     NOT NULL,
    policy_version   BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT profile_pkey PRIMARY KEY (id),
    CONSTRAINT fk_profile_household FOREIGN KEY (household_id)
        REFERENCES household (id) ON DELETE CASCADE,
    CONSTRAINT uq_profile_household_name UNIQUE (household_id, name),
    -- Redundant with the PK for uniqueness; exists as a composite-FK target so referencing
    -- tables can enforce profile-belongs-to-household.
    CONSTRAINT uq_profile_id_household UNIQUE (id, household_id)
);

CREATE TABLE account_profile
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    account_id       UUID                     NOT NULL,
    household_id     UUID                     NOT NULL,
    profile_id       UUID                     NOT NULL,
    CONSTRAINT account_profile_pkey PRIMARY KEY (id),
    CONSTRAINT fk_account_profile_membership FOREIGN KEY (account_id, household_id)
        REFERENCES household_membership (account_id, household_id) ON DELETE CASCADE,
    CONSTRAINT fk_account_profile_profile FOREIGN KEY (profile_id, household_id)
        REFERENCES profile (id, household_id) ON DELETE CASCADE,
    CONSTRAINT uq_account_profile_account_profile UNIQUE (account_id, profile_id)
);

CREATE INDEX idx_account_profile_profile_household ON account_profile (profile_id, household_id);

-- Boolean PK + CHECK (id) leaves TRUE as the only representable id: at most one bootstrap
-- row can ever exist.
CREATE TABLE server_bootstrap
(
    id               BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    admin_account_id UUID                     NOT NULL,
    CONSTRAINT server_bootstrap_pkey PRIMARY KEY (id),
    CONSTRAINT chk_server_bootstrap_singleton CHECK (id),
    -- No ON DELETE action: deleting the claiming admin is blocked while the bootstrap
    -- record exists.
    CONSTRAINT fk_server_bootstrap_admin_account FOREIGN KEY (admin_account_id)
        REFERENCES user_account (id)
);
