-- Add ON DELETE CASCADE to foreign keys referencing movie(id)
ALTER TABLE rating DROP CONSTRAINT fk_movie;
ALTER TABLE rating ADD CONSTRAINT fk_movie
  FOREIGN KEY (movie_id) REFERENCES movie (id) ON DELETE CASCADE;

ALTER TABLE review DROP CONSTRAINT fk_movie;
ALTER TABLE review ADD CONSTRAINT fk_movie
  FOREIGN KEY (movie_id) REFERENCES movie (id) ON DELETE CASCADE;

ALTER TABLE movie_person DROP CONSTRAINT movie_person_movie_id_fkey;
ALTER TABLE movie_person ADD CONSTRAINT movie_person_movie_id_fkey
  FOREIGN KEY (movie_id) REFERENCES movie (id) ON DELETE CASCADE;

ALTER TABLE movie_company DROP CONSTRAINT movie_company_movie_id_fkey;
ALTER TABLE movie_company ADD CONSTRAINT movie_company_movie_id_fkey
  FOREIGN KEY (movie_id) REFERENCES movie (id) ON DELETE CASCADE;

ALTER TABLE movie_director DROP CONSTRAINT movie_director_movie_id_fkey;
ALTER TABLE movie_director ADD CONSTRAINT movie_director_movie_id_fkey
  FOREIGN KEY (movie_id) REFERENCES movie (id) ON DELETE CASCADE;

ALTER TABLE movie_genre DROP CONSTRAINT movie_genre_movie_id_fkey;
ALTER TABLE movie_genre ADD CONSTRAINT movie_genre_movie_id_fkey
  FOREIGN KEY (movie_id) REFERENCES movie (id) ON DELETE CASCADE;

-- Add ON DELETE CASCADE to foreign keys referencing base_collectable(id)
ALTER TABLE external_identifier DROP CONSTRAINT fk_base_collectable;
ALTER TABLE external_identifier ADD CONSTRAINT fk_base_collectable
  FOREIGN KEY (entity_id) REFERENCES base_collectable (id) ON DELETE CASCADE;

ALTER TABLE media_file DROP CONSTRAINT fk_base_collectable;
ALTER TABLE media_file ADD CONSTRAINT fk_base_collectable
  FOREIGN KEY (media_id) REFERENCES base_collectable (id) ON DELETE CASCADE;

-- Joined inheritance: movie extends base_collectable
ALTER TABLE movie DROP CONSTRAINT fk_movie;
ALTER TABLE movie ADD CONSTRAINT fk_movie
  FOREIGN KEY (id) REFERENCES base_collectable (id) ON DELETE CASCADE;
