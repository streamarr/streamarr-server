CREATE TABLE company (
                         id UUID NOT NULL DEFAULT public.uuid_generate_v4(),
                         created_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                         created_by UUID NOT NULL,
                         last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                         last_modified_by UUID,
                         name TEXT,
                         CONSTRAINT company_pkey PRIMARY KEY (id)
);
