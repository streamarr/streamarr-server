-- Probe tasks are now picked by priority, then by execution time. A playback request raises its
-- probe above every queued probe. PostgreSQL sorts NULL first in descending order, so queued probes
-- that predate priorities get db-scheduler's default priority. The index serves the ordered pick
-- that db-scheduler publishes for priorities.
UPDATE scheduled_tasks
SET priority = 50
WHERE priority IS NULL;

CREATE INDEX scheduled_tasks_priority_execution_time_idx
    ON scheduled_tasks (priority DESC, execution_time ASC);
