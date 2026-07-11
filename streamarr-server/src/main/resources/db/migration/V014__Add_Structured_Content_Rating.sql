ALTER TABLE movie DROP COLUMN IF EXISTS content_rating;
ALTER TABLE movie ADD COLUMN content_rating_system VARCHAR(20);
ALTER TABLE movie ADD COLUMN content_rating_value VARCHAR(20);
ALTER TABLE movie ADD COLUMN content_rating_country VARCHAR(5);
