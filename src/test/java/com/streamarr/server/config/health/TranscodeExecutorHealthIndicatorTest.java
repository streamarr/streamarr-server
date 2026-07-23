package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
}
