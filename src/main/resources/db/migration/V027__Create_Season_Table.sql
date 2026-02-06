CREATE TABLE season (
    id            UUID    NOT NULL,
    series_id     UUID    NOT NULL,
    season_number INTEGER NOT NULL,
    overview      TEXT,
    poster_path   TEXT,
    air_date      DATE,
    CONSTRAINT season_pkey PRIMARY KEY (id),
    CONSTRAINT fk_base_collectable FOREIGN KEY (id)
        REFERENCES base_collectable (id) ON DELETE CASCADE,
    CONSTRAINT fk_series FOREIGN KEY (series_id)
        REFERENCES series (id) ON DELETE CASCADE,
    CONSTRAINT uq_season UNIQUE (series_id, season_number)
);

CREATE INDEX idx_season_series_id ON season (series_id);
