-- Change external_identifier PK from composite (external_source_type, external_id)
-- to UUID id column. TMDB assigns movie and TV IDs from separate sequences,
-- so the same numeric ID (e.g., 1399) can exist for both a movie and a TV series.
-- The composite unique constraint allows the same TMDB numeric ID across different
-- entities while preventing duplicate assignments to the same entity.

ALTER TABLE external_identifier DROP CONSTRAINT external_identifier_pkey;
ALTER TABLE external_identifier ADD PRIMARY KEY (id);
ALTER TABLE external_identifier ADD CONSTRAINT uq_external_source
    UNIQUE (external_source_type, external_id, entity_id);
