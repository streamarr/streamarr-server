package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.FakeTranscodeExecutor.HeldBack;
import com.streamarr.server.fakes.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The default marks: held above 60 s of own media ahead of the viewer, released at 30 s. */
@Tag("UnitTest")
@DisplayName("Hold-Back Tests")
class HoldBackTest {

  private static final ExecutionTargetId WORKER = new ExecutionTargetId("worker");
  private static final ExecutionTargetId OTHER_WORKER = new ExecutionTargetId("other-worker");

  private final FakeTranscodeExecutor executor = new FakeTranscodeExecutor();
  private final MutableClock clock = new MutableClock();
  private final HoldBack holdBack =
      new HoldBack(executor, StreamingProperties.builder().build(), clock);
  private final UUID sessionId = UUID.randomUUID();
  private final UUID attemptId = UUID.randomUUID();

  @Test
  @DisplayName(
      "Should hold back a job attempt only once its lead exceeds the high-water mark, until its"
          + " viewer reaches the low-water mark")
  void shouldHoldBackAJobAttemptOnlyOnceItsLeadExceedsTheHighWaterMark() {
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 60)));
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 61)));

    assertThat(executor.holdBacks())
        .containsExactly(new HeldBack(WORKER, Map.of(attemptId, Duration.ofSeconds(31))));
  }

  @Test
  @DisplayName(
      "Should keep a held job attempt's release without another command while its viewer plays at"
          + " real time")
  void shouldKeepAHeldJobAttemptsReleaseWithoutAnotherCommandWhileItsViewerPlaysAtRealTime() {
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 66)));

    clock.advance(Duration.ofSeconds(6));
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 60)));
    clock.advance(Duration.ofSeconds(6));
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 54)));

    assertThat(executor.holdBacks()).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should send an earlier release when the viewer moves ahead faster than real time while the"
          + " job attempt stays above the low-water mark")
  void shouldSendAnEarlierReleaseWhenTheViewerMovesAheadFasterThanRealTime() {
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 66)));

    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 40)));

    assertThat(executor.holdBacks().getLast())
        .isEqualTo(new HeldBack(WORKER, Map.of(attemptId, Duration.ofSeconds(10))));
  }

  @Test
  @DisplayName("Should release a held job attempt once its lead falls to the low-water mark")
  void shouldReleaseAHeldJobAttemptOnceItsLeadFallsToTheLowWaterMark() {
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 66)));

    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 30)));

    assertThat(executor.holdBacks().getLast()).isEqualTo(new HeldBack(WORKER, Map.of()));
  }

  @Test
  @DisplayName(
      "Should hold back again only above the high-water mark once the worker released a job"
          + " attempt at its release time")
  void shouldHoldBackAgainOnlyAboveTheHighWaterMarkOnceTheWorkerReleasedAJobAttempt() {
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 66)));
    clock.advance(Duration.ofSeconds(37));

    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 50)));
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 61)));

    assertThat(executor.holdBacks())
        .containsExactly(
            new HeldBack(WORKER, Map.of(attemptId, Duration.ofSeconds(36))),
            new HeldBack(WORKER, Map.of(attemptId, Duration.ofSeconds(31))));
  }

  @Test
  @DisplayName(
      "Should keep another stream session's hold on the same worker when one stream session's job"
          + " attempt is released")
  void shouldKeepAnotherStreamSessionsHoldOnTheSameWorkerWhenOneIsReleased() {
    var otherSession = UUID.randomUUID();
    var otherAttempt = UUID.randomUUID();
    holdBack.reconsider(otherSession, List.of(lead(WORKER, otherAttempt, 70)));
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 66)));

    holdBack.reconsider(sessionId, List.of());

    assertThat(executor.holdBacks())
        .containsExactly(
            new HeldBack(WORKER, Map.of(otherAttempt, Duration.ofSeconds(40))),
            new HeldBack(
                WORKER,
                Map.of(otherAttempt, Duration.ofSeconds(40), attemptId, Duration.ofSeconds(36))),
            new HeldBack(WORKER, Map.of(otherAttempt, Duration.ofSeconds(40))));
  }

  @Test
  @DisplayName(
      "Should send each worker only its own holds and release them all when the stream session"
          + " ends")
  void shouldSendEachWorkerOnlyItsOwnHoldsAndReleaseThemAllWhenTheStreamSessionEnds() {
    var otherAttempt = UUID.randomUUID();
    holdBack.reconsider(
        sessionId, List.of(lead(WORKER, attemptId, 66), lead(OTHER_WORKER, otherAttempt, 90)));

    holdBack.forget(sessionId);

    assertThat(executor.holdBacks())
        .containsExactlyInAnyOrder(
            new HeldBack(WORKER, Map.of(attemptId, Duration.ofSeconds(36))),
            new HeldBack(OTHER_WORKER, Map.of(otherAttempt, Duration.ofSeconds(60))),
            new HeldBack(WORKER, Map.of()),
            new HeldBack(OTHER_WORKER, Map.of()));
  }

  private static HoldBack.AttemptLead lead(ExecutionTargetId target, UUID attemptId, long seconds) {
    return new HoldBack.AttemptLead(attemptId, target, Duration.ofSeconds(seconds));
  }
}
