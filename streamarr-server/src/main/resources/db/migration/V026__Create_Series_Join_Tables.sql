-- Series-Company join table (mirrors movie_company from V004)
CREATE TABLE series_company
(
    series_id  UUID NOT NULL,
    company_id UUID NOT NULL,
    CONSTRAINT series_company_pkey PRIMARY KEY (series_id, company_id),
    CONSTRAINT series_company_series_id_fkey FOREIGN KEY (series_id)
        REFERENCES series (id) ON DELETE CASCADE,
    CONSTRAINT series_company_company_id_fkey FOREIGN KEY (company_id)
        REFERENCES company (id) ON DELETE CASCADE
);

CREATE INDEX idx_series_company_series_id ON series_company (series_id);
CREATE INDEX idx_series_company_company_id ON series_company (company_id);

-- Series-Person (cast) join table (mirrors movie_person from V006)
CREATE TABLE series_person
(
    series_id UUID    NOT NULL,
    person_id UUID    NOT NULL,
    ordinal   INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT series_person_pkey PRIMARY KEY (series_id, person_id),
    CONSTRAINT series_person_series_id_fkey FOREIGN KEY (series_id)
        REFERENCES series (id) ON DELETE CASCADE,
    CONSTRAINT series_person_person_id_fkey FOREIGN KEY (person_id)
        REFERENCES person (id) ON DELETE CASCADE
);

CREATE INDEX idx_series_person_series_id ON series_person (series_id);
CREATE INDEX idx_series_person_person_id ON series_person (person_id);

-- Series-Director join table (mirrors movie_director from V018)
CREATE TABLE series_director
(
    series_id UUID    NOT NULL,
    person_id UUID    NOT NULL,
    ordinal   INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT series_director_pkey PRIMARY KEY (series_id, person_id),
    CONSTRAINT series_director_series_id_fkey FOREIGN KEY (series_id)
        REFERENCES series (id) ON DELETE CASCADE,
    CONSTRAINT series_director_person_id_fkey FOREIGN KEY (person_id)
        REFERENCES person (id) ON DELETE CASCADE
);

CREATE INDEX idx_series_director_series_id ON series_director (series_id);
CREATE INDEX idx_series_director_person_id ON series_director (person_id);

-- Series-Genre join table (mirrors movie_genre from V019)
CREATE TABLE series_genre
(
    series_id UUID NOT NULL,
    genre_id  UUID NOT NULL,
    CONSTRAINT series_genre_pkey PRIMARY KEY (series_id, genre_id),
    CONSTRAINT series_genre_series_id_fkey FOREIGN KEY (series_id)
        REFERENCES series (id) ON DELETE CASCADE,
    CONSTRAINT series_genre_genre_id_fkey FOREIGN KEY (genre_id)
        REFERENCES genre (id) ON DELETE CASCADE
);

CREATE INDEX idx_series_genre_series_id ON series_genre (series_id);
CREATE INDEX idx_series_genre_genre_id ON series_genre (genre_id);
