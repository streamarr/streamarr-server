DROP INDEX idx_base_collectable_library_titlesort_ci_id;

CREATE INDEX idx_base_collectable_library_titlesort_ci_id
    ON base_collectable (
        library_id,
        (title_sort IS NULL),
        COALESCE(LOWER(title_sort), '') COLLATE "C",
        id
    );
