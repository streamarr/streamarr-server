ALTER TABLE image
    ADD COLUMN ambient_dark_vibrant  TEXT,
    ADD COLUMN ambient_dark_muted    TEXT,
    ADD COLUMN ambient_light_vibrant TEXT,
    ADD COLUMN ambient_light_muted   TEXT,
    ADD CONSTRAINT chk_image_ambient_swatches_require_primary
        CHECK (ambient_primary IS NOT NULL
            OR num_nonnulls(
                ambient_dark_vibrant,
                ambient_dark_muted,
                ambient_light_vibrant,
                ambient_light_muted) = 0);
