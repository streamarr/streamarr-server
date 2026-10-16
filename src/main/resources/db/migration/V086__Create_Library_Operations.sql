-- A library operation is a scan or a refresh that a library runs. Its row exists from the
-- transaction that starts the operation until the transaction that ends it, so a library has at
-- most one operation at a time. The library shows SCANNING or REFRESHING exactly while the row
-- exists. The operation runs as one db-scheduler task instance whose id is the operation's id, so
-- the work runs again after a restart.
CREATE TYPE library_operation_kind AS ENUM ('SCAN', 'REFRESH');

-- The audit columns name who requested the operation and when the request committed.
CREATE TABLE library_operation
(
    id                      UUID                     NOT NULL,
    library_id              UUID                     NOT NULL REFERENCES library (id) ON DELETE CASCADE,
    kind                    library_operation_kind   NOT NULL,
    image_refresh_mode      image_refresh_mode,
    -- The idle status that a stopped operation gives back to the library.
    status_before_operation library_status           NOT NULL,
    created_on              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              UUID                     NOT NULL,
    last_modified_on        TIMESTAMP WITH TIME ZONE NOT NULL,
    last_modified_by        UUID,
    CONSTRAINT library_operation_pkey PRIMARY KEY (id),
    CONSTRAINT library_operation_library_id_key UNIQUE (library_id),
    CONSTRAINT library_operation_refresh_mode_check
        CHECK ((kind = 'REFRESH') = (image_refresh_mode IS NOT NULL)),
    CONSTRAINT library_operation_status_before_operation_check
        CHECK (status_before_operation IN ('HEALTHY', 'UNHEALTHY'))
);

CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON library_operation
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();

-- db-scheduler's task table, as published for PostgreSQL by the library, for the scheduler that
-- runs library operations. Its own threads keep an operation that waits for probes and artwork
-- off the threads that run probes and artwork. The server never queries this table directly.
CREATE TABLE library_operation_scheduled_tasks
(
    task_name            TEXT                     NOT NULL,
    task_instance        TEXT                     NOT NULL,
    task_data            BYTEA,
    execution_time       TIMESTAMP WITH TIME ZONE NOT NULL,
    picked               BOOLEAN                  NOT NULL,
    picked_by            TEXT,
    last_success         TIMESTAMP WITH TIME ZONE,
    last_failure         TIMESTAMP WITH TIME ZONE,
    consecutive_failures INT,
    last_heartbeat       TIMESTAMP WITH TIME ZONE,
    version              BIGINT                   NOT NULL,
    priority             SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX library_operation_scheduled_tasks_execution_time_idx
    ON library_operation_scheduled_tasks (execution_time);
CREATE INDEX library_operation_scheduled_tasks_last_heartbeat_idx
    ON library_operation_scheduled_tasks (last_heartbeat);

-- Scans and refreshes ran in memory before this migration, so no operation row backs a library
-- that still shows SCANNING or REFRESHING. Such a library becomes UNHEALTHY, the status that a
-- restart gave a busy library before this migration.
SELECT set_config('streamarr.actor_id', 'b634c755-3c6a-4ff1-a0a7-3e812d0ed1b9', true);

UPDATE library
SET status = 'UNHEALTHY'
WHERE status IN ('SCANNING', 'REFRESHING');
