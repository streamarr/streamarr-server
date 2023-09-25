CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;

CREATE TYPE library_status AS ENUM ('SCANNING', 'HEALTHY', 'UNHEALTHY');
CREATE TYPE library_backend AS ENUM ('LOCAL', 'REMOTE');
CREATE TYPE media_type AS ENUM ('MOVIE', 'SERIES', 'OTHER');
CREATE TYPE external_agent_strategy AS ENUM ('TMDB');

CREATE TABLE library
(
    id                      UUID                     NOT NULL DEFAULT public.uuid_generate_v4(),
    created_on              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by              UUID                     NOT NULL,
    last_modified_on        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by        UUID,
    filepath                TEXT                     NOT NULL,
    name                    TEXT,
    scan_started_on         TIMESTAMP WITH TIME ZONE,
    scan_completed_on       TIMESTAMP WITH TIME ZONE,
    status                  library_status           NOT NULL,
    backend                 library_backend          NOT NULL,
    type                    media_type               NOT NULL,
    external_agent_strategy external_agent_strategy  NOT NULL DEFAULT 'TMDB',
    CONSTRAINT library_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX library_file_path_idx
    ON library (filepath);
