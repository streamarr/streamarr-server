CREATE TABLE series
(
    id             UUID NOT NULL,
    backdrop_path  TEXT,
    poster_path    TEXT,
    logo_path      TEXT,
    content_rating TEXT,
    CONSTRAINT series_pkey PRIMARY KEY (id),
    CONSTRAINT fk_series FOREIGN KEY (id) REFERENCES base_collectable (id),
    CONSTRAINT fk_series UNIQUE (id)
);
