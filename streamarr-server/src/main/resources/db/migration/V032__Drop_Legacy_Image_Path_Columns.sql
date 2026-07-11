ALTER TABLE movie DROP COLUMN backdrop_path;
ALTER TABLE movie DROP COLUMN poster_path;

ALTER TABLE series DROP COLUMN backdrop_path;
ALTER TABLE series DROP COLUMN poster_path;
ALTER TABLE series DROP COLUMN logo_path;

ALTER TABLE season DROP COLUMN poster_path;

ALTER TABLE episode DROP COLUMN still_path;

ALTER TABLE person DROP COLUMN profile_path;

ALTER TABLE company DROP COLUMN logo_path;
