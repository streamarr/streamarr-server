-- One row for each probe version whose upgrade re-scan the server queued. The row keeps a restarted
-- server, or a second server, from queuing the same re-scan again.
CREATE TABLE probe_version_rescan
(
    probe_version INTEGER PRIMARY KEY CHECK (probe_version > 0),
    queued_on     TIMESTAMPTZ NOT NULL
);
