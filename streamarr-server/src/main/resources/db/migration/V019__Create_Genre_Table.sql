CREATE TABLE genre (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_by UUID,
    created_on TIMESTAMP WITH TIME ZONE,
    last_modified_by UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE,
    name       TEXT NOT NULL,
    source_id  TEXT NOT NULL,
    CONSTRAINT genre_source_id_unique UNIQUE (source_id)
);

CREATE TABLE movie_genre (
    movie_id UUID NOT NULL REFERENCES movie (id),
    genre_id UUID NOT NULL REFERENCES genre (id),
    PRIMARY KEY (movie_id, genre_id)
);

CREATE INDEX idx_movie_genre_movie_id ON movie_genre (movie_id);
CREATE INDEX idx_movie_genre_genre_id ON movie_genre (genre_id);
