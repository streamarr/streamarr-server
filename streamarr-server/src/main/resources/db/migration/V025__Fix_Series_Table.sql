-- Add missing ON DELETE CASCADE (V022 fixed movie but missed series)
ALTER TABLE series DROP CONSTRAINT fk_series;
ALTER TABLE series ADD CONSTRAINT fk_series
    FOREIGN KEY (id) REFERENCES base_collectable (id) ON DELETE CASCADE;

-- Replace stale flat content_rating with structured columns (mirrors V014 for movie)
ALTER TABLE series DROP COLUMN IF EXISTS content_rating;
ALTER TABLE series ADD COLUMN content_rating_system VARCHAR(20);
ALTER TABLE series ADD COLUMN content_rating_value VARCHAR(20);
ALTER TABLE series ADD COLUMN content_rating_country VARCHAR(5);

-- Add series-specific metadata columns
ALTER TABLE series ADD COLUMN summary TEXT;
ALTER TABLE series ADD COLUMN tagline TEXT;
ALTER TABLE series ADD COLUMN runtime INTEGER;
ALTER TABLE series ADD COLUMN first_air_date DATE;
