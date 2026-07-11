ALTER TABLE library RENAME COLUMN filepath TO filepath_uri;

DROP INDEX IF EXISTS library_file_path_idx;
CREATE UNIQUE INDEX library_filepath_uri_idx ON library (filepath_uri);
