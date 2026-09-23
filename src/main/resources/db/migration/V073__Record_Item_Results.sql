CREATE TYPE item_result_step AS ENUM ('METADATA', 'ARTWORK');
CREATE TYPE item_result_outcome AS ENUM ('SUCCEEDED', 'UNAVAILABLE', 'FAILED');
CREATE TYPE item_result_failure_reason AS ENUM (
    'DOWNLOAD_FAILED',
    'INVALID_MEDIA',
    'TEMPORARY',
    'MISCONFIGURED',
    'SOURCE_INACCESSIBLE'
);

CREATE TABLE item_result
(
    item_id        UUID                     NOT NULL,
    item_type      image_entity_type        NOT NULL,
    step           item_result_step         NOT NULL,
    image_type     image_type,
    outcome        item_result_outcome      NOT NULL,
    failure_reason item_result_failure_reason,
    detail         TEXT,
    source_key     TEXT,
    attempted_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT item_result_identity UNIQUE NULLS NOT DISTINCT (item_id, item_type, step, image_type),
    CONSTRAINT item_result_artwork_names_image_type CHECK ((step = 'ARTWORK') = (image_type IS NOT NULL)),
    CONSTRAINT item_result_failure_names_reason CHECK ((outcome = 'FAILED') = (failure_reason IS NOT NULL))
);

ALTER TABLE media_file
    ADD COLUMN failure_reason item_result_failure_reason,
    ADD CONSTRAINT media_file_failure_reason_matches_status CHECK (
        failure_reason IS NULL OR status IN ('METADATA_UNAVAILABLE', 'ENRICHMENT_FAILED')
    );
