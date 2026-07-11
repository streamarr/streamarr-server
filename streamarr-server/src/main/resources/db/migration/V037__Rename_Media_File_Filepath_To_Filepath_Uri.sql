ALTER TABLE media_file RENAME COLUMN filepath TO filepath_uri;

DROP INDEX IF EXISTS media_file_filepath_idx;
CREATE UNIQUE INDEX media_file_filepath_uri_idx ON media_file (filepath_uri);
