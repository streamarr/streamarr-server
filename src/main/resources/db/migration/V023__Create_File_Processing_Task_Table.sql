CREATE TYPE file_processing_task_status AS ENUM ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED');

CREATE TABLE file_processing_task
(
    id                UUID                        NOT NULL DEFAULT public.uuid_generate_v4(),
    filepath          TEXT                        NOT NULL,
    library_id        UUID                        NOT NULL,
    status            file_processing_task_status NOT NULL DEFAULT 'PENDING',
    owner_instance_id TEXT,
    lease_expires_at  TIMESTAMP WITH TIME ZONE,
    error_message     TEXT,
    created_on        TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    completed_on      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT file_processing_task_pkey PRIMARY KEY (id),
    CONSTRAINT fk_library FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX file_processing_task_filepath_active_idx
    ON file_processing_task (filepath)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX file_processing_task_claimable_idx
    ON file_processing_task (status)
    WHERE status = 'PENDING';
