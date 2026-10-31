package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.WorkerVerifiedCodecFamilies;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.health.contributor.Status;

@Tag("UnitTest")
@DisplayName("Transcode Executor Health Indicator Tests")
class TranscodeExecutorHealthIndicatorTest {

  @Test
  @DisplayName("Should report up with available slots when executor is healthy")
  void shouldReportUpWithAvailableSlotsWhenExecutorIsHealthy() {
    var executor = new FakeTranscodeExecutor();
    executor.setAvailableSlots(3);
    var indicator = new TranscodeExecutorHealthIndicator(executor);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("availableSlots", 3);
  }

  @Test
  @DisplayName("Should report unbounded capacity when executor imposes no slot limit")
  void shouldReportUnboundedCapacityWhenExecutorImposesNoSlotLimit() {
    var executor = new FakeTranscodeExecutor();
    executor.setAvailableSlots(TranscodeExecutor.UNBOUNDED_SLOTS);
    var indicator = new TranscodeExecutorHealthIndicator(executor);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("capacity", "unbounded");
    assertThat(health.getDetails()).doesNotContainKey("availableSlots");
  }

  @Test
  @DisplayName("Should report down when executor has no transcode capacity")
  void shouldReportDownWhenExecutorHasNoTranscodeCapacity() {
    var executor = new FakeTranscodeExecutor();
    executor.setHealthy(false);
    var indicator = new TranscodeExecutorHealthIndicator(executor);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"h264|av1", "av1|h264", "hevc|av1,h264"})
  @DisplayName(
      "Should report degraded and name the worker when it lacks a worker-verified encoder for a codec family the server encodes")
  void
      shouldReportDegradedAndNameTheWorkerWhenItLacksAWorkerVerifiedEncoderForACodecFamilyTheServerEncodes(
          String verifiedFamilies, String missingFamilies) {
    var workerId = UUID.randomUUID();
    var executor = new FakeTranscodeExecutor();
    executor.setAvailableSlots(2);
    executor.setVerifiedVideoCodecFamiliesByWorker(
        List.of(worker(workerId, Set.of(verifiedFamilies.split(",")))));
    var indicator = new TranscodeExecutorHealthIndicator(executor);

    var health = indicator.health();

    assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
    assertThat(health.getStatus().getDescription()).isNotBlank();
    assertThat(health.getDetails())
        .containsEntry("availableSlots", 2)
        .containsEntry(
            "workersMissingEncoders",
            Map.of(workerId.toString(), List.of(missingFamilies.split(","))));
  }

  @Test
  @DisplayName(
      "Should name only the workers that lack an encoder when another worker encodes every codec family")
  void shouldNameOnlyTheWorkersThatLackAnEncoderWhenAnotherWorkerEncodesEveryCodecFamily() {
    var completeWorker = UUID.randomUUID();
    var brokenWorker = UUID.randomUUID();
    var executor = new FakeTranscodeExecutor();
    executor.setVerifiedVideoCodecFamiliesByWorker(
        List.of(worker(completeWorker, Set.of("h264", "av1")), worker(brokenWorker, Set.of())));
    var indicator = new TranscodeExecutorHealthIndicator(executor);

    var health = indicator.health();

    assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
    assertThat(health.getDetails())
        .containsEntry(
            "workersMissingEncoders", Map.of(brokenWorker.toString(), List.of("av1", "h264")));
  }

  @Test
  @DisplayName("Should report up when every worker encodes every codec family the server encodes")
  void shouldReportUpWhenEveryWorkerEncodesEveryCodecFamilyTheServerEncodes() {
    var executor = new FakeTranscodeExecutor();
    executor.setAvailableSlots(1);
    executor.setVerifiedVideoCodecFamiliesByWorker(
        List.of(
            worker(UUID.randomUUID(), Set.of("h264", "av1")),
            worker(UUID.randomUUID(), Set.of("h264", "hevc", "av1"))));
    var indicator = new TranscodeExecutorHealthIndicator(executor);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).doesNotContainKey("workersMissingEncoders");
  }

  private static WorkerVerifiedCodecFamilies worker(UUID workerId, Set<String> codecFamilies) {
    return WorkerVerifiedCodecFamilies.builder()
        .workerId(workerId)
        .codecFamilies(codecFamilies)
        .build();
  }
}
