package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.WorkerCalibration.WORKER_CALIBRATION;
import static org.jooq.impl.DSL.excluded;

import com.streamarr.server.domain.streaming.headroom.CalibrationKey;
import com.streamarr.server.domain.streaming.headroom.CpuLimit;
import com.streamarr.server.domain.streaming.headroom.KeptCalibration;
import java.time.ZoneOffset;
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

  private static final double MILLICORES_PER_CORE = 1_000;

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
            .set(WORKER_CALIBRATION.CPU_LIMIT_MILLICORES, millicores(key.cpuLimit()))
            .set(WORKER_CALIBRATION.FFMPEG_BUILD, key.ffmpegBuild())
            .set(WORKER_CALIBRATION.KEPT_AT, calibration.keptAt().atOffset(ZoneOffset.UTC))
            .set(WORKER_CALIBRATION.CALIBRATION, JSONB.jsonb(codec.encode(calibration)))
            .onConflict(
                WORKER_CALIBRATION.CPU_MODEL,
                WORKER_CALIBRATION.LOGICAL_CPUS,
                WORKER_CALIBRATION.GPU_MODELS,
                WORKER_CALIBRATION.CPU_LIMIT_MILLICORES,
                WORKER_CALIBRATION.FFMPEG_BUILD)
            .doUpdate()
            .set(WORKER_CALIBRATION.KEPT_AT, excluded(WORKER_CALIBRATION.KEPT_AT))
            .set(WORKER_CALIBRATION.CALIBRATION, excluded(WORKER_CALIBRATION.CALIBRATION))
            .where(WORKER_CALIBRATION.KEPT_AT.le(excluded(WORKER_CALIBRATION.KEPT_AT)))
            .execute();
    return updated == 1;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<KeptCalibration> findByKey(@NonNull CalibrationKey key) {
    return dsl.select(WORKER_CALIBRATION.CALIBRATION, WORKER_CALIBRATION.KEPT_AT)
        .from(WORKER_CALIBRATION)
        .where(matches(key))
        .fetchOptional(
            row ->
                codec
                    .decode(row.value1().data())
                    .key(key)
                    .keptAt(row.value2().toInstant())
                    .build());
  }

  private static Condition matches(CalibrationKey key) {
    var hardware = key.hardware();
    return WORKER_CALIBRATION
        .CPU_MODEL
        .eq(hardware.cpuModel())
        .and(WORKER_CALIBRATION.LOGICAL_CPUS.eq(hardware.logicalCpus()))
        .and(WORKER_CALIBRATION.GPU_MODELS.eq(hardware.gpuModels().toArray(String[]::new)))
        .and(WORKER_CALIBRATION.CPU_LIMIT_MILLICORES.eq(millicores(key.cpuLimit())))
        .and(WORKER_CALIBRATION.FFMPEG_BUILD.eq(key.ffmpegBuild()));
  }

  private static int millicores(CpuLimit cpuLimit) {
    return Math.toIntExact(Math.round(cpuLimit.cores() * MILLICORES_PER_CORE));
  }
}
