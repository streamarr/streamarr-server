CREATE TABLE episode (
    id             UUID    NOT NULL,
    season_id      UUID    NOT NULL,
    episode_number INTEGER NOT NULL,
    overview       TEXT,
    still_path     TEXT,
    air_date       DATE,
    runtime        INTEGER,
    CONSTRAINT episode_pkey PRIMARY KEY (id),
    CONSTRAINT fk_base_collectable FOREIGN KEY (id)
        REFERENCES base_collectable (id) ON DELETE CASCADE,
    CONSTRAINT fk_season FOREIGN KEY (season_id)
        REFERENCES season (id) ON DELETE CASCADE,
    CONSTRAINT uq_episode UNIQUE (season_id, episode_number)
);

CREATE INDEX idx_episode_season_id ON episode (season_id);
