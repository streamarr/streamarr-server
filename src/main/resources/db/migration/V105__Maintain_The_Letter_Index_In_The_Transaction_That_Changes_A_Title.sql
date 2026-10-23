-- A library's letter index changes in the transaction that adds, removes or renames a top-level
-- item, so the letter index counts every committed title without a recount. A top-level item is a
-- base_collectable row that has a movie or series row.

-- Matches JooqQueryHelper.alphabetLetterField, which the start-letter seek uses.
CREATE FUNCTION alphabet_letter_of(title_sort TEXT) RETURNS alphabet_letter
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN (CASE WHEN LEFT(title_sort, 1) COLLATE "C" ~ '[a-zA-Z]'
             THEN UPPER(LEFT(title_sort, 1) COLLATE "C") ELSE 'HASH' END)::alphabet_letter;

CREATE FUNCTION adjust_letter_index(
    counted_library_id UUID, counted_letter alphabet_letter, delta INTEGER)
    RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF delta > 0 THEN
        INSERT INTO library_metadata AS counted (library_id, letter, item_count)
        VALUES (counted_library_id, counted_letter, delta)
        ON CONFLICT (library_id, letter)
            DO UPDATE SET item_count = counted.item_count + EXCLUDED.item_count;
        RETURN;
    END IF;

    UPDATE library_metadata counted SET item_count = counted.item_count + delta
    WHERE counted.library_id = counted_library_id AND counted.letter = counted_letter;
    DELETE FROM library_metadata counted
    WHERE counted.library_id = counted_library_id
      AND counted.letter = counted_letter
      AND counted.item_count <= 0;
END $$;

CREATE FUNCTION count_added_top_level_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    item base_collectable%ROWTYPE;
BEGIN
    SELECT * INTO item FROM base_collectable WHERE id = NEW.id;
    IF FOUND THEN
        PERFORM adjust_letter_index(item.library_id, alphabet_letter_of(item.title_sort), 1);
    END IF;
    RETURN NULL;
END $$;

-- JPA deletes the movie or series row before its base_collectable row, so the base row still
-- names the letter. A delete that starts at base_collectable is uncounted there instead.
CREATE FUNCTION uncount_deleted_top_level_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    item base_collectable%ROWTYPE;
BEGIN
    SELECT * INTO item FROM base_collectable WHERE id = OLD.id;
    IF FOUND THEN
        PERFORM adjust_letter_index(item.library_id, alphabet_letter_of(item.title_sort), -1);
    END IF;
    RETURN NULL;
END $$;

CREATE FUNCTION uncount_deleted_collectable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM movie WHERE id = OLD.id)
        OR EXISTS (SELECT 1 FROM series WHERE id = OLD.id) THEN
        PERFORM adjust_letter_index(OLD.library_id, alphabet_letter_of(OLD.title_sort), -1);
    END IF;
    RETURN OLD;
END $$;

-- The trigger names no columns, because a column named by a trigger cannot change its type and
-- tests change the collation of title_sort. Two moves lock their letters in the same order, so
-- opposite moves never deadlock.
CREATE FUNCTION move_renamed_top_level_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    old_letter alphabet_letter := alphabet_letter_of(OLD.title_sort);
    new_letter alphabet_letter := alphabet_letter_of(NEW.title_sort);
BEGIN
    IF OLD.library_id = NEW.library_id AND old_letter = new_letter THEN
        RETURN NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM movie WHERE id = NEW.id)
        AND NOT EXISTS (SELECT 1 FROM series WHERE id = NEW.id) THEN
        RETURN NULL;
    END IF;

    IF (OLD.library_id::TEXT, old_letter) < (NEW.library_id::TEXT, new_letter) THEN
        PERFORM adjust_letter_index(OLD.library_id, old_letter, -1);
        PERFORM adjust_letter_index(NEW.library_id, new_letter, 1);
        RETURN NULL;
    END IF;

    PERFORM adjust_letter_index(NEW.library_id, new_letter, 1);
    PERFORM adjust_letter_index(OLD.library_id, old_letter, -1);
    RETURN NULL;
END $$;

CREATE TRIGGER count_added_movie
    AFTER INSERT ON movie FOR EACH ROW EXECUTE FUNCTION count_added_top_level_item();
CREATE TRIGGER count_added_series
    AFTER INSERT ON series FOR EACH ROW EXECUTE FUNCTION count_added_top_level_item();
CREATE TRIGGER uncount_deleted_movie
    AFTER DELETE ON movie FOR EACH ROW EXECUTE FUNCTION uncount_deleted_top_level_item();
CREATE TRIGGER uncount_deleted_series
    AFTER DELETE ON series FOR EACH ROW EXECUTE FUNCTION uncount_deleted_top_level_item();
CREATE TRIGGER uncount_deleted_collectable
    BEFORE DELETE ON base_collectable FOR EACH ROW EXECUTE FUNCTION uncount_deleted_collectable();
CREATE TRIGGER move_renamed_top_level_item
    AFTER UPDATE ON base_collectable FOR EACH ROW EXECUTE FUNCTION move_renamed_top_level_item();

-- A letter index from before this migration could miss titles that the file watcher removed.
DELETE FROM library_metadata;

INSERT INTO library_metadata (library_id, letter, item_count)
SELECT item.library_id, alphabet_letter_of(item.title_sort), COUNT(*)
FROM base_collectable item
WHERE EXISTS (SELECT 1 FROM movie WHERE movie.id = item.id)
   OR EXISTS (SELECT 1 FROM series WHERE series.id = item.id)
GROUP BY item.library_id, 2;
