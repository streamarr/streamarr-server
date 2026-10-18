-- Earlier buckets included seasons and episodes. Rebuild before exposing top-level totals.
DELETE FROM library_metadata;

INSERT INTO library_metadata (library_id, letter, item_count)
SELECT item.library_id,
       (CASE WHEN LEFT(item.title_sort, 1) COLLATE "C" ~ '[a-zA-Z]'
             THEN UPPER(LEFT(item.title_sort, 1) COLLATE "C") ELSE 'HASH' END)::alphabet_letter,
       COUNT(*)
FROM base_collectable item
LEFT JOIN movie ON movie.id = item.id
LEFT JOIN series ON series.id = item.id
WHERE movie.id IS NOT NULL OR series.id IS NOT NULL
GROUP BY item.library_id, 2;
