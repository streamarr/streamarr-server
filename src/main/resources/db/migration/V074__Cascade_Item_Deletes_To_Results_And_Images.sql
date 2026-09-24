-- item_result and image name their item by an (id, type) pair, which cannot carry a foreign key.
-- One stored generated column per item table carries it instead, so deleting an item deletes its
-- results and images, and neither table accepts a row for an item that does not exist.

DELETE FROM item_result
WHERE CASE item_result.item_type
          WHEN 'MOVIE' THEN NOT EXISTS (SELECT FROM movie WHERE id = item_result.item_id)
          WHEN 'SERIES' THEN NOT EXISTS (SELECT FROM series WHERE id = item_result.item_id)
          WHEN 'SEASON' THEN NOT EXISTS (SELECT FROM season WHERE id = item_result.item_id)
          WHEN 'EPISODE' THEN NOT EXISTS (SELECT FROM episode WHERE id = item_result.item_id)
          WHEN 'PERSON' THEN NOT EXISTS (SELECT FROM person WHERE id = item_result.item_id)
          WHEN 'COMPANY' THEN NOT EXISTS (SELECT FROM company WHERE id = item_result.item_id)
      END;

DELETE FROM image
WHERE CASE image.entity_type
          WHEN 'MOVIE' THEN NOT EXISTS (SELECT FROM movie WHERE id = image.entity_id)
          WHEN 'SERIES' THEN NOT EXISTS (SELECT FROM series WHERE id = image.entity_id)
          WHEN 'SEASON' THEN NOT EXISTS (SELECT FROM season WHERE id = image.entity_id)
          WHEN 'EPISODE' THEN NOT EXISTS (SELECT FROM episode WHERE id = image.entity_id)
          WHEN 'PERSON' THEN NOT EXISTS (SELECT FROM person WHERE id = image.entity_id)
          WHEN 'COMPANY' THEN NOT EXISTS (SELECT FROM company WHERE id = image.entity_id)
      END;

ALTER TABLE item_result
    ADD COLUMN movie_id UUID
        GENERATED ALWAYS AS (CASE WHEN item_type = 'MOVIE' THEN item_id END) STORED
        REFERENCES movie (id) ON DELETE CASCADE,
    ADD COLUMN series_id UUID
        GENERATED ALWAYS AS (CASE WHEN item_type = 'SERIES' THEN item_id END) STORED
        REFERENCES series (id) ON DELETE CASCADE,
    ADD COLUMN season_id UUID
        GENERATED ALWAYS AS (CASE WHEN item_type = 'SEASON' THEN item_id END) STORED
        REFERENCES season (id) ON DELETE CASCADE,
    ADD COLUMN episode_id UUID
        GENERATED ALWAYS AS (CASE WHEN item_type = 'EPISODE' THEN item_id END) STORED
        REFERENCES episode (id) ON DELETE CASCADE,
    ADD COLUMN person_id UUID
        GENERATED ALWAYS AS (CASE WHEN item_type = 'PERSON' THEN item_id END) STORED
        REFERENCES person (id) ON DELETE CASCADE,
    ADD COLUMN company_id UUID
        GENERATED ALWAYS AS (CASE WHEN item_type = 'COMPANY' THEN item_id END) STORED
        REFERENCES company (id) ON DELETE CASCADE;

ALTER TABLE image
    ADD COLUMN movie_id UUID
        GENERATED ALWAYS AS (CASE WHEN entity_type = 'MOVIE' THEN entity_id END) STORED
        REFERENCES movie (id) ON DELETE CASCADE,
    ADD COLUMN series_id UUID
        GENERATED ALWAYS AS (CASE WHEN entity_type = 'SERIES' THEN entity_id END) STORED
        REFERENCES series (id) ON DELETE CASCADE,
    ADD COLUMN season_id UUID
        GENERATED ALWAYS AS (CASE WHEN entity_type = 'SEASON' THEN entity_id END) STORED
        REFERENCES season (id) ON DELETE CASCADE,
    ADD COLUMN episode_id UUID
        GENERATED ALWAYS AS (CASE WHEN entity_type = 'EPISODE' THEN entity_id END) STORED
        REFERENCES episode (id) ON DELETE CASCADE,
    ADD COLUMN person_id UUID
        GENERATED ALWAYS AS (CASE WHEN entity_type = 'PERSON' THEN entity_id END) STORED
        REFERENCES person (id) ON DELETE CASCADE,
    ADD COLUMN company_id UUID
        GENERATED ALWAYS AS (CASE WHEN entity_type = 'COMPANY' THEN entity_id END) STORED
        REFERENCES company (id) ON DELETE CASCADE;

-- Each cascade deletes by one of these columns, which is null for every other item type.
CREATE INDEX item_result_movie_id_idx ON item_result (movie_id) WHERE movie_id IS NOT NULL;
CREATE INDEX item_result_series_id_idx ON item_result (series_id) WHERE series_id IS NOT NULL;
CREATE INDEX item_result_season_id_idx ON item_result (season_id) WHERE season_id IS NOT NULL;
CREATE INDEX item_result_episode_id_idx ON item_result (episode_id) WHERE episode_id IS NOT NULL;
CREATE INDEX item_result_person_id_idx ON item_result (person_id) WHERE person_id IS NOT NULL;
CREATE INDEX item_result_company_id_idx ON item_result (company_id) WHERE company_id IS NOT NULL;

CREATE INDEX image_movie_id_idx ON image (movie_id) WHERE movie_id IS NOT NULL;
CREATE INDEX image_series_id_idx ON image (series_id) WHERE series_id IS NOT NULL;
CREATE INDEX image_season_id_idx ON image (season_id) WHERE season_id IS NOT NULL;
CREATE INDEX image_episode_id_idx ON image (episode_id) WHERE episode_id IS NOT NULL;
CREATE INDEX image_person_id_idx ON image (person_id) WHERE person_id IS NOT NULL;
CREATE INDEX image_company_id_idx ON image (company_id) WHERE company_id IS NOT NULL;
