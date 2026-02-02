CREATE TABLE movie_director (
    movie_id   UUID NOT NULL REFERENCES movie (id),
    person_id  UUID NOT NULL REFERENCES person (id),
    ordinal    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (movie_id, person_id)
);

CREATE INDEX idx_movie_director_movie_id ON movie_director (movie_id);
CREATE INDEX idx_movie_director_person_id ON movie_director (person_id);
