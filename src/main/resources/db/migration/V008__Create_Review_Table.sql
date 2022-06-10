CREATE TABLE review (
                        id UUID NOT NULL DEFAULT public.uuid_generate_v4(),
                        created_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                        created_by UUID NOT NULL,
                        last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                        last_modified_by UUID,
                        author TEXT,
                        movie_id UUID NOT NULL,
                        CONSTRAINT review_pkey PRIMARY KEY (id),
                        CONSTRAINT fk_movie FOREIGN KEY (movie_id) REFERENCES movie (id)
);
