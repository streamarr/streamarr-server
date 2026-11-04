-- A scan never follows a soft link. It skips each soft link it finds and stores the skip here, so
-- a ServerAdmin can see why a title is missing. A scan replaces the skips that earlier scans of
-- its library stored, so the rows show what the latest scan skipped. The operation id references
-- no row, because an operation's row is deleted when the operation ends (as in V088).
CREATE TYPE skipped_file_reason AS ENUM ('SOFT_LINK');

CREATE TABLE skipped_file
(
    id                   UUID                NOT NULL DEFAULT gen_random_uuid(),
    library_id           UUID                NOT NULL REFERENCES library (id) ON DELETE CASCADE,
    library_operation_id UUID                NOT NULL,
    filepath_uri         TEXT                NOT NULL,
    reason               skipped_file_reason NOT NULL,
    CONSTRAINT skipped_file_pkey PRIMARY KEY (id),
    CONSTRAINT skipped_file_library_id_filepath_uri_key UNIQUE (library_id, filepath_uri)
);
