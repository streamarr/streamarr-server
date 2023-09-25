CREATE TABLE movie
(
    id             UUID NOT NULL,
    backdrop_path  TEXT,
    poster_path    TEXT,
    tagline        TEXT,
    summary        TEXT,
    content_rating TEXT,
    release_date   DATE,
    CONSTRAINT movie_pkey PRIMARY KEY (id),
    CONSTRAINT fk_movie FOREIGN KEY (id) REFERENCES base_collectable (id),
    CONSTRAINT fk_movie UNIQUE (id)
);
