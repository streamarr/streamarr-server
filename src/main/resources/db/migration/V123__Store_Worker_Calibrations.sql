-- The last calibration that the server keeps for each combination of worker hardware, CPU limit and
-- FFmpeg build (ADR 0044). A worker that registers with the same combination starts from it. The
-- GPU models are stored sorted, so their order does not matter. The CPU limit is stored as the
-- worker reports it, so two different limits never share a row. The calibration document holds the fitted constants, each encode
-- configuration's live predictions and the constants that a measurement has priced; the server
-- writes and reads it whole. kept_at_epoch_nanos orders writes at full precision, so a delayed write
-- never replaces a newer one.
CREATE TABLE worker_calibration
(
    id                   UUID                     NOT NULL DEFAULT gen_random_uuid(),
    cpu_model            TEXT                     NOT NULL,
    logical_cpus         INTEGER                  NOT NULL,
    gpu_models           TEXT[]                   NOT NULL,
    cpu_limit_cores      DOUBLE PRECISION         NOT NULL,
    ffmpeg_build         TEXT                     NOT NULL,
    kept_at_epoch_nanos  BIGINT                   NOT NULL,
    calibration          JSONB                    NOT NULL,
    created_on           TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by           UUID                     NOT NULL,
    last_modified_on     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_modified_by     UUID,
    CONSTRAINT worker_calibration_pkey PRIMARY KEY (id),
    CONSTRAINT worker_calibration_key
        UNIQUE (cpu_model, logical_cpus, gpu_models, cpu_limit_cores, ffmpeg_build),
    CONSTRAINT chk_worker_calibration_logical_cpus CHECK (logical_cpus > 0),
    CONSTRAINT chk_worker_calibration_cpu_limit_cores CHECK (cpu_limit_cores > 0)
);

CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON worker_calibration
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
