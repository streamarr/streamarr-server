package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.FakeTranscodeExecutor.HeldBack;
import com.streamarr.server.fakes.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final HoldBack holdBack =
      new HoldBack(executor, StreamingProperties.builder().build(), clock, meters);
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
            new HeldBack(WORKER, Map.of()),
            new HeldBack(WORKER, Map.of(attemptId, Duration.ofSeconds(31))));
  }

  @Test
  @DisplayName(
      "Should send a command without a job attempt before a new hold of it once the release time"
          + " that the server counted has passed")
  void shouldSendACommandWithoutAJobAttemptBeforeANewHoldOfItOnceTheReleaseTimeHasPassed() {
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 66)));
    clock.advance(Duration.ofSeconds(37));

    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 72)));

    assertThat(executor.holdBacks())
        .containsExactly(
            new HeldBack(WORKER, Map.of(attemptId, Duration.ofSeconds(36))),
            new HeldBack(WORKER, Map.of()),
            new HeldBack(WORKER, Map.of(attemptId, Duration.ofSeconds(42))));
  }

  @Test
  @DisplayName(
      "Should release a job attempt explicitly when its player waits after the release time that the"
          + " server counted, since the worker may have received the hold late")
  void
      shouldReleaseAJobAttemptExplicitlyWhenItsPlayerWaitsAfterTheReleaseTimeThatTheServerCounted() {
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 66)));
    clock.advance(Duration.ofSeconds(40));

    holdBack.reconsider(sessionId, List.of());

    assertThat(executor.holdBacks().getLast()).isEqualTo(new HeldBack(WORKER, Map.of()));
  }

  @Test
  @DisplayName(
      "Should raise both marks by how much the longest media segment ahead outlasts the target"
          + " segment duration, so a resume leaves the viewer time to produce it")
  void shouldRaiseBothMarksByHowMuchTheLongestMediaSegmentAheadOutlastsTheTargetSegmentDuration() {
    // A 45 s merged segment ahead outlasts the 6 s target by 39 s: the job attempt is held above 99
    // s
    // and released at 69 s.
    holdBack.reconsider(
        sessionId,
        List.of(
            leadBuilder(WORKER, attemptId, 99)
                .longestSegmentAhead(Duration.ofSeconds(45))
                .build()));
    holdBack.reconsider(
        sessionId,
        List.of(
            leadBuilder(WORKER, attemptId, 100)
                .longestSegmentAhead(Duration.ofSeconds(45))
                .build()));

    assertThat(executor.holdBacks())
        .containsExactly(new HeldBack(WORKER, Map.of(attemptId, Duration.ofSeconds(31))));
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

  @Test
  @DisplayName(
      "Should count each hold-back command, each hold started and each hold ended, a lapsed hold"
          + " held again counting as both")
  void shouldCountEachHoldBackCommandEachHoldStartedAndEachHoldEnded() {
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 66)));
    clock.advance(Duration.ofSeconds(37));
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 72)));

    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 30)));

    assertThat(executor.holdBacks()).hasSize(4);
    assertThat(count("streamarr.streaming.hold_back_commands")).isEqualTo(4);
    assertThat(count("streamarr.streaming.hold_backs")).isEqualTo(2);
    assertThat(count("streamarr.streaming.hold_back_releases")).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should count no hold started or ended when a command only moves a held job attempt's"
          + " release earlier")
  void shouldCountNoHoldStartedOrEndedWhenACommandOnlyMovesAHeldJobAttemptsReleaseEarlier() {
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 66)));

    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 40)));

    assertThat(executor.holdBacks()).hasSize(2);
    assertThat(count("streamarr.streaming.hold_back_commands")).isEqualTo(2);
    assertThat(count("streamarr.streaming.hold_backs")).isEqualTo(1);
    assertThat(count("streamarr.streaming.hold_back_releases")).isZero();
  }

  @Test
  @DisplayName(
      "Should count another stream session's hold on the same worker once, however many commands"
          + " list it")
  void shouldCountAnotherStreamSessionsHoldOnTheSameWorkerOnceHoweverManyCommandsListIt() {
    var otherSession = UUID.randomUUID();
    var otherAttempt = UUID.randomUUID();
    holdBack.reconsider(otherSession, List.of(lead(WORKER, otherAttempt, 70)));
    holdBack.reconsider(sessionId, List.of(lead(WORKER, attemptId, 66)));

    holdBack.forget(sessionId);

    assertThat(executor.holdBacks()).hasSize(3);
    assertThat(count("streamarr.streaming.hold_backs")).isEqualTo(2);
    assertThat(count("streamarr.streaming.hold_back_releases")).isEqualTo(1);
  }

  private double count(String name) {
    return meters.counter(name).count();
  }

  // A lead whose segments ahead last the plain 6 s.
  private static HoldBack.AttemptLead lead(ExecutionTargetId target, UUID attemptId, long seconds) {
    return leadBuilder(target, attemptId, seconds).build();
  }

  private static HoldBack.AttemptLead.AttemptLeadBuilder leadBuilder(
      ExecutionTargetId target, UUID attemptId, long seconds) {
    return HoldBack.AttemptLead.builder()
        .attemptId(attemptId)
        .target(target)
        .lead(Duration.ofSeconds(seconds))
        .longestSegmentAhead(Duration.ofSeconds(6));
  }
}
