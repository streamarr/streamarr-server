CREATE TABLE movie_file
(
    id               UUID                     NOT NULL DEFAULT public.uuid_generate_v4(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID                     NOT NULL,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    filename         TEXT                     NOT NULL,
    filepath         TEXT                     NOT NULL,
    size             BIGINT                   NOT NULL,
    movie_id         UUID,
    library_id       UUID                     NOT NULL,
    CONSTRAINT movie_file_pkey PRIMARY KEY (id),
    CONSTRAINT fk_movie FOREIGN KEY (movie_id) REFERENCES movie (id),
    CONSTRAINT fk_library FOREIGN KEY (library_id) REFERENCES library (id)
);

CREATE UNIQUE INDEX movie_file_filepath_idx
    ON movie_file (filepath);
