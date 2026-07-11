-- movie_director: replace composite PK with surrogate UUID key
ALTER TABLE movie_director DROP CONSTRAINT movie_director_pkey;
ALTER TABLE movie_director ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE movie_director ADD CONSTRAINT movie_director_pkey PRIMARY KEY (id);

-- series_person: replace composite PK with surrogate UUID key
ALTER TABLE series_person DROP CONSTRAINT series_person_pkey;
ALTER TABLE series_person ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE series_person ADD CONSTRAINT series_person_pkey PRIMARY KEY (id);

-- series_director: replace composite PK with surrogate UUID key
ALTER TABLE series_director DROP CONSTRAINT series_director_pkey;
ALTER TABLE series_director ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE series_director ADD CONSTRAINT series_director_pkey PRIMARY KEY (id);
