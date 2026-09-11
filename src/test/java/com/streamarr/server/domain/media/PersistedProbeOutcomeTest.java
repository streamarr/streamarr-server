package com.streamarr.server.domain.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Persisted probe outcome freshness")
class PersistedProbeOutcomeTest {

  @ParameterizedTest
  @MethodSource("requestedInputs")
  @DisplayName("Should skip probing only when the source snapshot and requested version match")
  void shouldSkipProbingOnlyWhenSourceSnapshotAndRequestedVersionMatch(
      SourceFileSnapshot requestedSnapshot, int requestedVersion, boolean matches) {
    var outcome =
        new PersistedProbeOutcome(
            new SourceFileSnapshot(1234, Instant.EPOCH),
            2,
            new ProbeOutcome.Failure(ProbeError.NO_VIDEO_STREAM));

    assertThat(outcome.matches(requestedSnapshot, requestedVersion)).isEqualTo(matches);
  }

  private static Stream<Arguments> requestedInputs() {
    return Stream.of(
        Arguments.of(new SourceFileSnapshot(1234, Instant.EPOCH), 2, true),
        Arguments.of(new SourceFileSnapshot(1233, Instant.EPOCH), 2, false),
        Arguments.of(new SourceFileSnapshot(1234, Instant.EPOCH.minusNanos(1)), 2, false),
        Arguments.of(new SourceFileSnapshot(1234, Instant.EPOCH), 1, false),
        Arguments.of(new SourceFileSnapshot(1234, Instant.EPOCH), 3, false));
  }

  @ParameterizedTest
  @CsvSource({"1, true", "2, true", "3, false"})
  @DisplayName(
      "Should retain compatible older successes while requiring refresh to the current version")
  void shouldRetainCompatibleOlderSuccessesWhileRequiringRefreshToCurrentVersion(
      int storedVersion, boolean compatible) {
    var outcome =
        new PersistedProbeOutcome(
            new SourceFileSnapshot(1234, Instant.EPOCH),
            storedVersion,
            new ProbeOutcome.Success(
                ProbeContainer.builder().build(),
                List.of(
                    StreamInfo.builder()
                        .index(0)
                        .codecType("video")
                        .codec(Optional.of("h264"))
                        .build())));

    assertThat(outcome.isCompatibleWith(2)).isEqualTo(compatible);
    assertThat(outcome.matches(outcome.snapshot(), 2)).isEqualTo(storedVersion == 2);
  }
}
