ALTER TABLE image
    ADD COLUMN ambient_top_left     TEXT,
    ADD COLUMN ambient_top_right    TEXT,
    ADD COLUMN ambient_bottom_right TEXT,
    ADD COLUMN ambient_bottom_left  TEXT,
    ADD COLUMN ambient_primary      TEXT;
