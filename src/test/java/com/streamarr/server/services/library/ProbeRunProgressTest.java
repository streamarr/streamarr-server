package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.ProbeCapacity;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Probe Run Progress Tests")
class ProbeRunProgressTest {

  @ParameterizedTest(name = "finished={0}, elapsed={1} ms")
  @CsvSource({"120, 30000, 4.0", "3, 2000, 1.5", "0, 30000, 0.0", "5, 0, 0.0"})
  @DisplayName(
      "Should divide the finished probes by the time since the first request when computing the"
          + " files per second")
  void shouldDivideTheFinishedProbesByTheTimeSinceTheFirstRequestWhenComputingTheFilesPerSecond(
      int finished, long elapsedMillis, double filesPerSecond) {
    var progress =
        ProbeRunProgress.builder()
            .finished(finished)
            .pending(10)
            .elapsed(Duration.ofMillis(elapsedMillis))
            .capacity(ProbeCapacity.builder().backgroundProbeCapacity(4).build())
            .build();

    assertThat(progress.filesPerSecond()).isEqualTo(filesPerSecond);
  }
}
