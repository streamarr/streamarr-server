CREATE TYPE media_file_status AS ENUM ('UNMATCHED', 'METADATA_PARSING_FAILED', 'METADATA_SEARCH_FAILED', 'MATCHED');

CREATE TABLE media_file
(
    id               UUID                     NOT NULL DEFAULT public.uuid_generate_v4(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID                     NOT NULL,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    filename         TEXT                     NOT NULL,
    filepath_uri     TEXT                     NOT NULL,
    size             BIGINT                   NOT NULL,
    media_id         UUID,
    library_id       UUID                     NOT NULL,
    status           media_file_status        NOT NULL,
    CONSTRAINT movie_file_pkey PRIMARY KEY (id),
    CONSTRAINT fk_base_collectable FOREIGN KEY (media_id) REFERENCES base_collectable (id),
    CONSTRAINT fk_library FOREIGN KEY (library_id) REFERENCES library (id)
);

CREATE UNIQUE INDEX media_file_filepath_uri_idx
    ON media_file (filepath_uri);
