ALTER TABLE image
    ADD COLUMN ambient_top_left     TEXT,
    ADD COLUMN ambient_top_right    TEXT,
    ADD COLUMN ambient_bottom_right TEXT,
    ADD COLUMN ambient_bottom_left  TEXT,
    ADD COLUMN ambient_primary      TEXT,
    ADD CONSTRAINT chk_image_ambient_colors_complete
        CHECK (num_nonnulls(
            ambient_top_left,
            ambient_top_right,
            ambient_bottom_right,
            ambient_bottom_left,
            ambient_primary) IN (0, 5));
