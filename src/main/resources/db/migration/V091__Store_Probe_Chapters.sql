-- An outcome from a probe version that reports no chapters keeps chapter_status NULL. KNOWN holds
-- the container's chapters, possibly none. OMITTED means the probe left out chapters that exceed
-- its chapter limit.
ALTER TABLE media_file_container_info
    ADD COLUMN chapter_status TEXT CHECK (chapter_status IN ('KNOWN', 'OMITTED')),
    ADD CONSTRAINT failed_probe_has_no_chapter_status
        CHECK (probe_error IS NULL OR chapter_status IS NULL),
    ADD CONSTRAINT media_file_container_info_chapter_status_key
        UNIQUE (media_file_id, chapter_status);

-- chapter_index keeps the order in which the probe reported the chapters. Times are seconds and
-- nanoseconds on the media timeline of the keyframe positions, so a start can be negative. The
-- foreign key admits a chapter row only while its outcome's chapter_status is KNOWN.
CREATE TABLE media_file_chapter
(
    media_file_id UUID NOT NULL,
    chapter_index INTEGER NOT NULL CHECK (chapter_index >= 0),
    chapter_status TEXT GENERATED ALWAYS AS ('KNOWN') STORED,
    start_seconds BIGINT,
    start_nanos INTEGER CHECK (start_nanos BETWEEN 0 AND 999999999),
    end_seconds BIGINT,
    end_nanos INTEGER CHECK (end_nanos BETWEEN 0 AND 999999999),
    title TEXT CHECK (octet_length(title) <= 512),
    PRIMARY KEY (media_file_id, chapter_index),
    CONSTRAINT complete_chapter_start CHECK ((start_seconds IS NULL) = (start_nanos IS NULL)),
    CONSTRAINT complete_chapter_end CHECK ((end_seconds IS NULL) = (end_nanos IS NULL)),
    CONSTRAINT chapters_require_known_status
        FOREIGN KEY (media_file_id, chapter_status)
            REFERENCES media_file_container_info (media_file_id, chapter_status)
            ON DELETE CASCADE
);
