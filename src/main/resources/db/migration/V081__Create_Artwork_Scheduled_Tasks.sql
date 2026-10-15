-- db-scheduler's task table, as published for PostgreSQL by the library, once for each artwork
-- scheduler. Primary artwork (movies, series, seasons and episodes) and background artwork (people
-- and companies) run on separate schedulers with their own threads, so neither can take the
-- other's threads or the threads that run probes. The server never queries these tables directly.
CREATE TABLE primary_artwork_scheduled_tasks
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

CREATE INDEX primary_artwork_scheduled_tasks_execution_time_idx
    ON primary_artwork_scheduled_tasks (execution_time);
CREATE INDEX primary_artwork_scheduled_tasks_last_heartbeat_idx
    ON primary_artwork_scheduled_tasks (last_heartbeat);

CREATE TABLE background_artwork_scheduled_tasks
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

CREATE INDEX background_artwork_scheduled_tasks_execution_time_idx
    ON background_artwork_scheduled_tasks (execution_time);
CREATE INDEX background_artwork_scheduled_tasks_last_heartbeat_idx
    ON background_artwork_scheduled_tasks (last_heartbeat);
