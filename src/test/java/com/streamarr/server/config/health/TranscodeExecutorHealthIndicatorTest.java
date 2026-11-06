package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.WorkerVerifiedCodecFamilies;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
  @MethodSource("codecFamiliesMissingAnEncoder")
  @DisplayName(
      "Should report degraded without naming the worker when it lacks a worker-verified encoder for a codec family the server encodes")
  void
      shouldReportDegradedWithoutNamingTheWorkerWhenItLacksAWorkerVerifiedEncoderForACodecFamilyTheServerEncodes(
          Set<String> verifiedFamilies) {
    var executor = new FakeTranscodeExecutor();
    executor.setAvailableSlots(2);
    var worker = worker(verifiedFamilies);
    executor.setVerifiedVideoCodecFamiliesByWorker(List.of(worker));
    var indicator = new TranscodeExecutorHealthIndicator(executor);

    var health = indicator.health();

    assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
    assertThat(health.getStatus().getDescription())
        .isNotBlank()
        .doesNotContain(worker.workerId().toString());
    assertThat(health.getDetails()).containsExactly(entry("availableSlots", 2));
  }

  private static Stream<Set<String>> codecFamiliesMissingAnEncoder() {
    return Stream.of(Set.of("h264"), Set.of("av1"), Set.of("hevc", "av1"));
  }

  @Test
  @DisplayName(
      "Should report degraded when one worker lacks an encoder and another worker encodes every codec family")
  void shouldReportDegradedWhenOneWorkerLacksAnEncoderAndAnotherWorkerEncodesEveryCodecFamily() {
    var executor = new FakeTranscodeExecutor();
    executor.setAvailableSlots(TranscodeExecutor.UNBOUNDED_SLOTS);
    executor.setVerifiedVideoCodecFamiliesByWorker(
        List.of(worker(Set.of("h264", "av1")), worker(Set.of())));
    var indicator = new TranscodeExecutorHealthIndicator(executor);

    var health = indicator.health();

    assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
    assertThat(health.getDetails()).containsExactly(entry("capacity", "unbounded"));
  }

  @Test
  @DisplayName("Should report up when every worker encodes every codec family the server encodes")
  void shouldReportUpWhenEveryWorkerEncodesEveryCodecFamilyTheServerEncodes() {
    var executor = new FakeTranscodeExecutor();
    executor.setAvailableSlots(1);
    executor.setVerifiedVideoCodecFamiliesByWorker(
        List.of(worker(Set.of("h264", "hevc", "av1")), worker(Set.of("h264", "hevc", "av1"))));
    var indicator = new TranscodeExecutorHealthIndicator(executor);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsExactly(entry("availableSlots", 1));
  }

  private static WorkerVerifiedCodecFamilies worker(Set<String> codecFamilies) {
    return WorkerVerifiedCodecFamilies.builder()
        .workerId(UUID.randomUUID())
        .codecFamilies(codecFamilies)
        .build();
  }
}
