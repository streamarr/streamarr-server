-- A library shows STOPPING from the transaction that stores a ServerAdmin's stop until the
-- transaction that ends the stopped operation (ADR 0027).
ALTER TYPE library_status ADD VALUE IF NOT EXISTS 'STOPPING' AFTER 'REFRESHING';

-- The stop is stored with the operation, so every execution, including one after a restart, reads
-- the stop and admits no further task.
ALTER TABLE library_operation
    ADD COLUMN stop_requested BOOLEAN NOT NULL DEFAULT FALSE;
