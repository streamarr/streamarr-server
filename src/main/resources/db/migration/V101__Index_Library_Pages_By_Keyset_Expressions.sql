-- Library pages seek by one row comparison over (placement, value, id). The placement flag is
-- (value IS NULL) when items without a value sort in the value's direction, and
-- (value IS NOT NULL) when they sort against it. Each index names those exact expressions, so a
-- late page reads only its own rows. Titles sort lowercased and compared byte by byte.
DROP INDEX idx_base_collectable_library_title_seek;

CREATE INDEX idx_base_collectable_library_title_seek
    ON base_collectable (
        library_id,
        (LOWER(title_sort COLLATE "C") IS NULL),
        LOWER(title_sort COLLATE "C"),
        id
    );

DROP INDEX idx_movie_releasedate_id;
DROP INDEX idx_movie_releasedate_desc_id;
DROP INDEX idx_movie_runtime_id;
DROP INDEX idx_movie_runtime_desc_id;
DROP INDEX idx_series_firstairdate_id;
DROP INDEX idx_series_firstairdate_desc_id;
DROP INDEX idx_series_runtime_id;
DROP INDEX idx_series_runtime_desc_id;

CREATE INDEX idx_movie_release_date_seek ON movie ((release_date IS NULL), release_date, id);
CREATE INDEX idx_movie_release_date_desc_seek ON movie ((release_date IS NOT NULL), release_date, id);
CREATE INDEX idx_movie_runtime_seek ON movie ((runtime IS NULL), runtime, id);
CREATE INDEX idx_movie_runtime_desc_seek ON movie ((runtime IS NOT NULL), runtime, id);
CREATE INDEX idx_series_first_air_date_seek ON series ((first_air_date IS NULL), first_air_date, id);
CREATE INDEX idx_series_first_air_date_desc_seek
    ON series ((first_air_date IS NOT NULL), first_air_date, id);
CREATE INDEX idx_series_runtime_seek ON series ((runtime IS NULL), runtime, id);
CREATE INDEX idx_series_runtime_desc_seek ON series ((runtime IS NOT NULL), runtime, id);
