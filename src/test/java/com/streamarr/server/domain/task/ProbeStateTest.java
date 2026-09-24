package com.streamarr.server.domain.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeError;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe state")
class ProbeStateTest {

  private static final ProbeInputs REQUESTED =
      new ProbeInputs(new SourceFileSnapshot(10, Instant.EPOCH), ProbeVersion.CURRENT);
  private static final ProbeInputs CHANGED =
      new ProbeInputs(new SourceFileSnapshot(11, Instant.EPOCH), ProbeVersion.CURRENT);
  private static final Instant REQUESTED_AT = Instant.parse("2026-09-23T12:00:00Z");
  private static final RequestedProbe REQUESTED_PROBE = new RequestedProbe(REQUESTED, REQUESTED_AT);

  @Test
  @DisplayName("Should be ready when a successful outcome matches the requested inputs")
  void shouldBeReadyWhenASuccessfulOutcomeMatchesTheRequestedInputs() {
    var state = state().stored(Optional.of(stored(REQUESTED, Optional.empty()))).build();

    assertThat(state.resultFor(REQUESTED_PROBE)).isEqualTo(RequestedProbeResult.READY);
  }

  @Test
  @DisplayName("Should report a media error when a terminal outcome matches the requested inputs")
  void shouldReportAMediaErrorWhenATerminalOutcomeMatchesTheRequestedInputs() {
    var state =
        state()
            .stored(Optional.of(stored(REQUESTED, Optional.of(ProbeError.INVALID_MEDIA))))
            .build();

    assertThat(state.resultFor(REQUESTED_PROBE)).isEqualTo(RequestedProbeResult.MEDIA_ERROR);
  }

  @Test
  @DisplayName("Should report a failure when an attempt at the requested inputs failed")
  void shouldReportAFailureWhenAnAttemptAtTheRequestedInputsFailed() {
    var state = state().failure(Optional.of(failedAt(REQUESTED_AT.plusSeconds(1)))).build();

    assertThat(state.resultFor(REQUESTED_PROBE)).isEqualTo(RequestedProbeResult.FAILED);
  }

  @Test
  @DisplayName("Should stay pending when the recorded failure predates the request")
  void shouldStayPendingWhenTheRecordedFailurePredatesTheRequest() {
    var state = state().failure(Optional.of(failedAt(REQUESTED_AT.minusSeconds(1)))).build();

    assertThat(state.resultFor(REQUESTED_PROBE)).isEqualTo(RequestedProbeResult.PENDING);
  }

  @Test
  @DisplayName("Should be pending when no attempt at the requested inputs has finished")
  void shouldBePendingWhenNoAttemptAtTheRequestedInputsHasFinished() {
    var state = state().stored(Optional.of(stored(CHANGED, Optional.empty()))).build();

    assertThat(state.resultFor(REQUESTED_PROBE)).isEqualTo(RequestedProbeResult.PENDING);
  }

  @Test
  @DisplayName("Should be superseded when later inputs replaced the requested inputs")
  void shouldBeSupersededWhenLaterInputsReplacedTheRequestedInputs() {
    var state = state().requested(Optional.of(CHANGED)).build();

    assertThat(state.resultFor(REQUESTED_PROBE)).isEqualTo(RequestedProbeResult.SUPERSEDED);
  }

  @Test
  @DisplayName(
      "Should be probed by a newer version when a newer probe version stored an outcome for the"
          + " source")
  void shouldBeProbedByANewerVersionWhenANewerProbeVersionStoredAnOutcomeForTheSource() {
    var newerVersion = new ProbeInputs(REQUESTED.snapshot(), REQUESTED.probeVersion() + 1);
    var state = state().stored(Optional.of(stored(newerVersion, Optional.empty()))).build();

    assertThat(state.resultFor(REQUESTED_PROBE))
        .isEqualTo(RequestedProbeResult.PROBED_BY_NEWER_VERSION);
  }

  @Test
  @DisplayName("Should be removed when no probe is requested and no outcome matches")
  void shouldBeRemovedWhenNoProbeIsRequestedAndNoOutcomeMatches() {
    var state = state().requested(Optional.empty()).build();

    assertThat(state.resultFor(REQUESTED_PROBE)).isEqualTo(RequestedProbeResult.REMOVED);
  }

  private static ProbeAttemptFailure failedAt(Instant failedAt) {
    return ProbeAttemptFailure.builder()
        .reason(ItemFailureReason.SOURCE_INACCESSIBLE)
        .detail("Worker could not read the source")
        .failedAt(failedAt)
        .build();
  }

  private static ProbeState.ProbeStateBuilder state() {
    return ProbeState.builder()
        .mediaFileId(UUID.randomUUID())
        .requested(Optional.of(REQUESTED))
        .stored(Optional.empty())
        .failure(Optional.empty());
  }

  private static ProbeState.Stored stored(ProbeInputs inputs, Optional<ProbeError> error) {
    return new ProbeState.Stored(inputs, error);
  }
}
