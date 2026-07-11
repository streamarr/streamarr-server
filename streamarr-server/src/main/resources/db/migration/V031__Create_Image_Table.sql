CREATE TYPE image_type AS ENUM ('POSTER', 'BACKDROP', 'LOGO', 'STILL', 'PROFILE');
CREATE TYPE image_entity_type AS ENUM ('MOVIE', 'SERIES', 'SEASON', 'EPISODE', 'PERSON', 'COMPANY');
CREATE TYPE image_size AS ENUM ('SMALL', 'MEDIUM', 'LARGE', 'ORIGINAL');

CREATE TABLE image
(
    id               UUID                     NOT NULL DEFAULT public.uuid_generate_v4(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID                     NOT NULL,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    entity_id        UUID                     NOT NULL,
    entity_type      image_entity_type        NOT NULL,
    image_type       image_type               NOT NULL,
    variant          image_size               NOT NULL,
    width            INT                      NOT NULL,
    height           INT                      NOT NULL,
    blur_hash        TEXT,
    path             TEXT                     NOT NULL,
    CONSTRAINT image_pkey PRIMARY KEY (id)
);

CREATE INDEX image_entity_type_entity_id_idx
    ON image (entity_type, entity_id);

CREATE UNIQUE INDEX image_entity_id_image_type_variant_idx
    ON image (entity_id, image_type, variant);
