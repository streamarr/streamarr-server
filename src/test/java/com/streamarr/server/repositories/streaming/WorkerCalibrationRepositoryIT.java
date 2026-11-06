package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.WorkerCalibration.WORKER_CALIBRATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.headroom.CalibrationKey;
import com.streamarr.server.domain.streaming.headroom.CpuLimit;
import com.streamarr.server.domain.streaming.headroom.DecodeBackend;
import com.streamarr.server.domain.streaming.headroom.EncodeConfiguration;
import com.streamarr.server.domain.streaming.headroom.EncoderAndPreset;
import com.streamarr.server.domain.streaming.headroom.HeadroomConstants;
import com.streamarr.server.domain.streaming.headroom.KeptCalibration;
import com.streamarr.server.domain.streaming.headroom.PixelRate;
import com.streamarr.server.domain.streaming.headroom.PredictionCheck;
import com.streamarr.server.domain.streaming.headroom.SourceDecoder;
import com.streamarr.server.domain.streaming.headroom.ToneMapping;
import com.streamarr.server.domain.streaming.headroom.WorkerHardware;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Worker Calibration Repository Integration Tests")
class WorkerCalibrationRepositoryIT extends AbstractIntegrationTest {

  private static final EncoderAndPreset LIBX264_FASTER =
      new EncoderAndPreset(
          EncoderBackend.builder()
              .codecFamily("h264")
              .encoder("libx264")
              .kind(EncoderBackend.Kind.SOFTWARE)
              .build(),
          EncoderPreset.FASTER);
  private static final SourceDecoder SOFTWARE_H264 =
      new SourceDecoder("h264", DecodeBackend.SOFTWARE);
  private static final Instant KEPT_AT = Instant.parse("2026-11-06T02:00:00Z");

  @Autowired private WorkerCalibrationRepository repository;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate transactions;

  @BeforeEach
  void deleteKeptCalibrations() {
    transactions.executeWithoutResult(_ -> dsl.deleteFrom(WORKER_CALIBRATION).execute());
  }

  private static CalibrationKey.CalibrationKeyBuilder tenCoreWorker() {
    return CalibrationKey.builder()
        .hardware(
            WorkerHardware.builder()
                .cpuModel("Synthetic CPU 9000")
                .logicalCpus(10)
                .gpuModels(List.of("Synthetic GPU A", "Synthetic GPU B"))
                .build())
        .cpuLimit(new CpuLimit(10))
        .ffmpegBuild("8.1.2-synthetic");
  }

  private static KeptCalibration.KeptCalibrationBuilder fasterAt(double cpuSecondsPerMegapixel) {
    return KeptCalibration.builder()
        .key(tenCoreWorker().build())
        .constants(
            HeadroomConstants.builder()
                .cpuSecondsPerMegapixel(
                    Map.of(LIBX264_FASTER, cpuSecondsPerMegapixel, SOFTWARE_H264, 0.01))
                .coresAlone(Map.of(LIBX264_FASTER, 6.0))
                .build())
        .latestPredictions(
            Map.of(
                new EncodeConfiguration(LIBX264_FASTER, SOFTWARE_H264, ToneMapping.NONE),
                List.of(
                    PredictionCheck.builder()
                        .relativeError(0.04)
                        .besideOtherEncodes(true)
                        .outputPixelRate(PixelRate.of(1280, 720, 24))
                        .sourcePixelRate(PixelRate.of(1920, 1080, 24))
                        .measuredCpuSecondsPerMediaSecond(1.5)
                        .measuredAt(KEPT_AT.minusSeconds(60))
                        .build())))
        .measuredConstants(Set.of(LIBX264_FASTER, SOFTWARE_H264))
        .keptAt(KEPT_AT);
  }

  @Test
  @DisplayName("Should find the kept calibration when a worker registers with the same key")
  void shouldFindTheKeptCalibrationWhenAWorkerRegistersWithTheSameKey() {
    var kept = fasterAt(0.04).build();

    assertThat(repository.keep(kept)).isTrue();

    assertThat(repository.findByKey(tenCoreWorker().build())).contains(kept);
  }

  @Test
  @DisplayName("Should find no calibration when the worker returns with a new FFmpeg build")
  void shouldFindNoCalibrationWhenTheWorkerReturnsWithANewFfmpegBuild() {
    repository.keep(fasterAt(0.04).build());

    assertThat(repository.findByKey(tenCoreWorker().ffmpegBuild("8.1.3-synthetic").build()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should find no calibration when the worker returns with a new CPU limit")
  void shouldFindNoCalibrationWhenTheWorkerReturnsWithANewCpuLimit() {
    repository.keep(fasterAt(0.04).build());

    assertThat(repository.findByKey(tenCoreWorker().cpuLimit(new CpuLimit(6.5)).build())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should find no calibration when the worker returns with a CPU limit that differs by less than a thousandth of a core")
  void
      shouldFindNoCalibrationWhenTheWorkerReturnsWithACpuLimitThatDiffersByLessThanAThousandthOfACore() {
    repository.keep(
        fasterAt(0.04).key(tenCoreWorker().cpuLimit(new CpuLimit(6.5001)).build()).build());

    assertThat(repository.findByKey(tenCoreWorker().cpuLimit(new CpuLimit(6.5004)).build()))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the newer calibration when an older one whose kept time falls in the same microsecond arrives late")
  void
      shouldKeepTheNewerCalibrationWhenAnOlderOneWhoseKeptTimeFallsInTheSameMicrosecondArrivesLate() {
    var newer = fasterAt(0.05).keptAt(KEPT_AT.plusNanos(400)).build();
    repository.keep(newer);

    repository.keep(fasterAt(0.04).keptAt(KEPT_AT.plusNanos(100)).build());

    assertThat(repository.findByKey(tenCoreWorker().build()))
        .hasValueSatisfying(
            kept ->
                assertThat(kept.constants().cpuSecondsPerMegapixel())
                    .containsEntry(LIBX264_FASTER, 0.05));
  }

  @Test
  @DisplayName("Should find the kept calibration when the worker reports its GPUs in another order")
  void shouldFindTheKeptCalibrationWhenTheWorkerReportsItsGpusInAnotherOrder() {
    var kept = fasterAt(0.04).build();
    repository.keep(kept);

    var reordered =
        tenCoreWorker()
            .hardware(
                WorkerHardware.builder()
                    .cpuModel("Synthetic CPU 9000")
                    .logicalCpus(10)
                    .gpuModels(List.of("Synthetic GPU B", "Synthetic GPU A"))
                    .build())
            .build();

    assertThat(repository.findByKey(reordered)).contains(kept);
  }

  @Test
  @DisplayName(
      "Should replace the kept calibration when a later calibration for the same key is kept")
  void shouldReplaceTheKeptCalibrationWhenALaterCalibrationForTheSameKeyIsKept() {
    repository.keep(fasterAt(0.04).build());
    var later = fasterAt(0.05).keptAt(KEPT_AT.plusSeconds(300)).build();

    assertThat(repository.keep(later)).isTrue();

    assertThat(repository.findByKey(tenCoreWorker().build())).contains(later);
    assertThat(dsl.fetchCount(WORKER_CALIBRATION)).isOne();
  }

  @Test
  @DisplayName(
      "Should keep the newer calibration when an older calibration for the same key arrives late")
  void shouldKeepTheNewerCalibrationWhenAnOlderCalibrationForTheSameKeyArrivesLate() {
    var newer = fasterAt(0.05).keptAt(KEPT_AT.plusSeconds(300)).build();
    repository.keep(newer);

    assertThat(repository.keep(fasterAt(0.04).build())).isFalse();

    assertThat(repository.findByKey(tenCoreWorker().build())).contains(newer);
  }
}
