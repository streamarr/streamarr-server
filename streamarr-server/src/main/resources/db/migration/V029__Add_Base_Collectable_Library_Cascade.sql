-- Add missing ON DELETE CASCADE to base_collectable → library FK.
-- V022 added cascades for movie→base_collectable and media_file→base_collectable
-- but missed the base_collectable→library direction.
ALTER TABLE base_collectable DROP CONSTRAINT fk_library;
ALTER TABLE base_collectable ADD CONSTRAINT fk_library
    FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE;
