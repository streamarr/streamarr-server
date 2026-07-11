CREATE TABLE movie_company
(
    id         UUID                     NOT NULL DEFAULT public.uuid_generate_v4(),
    created_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    movie_id   UUID                     NOT NULL,
    company_id UUID                     NOT NULL,
    CONSTRAINT movie_company_pkey PRIMARY KEY (id),
    CONSTRAINT movie_company_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES movie (id),
    CONSTRAINT movie_company_company_id_fkey FOREIGN KEY (company_id) REFERENCES company (id)
);
