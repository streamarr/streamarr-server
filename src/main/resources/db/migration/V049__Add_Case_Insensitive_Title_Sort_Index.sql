CREATE INDEX idx_base_collectable_library_titlesort_ci_id
    ON base_collectable (library_id, LOWER(title_sort), id);
