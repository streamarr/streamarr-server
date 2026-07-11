-- FK indexes on existing tables
CREATE INDEX idx_base_collectable_library_id ON base_collectable (library_id);
CREATE INDEX idx_movie_company_movie_id ON movie_company (movie_id);
CREATE INDEX idx_movie_company_company_id ON movie_company (company_id);
CREATE INDEX idx_movie_person_movie_id ON movie_person (movie_id);
CREATE INDEX idx_movie_person_person_id ON movie_person (person_id);
CREATE INDEX idx_rating_movie_id ON rating (movie_id);
CREATE INDEX idx_review_movie_id ON review (movie_id);
CREATE INDEX idx_media_file_media_id ON media_file (media_id);
CREATE INDEX idx_media_file_library_id ON media_file (library_id);
CREATE INDEX idx_external_identifier_entity_id ON external_identifier (entity_id);

-- Composite indexes for keyset pagination sort patterns
CREATE INDEX idx_base_collectable_library_title_id ON base_collectable (library_id, title, id);
CREATE INDEX idx_base_collectable_library_created_id ON base_collectable (library_id, created_on, id);
