package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.WorkerCalibration.WORKER_CALIBRATION;
import static org.jooq.impl.DSL.excluded;

import com.streamarr.server.domain.streaming.headroom.CalibrationKey;
import com.streamarr.server.domain.streaming.headroom.KeptCalibration;
import java.time.Instant;
import java.util.Optional;
import lombok.NonNull;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JooqWorkerCalibrationRepository implements WorkerCalibrationRepository {

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private final DSLContext dsl;
  private final CalibrationDocumentCodec codec;

  public JooqWorkerCalibrationRepository(DSLContext dsl, ObjectMapper mapper) {
    this.dsl = dsl;
    this.codec = new CalibrationDocumentCodec(mapper);
  }

  /** Replaces the kept calibration only when this one was kept at the same time or later. */
  @Override
  @Transactional
  public boolean keep(@NonNull KeptCalibration calibration) {
    var key = calibration.key();
    var hardware = key.hardware();
    var updated =
        dsl.insertInto(WORKER_CALIBRATION)
            .set(WORKER_CALIBRATION.CPU_MODEL, hardware.cpuModel())
            .set(WORKER_CALIBRATION.LOGICAL_CPUS, hardware.logicalCpus())
            .set(WORKER_CALIBRATION.GPU_MODELS, hardware.gpuModels().toArray(String[]::new))
            .set(WORKER_CALIBRATION.CPU_LIMIT_CORES, key.cpuLimit().cores())
            .set(WORKER_CALIBRATION.FFMPEG_BUILD, key.ffmpegBuild())
            .set(WORKER_CALIBRATION.KEPT_AT_EPOCH_NANOS, epochNanos(calibration.keptAt()))
            .set(WORKER_CALIBRATION.CALIBRATION, JSONB.jsonb(codec.encode(calibration)))
            .onConflict(
                WORKER_CALIBRATION.CPU_MODEL,
                WORKER_CALIBRATION.LOGICAL_CPUS,
                WORKER_CALIBRATION.GPU_MODELS,
                WORKER_CALIBRATION.CPU_LIMIT_CORES,
                WORKER_CALIBRATION.FFMPEG_BUILD)
            .doUpdate()
            .set(
                WORKER_CALIBRATION.KEPT_AT_EPOCH_NANOS,
                excluded(WORKER_CALIBRATION.KEPT_AT_EPOCH_NANOS))
            .set(WORKER_CALIBRATION.CALIBRATION, excluded(WORKER_CALIBRATION.CALIBRATION))
            .where(
                WORKER_CALIBRATION.KEPT_AT_EPOCH_NANOS.le(
                    excluded(WORKER_CALIBRATION.KEPT_AT_EPOCH_NANOS)))
            .execute();
    return updated == 1;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<KeptCalibration> findByKey(@NonNull CalibrationKey key) {
    return dsl.select(WORKER_CALIBRATION.CALIBRATION, WORKER_CALIBRATION.KEPT_AT_EPOCH_NANOS)
        .from(WORKER_CALIBRATION)
        .where(matches(key))
        .fetchOptional(
            row ->
                codec.decode(row.value1().data()).key(key).keptAt(instantOf(row.value2())).build());
  }

  private static Condition matches(CalibrationKey key) {
    var hardware = key.hardware();
    return WORKER_CALIBRATION
        .CPU_MODEL
        .eq(hardware.cpuModel())
        .and(WORKER_CALIBRATION.LOGICAL_CPUS.eq(hardware.logicalCpus()))
        .and(WORKER_CALIBRATION.GPU_MODELS.eq(hardware.gpuModels().toArray(String[]::new)))
        .and(WORKER_CALIBRATION.CPU_LIMIT_CORES.eq(key.cpuLimit().cores()))
        .and(WORKER_CALIBRATION.FFMPEG_BUILD.eq(key.ffmpegBuild()));
  }

  private static long epochNanos(Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
  }

  private static Instant instantOf(long epochNanos) {
    return Instant.ofEpochSecond(
        Math.floorDiv(epochNanos, NANOS_PER_SECOND), Math.floorMod(epochNanos, NANOS_PER_SECOND));
  }
}
