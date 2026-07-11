CREATE TABLE movie_person
(
    id         UUID                     NOT NULL DEFAULT public.uuid_generate_v4(),
    created_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    movie_id   UUID                     NOT NULL,
    person_id  UUID                     NOT NULL,
    CONSTRAINT movie_person_pkey PRIMARY KEY (id),
    CONSTRAINT movie_person_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES movie (id),
    CONSTRAINT movie_person_person_id_fkey FOREIGN KEY (person_id) REFERENCES person (id)
);
