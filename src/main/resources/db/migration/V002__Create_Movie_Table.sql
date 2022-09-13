CREATE TABLE movie
(
    id             UUID NOT NULL,
    artwork        TEXT,
    tmdb_id        TEXT,
    content_rating TEXT,
    CONSTRAINT movie_pkey PRIMARY KEY (id),
    CONSTRAINT fk_movie FOREIGN KEY (id) REFERENCES base_collectable (id),
    CONSTRAINT fk_movie UNIQUE (id)
);
