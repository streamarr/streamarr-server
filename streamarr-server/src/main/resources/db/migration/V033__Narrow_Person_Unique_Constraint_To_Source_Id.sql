ALTER TABLE person DROP CONSTRAINT person_uc;
ALTER TABLE person ADD CONSTRAINT person_source_id_unique UNIQUE (source_id);
