CREATE TABLE base_collectable
(
    id               UUID                     NOT NULL DEFAULT public.uuid_generate_v4(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID                     NOT NULL,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    title            TEXT,
    library_id       UUID                     NOT NULL,
    CONSTRAINT base_collectable_pkey PRIMARY KEY (id),
    CONSTRAINT fk_library FOREIGN KEY (library_id) REFERENCES library (id)
);
