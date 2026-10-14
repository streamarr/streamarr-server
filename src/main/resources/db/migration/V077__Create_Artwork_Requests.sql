-- One artwork request per item: the image sources and refresh mode of the artwork the item needs.
-- A request with changed sources or a stronger refresh mode starts a new generation. An artwork
-- step saves images and records results only while its generation is the current one, and the
-- step deletes the request once every requested image of that generation has a final result.
-- The refresh modes are declared from weakest to strongest, so GREATEST keeps the stronger one.
CREATE TYPE image_refresh_mode AS ENUM ('PRESERVE', 'REFRESH_IF_CHANGED', 'FORCE_REFRESH');

CREATE SEQUENCE artwork_request_generation;

CREATE TABLE artwork_request
(
    item_id            UUID                     NOT NULL,
    item_type          image_entity_type        NOT NULL,
    image_refresh_mode image_refresh_mode       NOT NULL,
    source_image_types image_type[]             NOT NULL,
    source_keys        TEXT[]                   NOT NULL,
    image_count        INT                      NOT NULL,
    generation         BIGINT                   NOT NULL,
    requested_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Like item_result and image (V074), each item table carries a foreign key through one stored
    -- generated column, so deleting an item deletes its artwork request.
    movie_id           UUID GENERATED ALWAYS AS (CASE WHEN item_type = 'MOVIE' THEN item_id END) STORED
        REFERENCES movie (id) ON DELETE CASCADE,
    series_id          UUID GENERATED ALWAYS AS (CASE WHEN item_type = 'SERIES' THEN item_id END) STORED
        REFERENCES series (id) ON DELETE CASCADE,
    season_id          UUID GENERATED ALWAYS AS (CASE WHEN item_type = 'SEASON' THEN item_id END) STORED
        REFERENCES season (id) ON DELETE CASCADE,
    episode_id         UUID GENERATED ALWAYS AS (CASE WHEN item_type = 'EPISODE' THEN item_id END) STORED
        REFERENCES episode (id) ON DELETE CASCADE,
    person_id          UUID GENERATED ALWAYS AS (CASE WHEN item_type = 'PERSON' THEN item_id END) STORED
        REFERENCES person (id) ON DELETE CASCADE,
    company_id         UUID GENERATED ALWAYS AS (CASE WHEN item_type = 'COMPANY' THEN item_id END) STORED
        REFERENCES company (id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, item_type),
    CONSTRAINT artwork_request_pairs_sources CHECK (
        cardinality(source_image_types) = cardinality(source_keys)
        ),
    CONSTRAINT artwork_request_counts_images CHECK (image_count > 0)
);

CREATE INDEX artwork_request_movie_id_idx ON artwork_request (movie_id) WHERE movie_id IS NOT NULL;
CREATE INDEX artwork_request_series_id_idx ON artwork_request (series_id) WHERE series_id IS NOT NULL;
CREATE INDEX artwork_request_season_id_idx ON artwork_request (season_id) WHERE season_id IS NOT NULL;
CREATE INDEX artwork_request_episode_id_idx ON artwork_request (episode_id) WHERE episode_id IS NOT NULL;
CREATE INDEX artwork_request_person_id_idx ON artwork_request (person_id) WHERE person_id IS NOT NULL;
CREATE INDEX artwork_request_company_id_idx ON artwork_request (company_id) WHERE company_id IS NOT NULL;

-- The generation of the artwork request that an artwork result was recorded for. Results recorded
-- before artwork requests existed belong to generation 0, which every request outranks.
ALTER TABLE item_result
    ADD COLUMN artwork_generation BIGINT;

UPDATE item_result
SET artwork_generation = 0
WHERE step = 'ARTWORK';

ALTER TABLE item_result
    ADD CONSTRAINT item_result_artwork_names_generation CHECK (
        (step = 'ARTWORK') = (artwork_generation IS NOT NULL)
        );
