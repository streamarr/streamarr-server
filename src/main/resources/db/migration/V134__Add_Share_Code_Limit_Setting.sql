-- How many live share codes a Household may hold at once (ADR 0024 §Share codes): ten by default,
-- between one and a hundred. At the limit, issuing another is refused until a code is spent,
-- withdrawn, invalidated or expires. Lowering the limit ends no live code.
ALTER TABLE server_settings
    ADD COLUMN share_code_limit INTEGER NOT NULL DEFAULT 10,
    ADD CONSTRAINT chk_server_settings_share_code_limit CHECK (share_code_limit BETWEEN 1 AND 100);
