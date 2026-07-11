CREATE TYPE external_source_type AS ENUM ('IMDB', 'OMDB', 'TMDB', 'TVDB');

CREATE TABLE external_identifier
(
    id                   UUID                     NOT NULL DEFAULT public.uuid_generate_v4(),
    created_on           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by           UUID                     NOT NULL,
    last_modified_on     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by     UUID,
    external_source_type external_source_type     NOT NULL,
    external_id          TEXT                     NOT NULL,
    entity_id            UUID,
    CONSTRAINT external_identifier_pkey PRIMARY KEY (external_source_type, external_id),
    CONSTRAINT fk_base_collectable FOREIGN KEY (entity_id) REFERENCES base_collectable (id)
);

CREATE UNIQUE INDEX external_identifier_id_idx
    ON external_identifier (id);
