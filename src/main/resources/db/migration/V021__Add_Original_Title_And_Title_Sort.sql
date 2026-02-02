ALTER TABLE base_collectable ADD COLUMN original_title TEXT;
ALTER TABLE base_collectable ADD COLUMN title_sort TEXT;

-- Backfill title_sort for existing rows
UPDATE base_collectable SET title_sort = CASE
    WHEN title ~* '^The ' THEN substring(title from 5) || ', The'
    WHEN title ~* '^An ' THEN substring(title from 4) || ', An'
    WHEN title ~* '^A ' THEN substring(title from 3) || ', A'
    ELSE title
END;

-- Composite index for future keyset pagination by title_sort
CREATE INDEX idx_base_collectable_library_titlesort_id
    ON base_collectable (library_id, title_sort, id);
