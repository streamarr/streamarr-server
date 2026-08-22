ALTER TABLE image
    ADD COLUMN key            TEXT,
    ADD COLUMN content_sha256 TEXT,
    ADD CONSTRAINT image_content_sha256_format_check
        CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$') NOT VALID;
