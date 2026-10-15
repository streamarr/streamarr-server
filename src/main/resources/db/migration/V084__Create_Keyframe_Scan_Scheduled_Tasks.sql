-- db-scheduler's task table, as published for PostgreSQL by the library, for the scheduler that
-- runs keyframe packet scans. Scans run on their own scheduler with its own threads, so a scan
-- never takes a thread that runs probes. The server never queries this table directly.
CREATE TABLE keyframe_scan_scheduled_tasks
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

CREATE INDEX keyframe_scan_scheduled_tasks_execution_time_idx
    ON keyframe_scan_scheduled_tasks (execution_time);
CREATE INDEX keyframe_scan_scheduled_tasks_last_heartbeat_idx
    ON keyframe_scan_scheduled_tasks (last_heartbeat);
