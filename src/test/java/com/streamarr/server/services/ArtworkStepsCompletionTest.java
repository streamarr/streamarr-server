package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.github.kagkarlsson.scheduler.event.SchedulerListeners;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.NewValue;
import com.github.kagkarlsson.scheduler.task.RescheduleUpdate;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.fakes.FakeArtworkRequestRepository;
import com.streamarr.server.fakes.FakeItemResultRepository;
import com.streamarr.server.fakes.FakeTaskRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Artwork Steps Completion Tests")
class ArtworkStepsCompletionTest {

  private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
  private static final ArtworkTarget MOVIE =
      new ArtworkTarget(UUID.randomUUID(), ImageEntityType.MOVIE);

  private final FakeArtworkRequestRepository requests =
      new FakeArtworkRequestRepository(new FakeItemResultRepository());
  private final FakeTaskRepository artworkTasks = new FakeTaskRepository();
  private final ArtworkStepsCompletion completion =
      ArtworkStepsCompletion.builder()
          .requests(requests)
          .transactionManager(new FakeTransactionManager())
          .artworkTasks(artworkTasks)
          .clock(Clock.fixed(NOW, ZoneOffset.UTC))
          .build();

  @Test
  @DisplayName("Should remove the task and the request when the attempted generation finished")
  void shouldRemoveTheTaskAndTheRequestWhenTheAttemptedGenerationFinished() {
    var generation = request("/poster.jpg");
    var execution = execution(0);

    complete(new ArtworkStepsOutcome.Finished(generation), execution);

    assertThat(artworkTasks.removed()).containsExactly(execution);
    assertThat(requests.requests()).isEmpty();
  }

  @Test
  @DisplayName("Should run a newer generation at once without failures when an older one finished")
  void shouldRunANewerGenerationAtOnceWithoutFailuresWhenAnOlderOneFinished() {
    var older = request("/poster.jpg");
    request("/newer-poster.jpg");

    complete(new ArtworkStepsOutcome.Finished(older), execution(3));

    assertThat(artworkTasks.removed()).isEmpty();
    assertThat(artworkTasks.rescheduled()).containsExactly(runNowWithoutFailures());
    assertThat(requests.requests()).hasSize(1);
  }

  @Test
  @DisplayName("Should retry after the image host's delay when it is longer than the backoff")
  void shouldRetryAfterTheImageHostsDelayWhenItIsLongerThanTheBackoff() {
    var generation = request("/poster.jpg");

    complete(
        new ArtworkStepsOutcome.Unfinished(generation, Optional.of(Duration.ofSeconds(90))),
        execution(1));

    assertThat(artworkTasks.rescheduled()).containsExactly(retryAt(NOW.plusSeconds(90), 2));
  }

  @Test
  @DisplayName("Should retry after the capped backoff when the image host names no delay")
  void shouldRetryAfterTheCappedBackoffWhenTheImageHostNamesNoDelay() {
    var generation = request("/poster.jpg");

    complete(new ArtworkStepsOutcome.Unfinished(generation, Optional.empty()), execution(10));

    assertThat(artworkTasks.rescheduled())
        .containsExactly(retryAt(NOW.plus(Duration.ofMinutes(5)), 11));
  }

  @Test
  @DisplayName(
      "Should run an attempt again at once without counting a failure when it was interrupted")
  void shouldRunAnAttemptAgainAtOnceWithoutCountingAFailureWhenItWasInterrupted() {
    var generation = request("/poster.jpg");

    complete(new ArtworkStepsOutcome.Interrupted(generation), execution(2));

    assertThat(artworkTasks.rescheduled())
        .containsExactly(new RescheduleUpdate(NOW, null, null, null, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("outcomesLeftToRun")
  @DisplayName(
      "Should run the newer generation at once without failures when it replaced the attempt's")
  void shouldRunTheNewerGenerationAtOnceWithoutFailuresWhenItReplacedTheAttempts(
      String name, ArtworkStepsOutcome outcome) {
    request("/poster.jpg");
    request("/newer-poster.jpg");

    complete(outcome, execution(4));

    assertThat(artworkTasks.removed()).isEmpty();
    assertThat(artworkTasks.rescheduled()).containsExactly(runNowWithoutFailures());
  }

  private static Stream<Arguments> outcomesLeftToRun() {
    return Stream.of(
        Arguments.of("unfinished", new ArtworkStepsOutcome.Unfinished(1, Optional.empty())),
        Arguments.of("interrupted", new ArtworkStepsOutcome.Interrupted(1)),
        Arguments.of("not requested", new ArtworkStepsOutcome.NotRequested()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("outcomesLeftToRun")
  @DisplayName("Should remove the task when no request remains after an unfinished attempt")
  void shouldRemoveTheTaskWhenNoRequestRemainsAfterAnUnfinishedAttempt(
      String name, ArtworkStepsOutcome outcome) {
    var execution = execution(1);

    complete(outcome, execution);

    assertThat(artworkTasks.removed()).containsExactly(execution);
    assertThat(artworkTasks.rescheduled()).isEmpty();
  }

  @Test
  @DisplayName("Should remove the task when an attempt failed unexpectedly and no request remains")
  void shouldRemoveTheTaskWhenAnAttemptFailedUnexpectedlyAndNoRequestRemains() {
    var execution = execution(0);

    fail(execution);

    assertThat(artworkTasks.removed()).containsExactly(execution);
  }

  @Test
  @DisplayName(
      "Should retry after the backoff and count a failure when an attempt failed unexpectedly")
  void shouldRetryAfterTheBackoffAndCountAFailureWhenAnAttemptFailedUnexpectedly() {
    request("/poster.jpg");

    fail(execution(0));

    assertThat(artworkTasks.rescheduled()).containsExactly(retryAt(NOW.plusSeconds(5), 1));
  }

  @Test
  @DisplayName("Should reject a completion when a collaborator is missing")
  void shouldRejectACompletionWhenACollaboratorIsMissing() {
    var transactionManager = new FakeTransactionManager();
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);

    assertThatNullPointerException()
        .isThrownBy(
            () -> new ArtworkStepsCompletion(null, transactionManager, artworkTasks, clock));
    assertThatNullPointerException()
        .isThrownBy(() -> new ArtworkStepsCompletion(requests, null, artworkTasks, clock));
    assertThatNullPointerException()
        .isThrownBy(() -> new ArtworkStepsCompletion(requests, transactionManager, null, clock));
    assertThatNullPointerException()
        .isThrownBy(
            () -> new ArtworkStepsCompletion(requests, transactionManager, artworkTasks, null));
  }

  private long request(String key) {
    var artwork =
        ArtworkSources.builder()
            .targetId(MOVIE.targetId())
            .targetType(MOVIE.targetType())
            .sources(List.of(new TmdbImageSource(ImageType.POSTER, key)))
            .build();
    return requests.saveRequest(artwork, ImageRefreshMode.PRESERVE, NOW).generation();
  }

  private void complete(ArtworkStepsOutcome outcome, Execution execution) {
    completion
        .handlerFor(MOVIE, outcome)
        .complete(ExecutionComplete.success(execution, NOW, NOW), operations(execution));
  }

  private void fail(Execution execution) {
    completion
        .failureHandler()
        .onFailure(
            ExecutionComplete.failure(
                execution, NOW, NOW, new IllegalStateException("database unavailable")),
            operations(execution));
  }

  private ExecutionOperations<ArtworkTarget> operations(Execution execution) {
    return new ExecutionOperations<>(artworkTasks, SchedulerListeners.NOOP, execution);
  }

  private static Execution execution(int consecutiveFailures) {
    var instance =
        new TaskInstance<>("primary-artwork-steps", ArtworkStepsTask.instanceId(MOVIE), MOVIE);
    return new Execution(NOW, instance, true, "server", null, null, consecutiveFailures, NOW, 1L);
  }

  private static RescheduleUpdate runNowWithoutFailures() {
    return RescheduleUpdate.toExecutionTime(NOW).resetFailures().build();
  }

  private static RescheduleUpdate retryAt(Instant executionTime, int consecutiveFailures) {
    return new RescheduleUpdate(
        executionTime, null, null, new NewValue<>(NOW), new NewValue<>(consecutiveFailures));
  }
}
