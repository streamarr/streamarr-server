-- Title pages seek by (title IS NULL, title, id), the title lowercased and compared byte by byte.
-- The index names those exact expressions, so a late page reads only its own rows.
DROP INDEX idx_base_collectable_library_title_seek;

CREATE INDEX idx_base_collectable_library_title_seek
    ON base_collectable (
        library_id,
        (LOWER(title_sort COLLATE "C") IS NULL),
        LOWER(title_sort COLLATE "C"),
        id
    );
