package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.fixtures.StreamSessionFixture.remuxDecision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.PlaybackSessionClosedException;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Playback Session Service Tests")
class PlaybackSessionServiceTest {
  private final ChildStreams streams = new ChildStreams();
  private final FakePlaybackAuthorityGate authority = new FakePlaybackAuthorityGate();
  private final MutableClock clock = new MutableClock();
  private final PlaybackSessionService service =
      PlaybackSessionService.builder()
          .streamingService(streams)
          .authorityGate(authority)
          .executor(Runnable::run)
          .maximumFormatAttempts(2)
          .sessionRetention(Duration.ofHours(24))
          .clock(clock)
          .build();

  @Test
  @DisplayName("Should count the initial format attempt when playback starts")
  void shouldCountTheInitialFormatAttemptWhenPlaybackStarts() {
    var command = startCommand().build();
    var snapshot = accepted(service.start(command));

    assertThat(snapshot.formatAttemptCount()).isEqualTo(1);
    assertThat(snapshot.maximumFormatAttempts()).isEqualTo(2);
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getMediaFileId()).isEqualTo(command.mediaFileId());
              assertThat(ready.streamSessionId()).isNotEqualTo(snapshot.playbackSessionId());
              assertThat(streams.getAllSessions()).containsExactly(ready.session());
            });
  }

  @Test
  @DisplayName("Should replace the copy when its mismatch arrives before initial creation returns")
  void shouldReplaceTheCopyWhenItsMismatchArrivesBeforeInitialCreationReturns() {
    var initialId = new AtomicReference<UUID>();
    streams.duringNextCreation =
        command -> {
          initialId.set(command.streamSessionId());
          service.onVideoCopyOutputMismatch(
              new VideoCopyOutputMismatchEvent(
                  command.streamSessionId(), UUID.randomUUID(), "default"));
        };
    var command = startCommand().build();

    var snapshot = accepted(service.start(command));

    assertThat(snapshot.formatAttemptCount()).isEqualTo(2);
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.streamSessionId()).isNotEqualTo(initialId.get());
              assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                  .isFalse();
              assertThat(ready.session().getOptions()).isEqualTo(command.options());
              assertThat(streams.getAllSessions()).containsExactly(ready.session());
            });
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4})
  @DisplayName("Should bound total format attempts when Auto reports repeated format failures")
  void shouldBoundTotalFormatAttemptsWhenAutoReportsRepeatedFormatFailures(int maximum) {
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(Runnable::run)
            .maximumFormatAttempts(maximum)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var snapshot = accepted(playback.start(command));
    for (var attempt = 1; attempt <= maximum; attempt++) {
      snapshot = playback.reportFormatFailure(report(snapshot, command).build()).orElseThrow();
    }

    assertThat(snapshot.formatAttemptCount()).isEqualTo(maximum);
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            failed ->
                assertThat(failed.reason())
                    .isEqualTo(new PlaybackSessionFailure.AttemptsExhausted()));
    assertThat(streams.getAllSessions()).isEmpty();
    assertThat(accepted(playback.start(command)).formatAttemptCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(value = VideoQuality.class, mode = EnumSource.Mode.EXCLUDE, names = "AUTO")
  @DisplayName("Should terminate the chosen format when fixed quality receives a copy mismatch")
  void shouldTerminateTheChosenFormatWhenFixedQualityReceivesACopyMismatch(VideoQuality quality) {
    var command =
        startCommand()
            .options(
                StreamingOptions.builder()
                    .quality(quality)
                    .supportedCodecs(List.of("h264"))
                    .build())
            .build();
    var snapshot = accepted(service.start(command));
    var failedId = snapshot.currentFormatAttempt().streamSessionId();
    service.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(failedId, UUID.randomUUID(), "default"));

    var failed = service.access(request(snapshot, command)).orElseThrow();
    assertThat(failed.formatAttemptCount()).isEqualTo(1);
    assertThat(failed.currentFormatAttempt())
        .isEqualTo(
            new PlaybackFormatAttempt.Failed(
                failedId, new PlaybackSessionFailure.OutputMismatch()));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should retain a typed client failure when the chosen quality is fixed")
  void shouldRetainATypedClientFailureWhenTheChosenQualityIsFixed() {
    var command =
        startCommand()
            .options(
                StreamingOptions.builder()
                    .quality(VideoQuality.ORIGINAL)
                    .supportedCodecs(List.of("h264"))
                    .build())
            .build();
    var snapshot = accepted(service.start(command));
    var failed =
        service
            .reportFormatFailure(
                report(snapshot, command).reason(PlaybackFormatFailure.UNSUPPORTED_FORMAT).build())
            .orElseThrow();
    assertThat(failed.currentFormatAttempt())
        .isEqualTo(
            new PlaybackFormatAttempt.Failed(
                snapshot.currentFormatAttempt().streamSessionId(),
                new PlaybackSessionFailure.FixedFormatFailed(
                    PlaybackFormatFailure.UNSUPPORTED_FORMAT)));
    assertThat(failed.formatAttemptCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("Should reject the total attempt limit when it is not positive")
  void shouldRejectTheTotalAttemptLimitWhenItIsNotPositive(int maximum) {
    var builder =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(Runnable::run)
            .maximumFormatAttempts(maximum)
            .sessionRetention(Duration.ofHours(24));

    assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reserve one replacement when client and worker failures race")
  void shouldReserveOneReplacementWhenClientAndWorkerFailuresRace() throws Exception {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(3)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    var ready = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var client =
          threads.submit(
              () -> {
                ready.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return playback.reportFormatFailure(report(initial, command).build());
              });
      var worker =
          threads.submit(
              () -> {
                ready.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                playback.onVideoCopyOutputMismatch(
                    new VideoCopyOutputMismatchEvent(
                        initial.currentFormatAttempt().streamSessionId(),
                        UUID.randomUUID(),
                        "default"));
                return true;
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      client.get(5, TimeUnit.SECONDS);
      worker.get(5, TimeUnit.SECONDS);
    }

    var pending = playback.access(request(initial, command)).orElseThrow();
    assertThat(pending.formatAttemptCount()).isEqualTo(2);
    assertThat(pending.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Pending.class);
    assertThat(playback.reportFormatFailure(report(pending, command).build())).contains(pending);
    drain(work);
    var result = playback.access(request(initial, command)).orElseThrow();
    assertThat(result.formatAttemptCount()).isEqualTo(2);
    assertThat(result.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(playback.reportFormatFailure(report(initial, command).build())).contains(result);
    playback.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(
            initial.currentFormatAttempt().streamSessionId(), UUID.randomUUID(), "default"));
    assertThat(playback.access(request(initial, command))).contains(result);
    assertThat(streams.getActiveSessionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should refuse playback creation when live authority is denied")
  void shouldRefusePlaybackCreationWhenLiveAuthorityIsDenied() {
    authority.deny();
    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse pending status and reports when live authority is revoked")
  void shouldRefusePendingStatusAndReportsWhenLiveAuthorityIsRevoked() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    var pending = playback.reportFormatFailure(report(initial, command).build()).orElseThrow();
    authority.deny();
    var pendingRequest = request(initial, command);
    var pendingReport = report(pending, command).build();
    assertThatThrownBy(() -> playback.access(pendingRequest))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(() -> playback.reportFormatFailure(pendingReport))
        .isInstanceOf(AuthenticationRequiredException.class);
    drain(work);
    assertThat(playback.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"authSessionId", "accountId", "householdId"})
  @DisplayName("Should refuse another authority when its profile matches the playback owner")
  void shouldRefuseAnotherAuthorityWhenItsProfileMatchesThePlaybackOwner(String changedField) {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    var owner = command.identity().playbackAuthority();
    var foreignAuthority =
        defaultPlaybackAuthorityBuilder()
            .authSessionId(owner.authSessionId())
            .accountId(owner.accountId())
            .householdId(owner.householdId())
            .profileId(owner.profileId());
    var foreign =
        identityFor(
            switch (changedField) {
              case "authSessionId" -> foreignAuthority.authSessionId(UUID.randomUUID()).build();
              case "accountId" -> foreignAuthority.accountId(UUID.randomUUID()).build();
              case "householdId" -> foreignAuthority.householdId(UUID.randomUUID()).build();
              default -> throw new IllegalArgumentException(changedField);
            });
    var foreignRequest =
        PlaybackSessionRequest.builder()
            .playbackSessionId(initial.playbackSessionId())
            .identity(foreign)
            .build();
    var foreignReport = report(initial, command).identity(foreign).build();
    assertThat(service.access(foreignRequest)).isEmpty();
    assertThat(service.reportFormatFailure(foreignReport)).isEmpty();
    service.close(foreignRequest);

    assertThat(service.access(request(initial, command))).contains(initial);
    assertThat(streams.getActiveSessionCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({
    "stream,true",
    "stream,false",
    "media,true",
    "media,false",
    "shutdown,true",
    "shutdown,false"
  })
  @DisplayName("Should discard late creation when its logical playback closes during startup")
  void shouldDiscardLateCreationWhenItsLogicalPlaybackClosesDuringStartup(
      String closing, boolean beforeRegistration) {
    Consumer<CreateStreamSessionCommand> close =
        command -> {
          switch (closing) {
            case "stream" -> service.closeOwningStream(command.streamSessionId());
            case "media" -> service.closeMediaFiles(Set.of(command.mediaFileId()));
            case "shutdown" -> service.closeAll();
            default -> throw new AssertionError(closing);
          }
        };
    if (beforeRegistration) {
      streams.beforeNextCreation = close;
    } else {
      streams.duringNextCreation = close;
    }

    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start))
        .isInstanceOf(PlaybackSessionClosedException.class);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should preserve the terminal outcome when Original fails before creation returns")
  void shouldPreserveTheTerminalOutcomeWhenOriginalFailsBeforeCreationReturns() {
    streams.duringNextCreation =
        command ->
            service.onVideoCopyOutputMismatch(
                new VideoCopyOutputMismatchEvent(
                    command.streamSessionId(), UUID.randomUUID(), "default"));
    var command =
        startCommand()
            .options(
                StreamingOptions.builder()
                    .quality(VideoQuality.ORIGINAL)
                    .supportedCodecs(List.of("h264"))
                    .build())
            .build();
    var result = accepted(service.start(command));
    assertThat(result.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            failed ->
                assertThat(failed.reason()).isEqualTo(new PlaybackSessionFailure.OutputMismatch()));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should keep the current replacement when cleanup names its retired child")
  void shouldKeepTheCurrentReplacementWhenCleanupNamesItsRetiredChild() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    var pending = playback.reportFormatFailure(report(initial, command).build()).orElseThrow();
    playback.closeOwningStream(initial.currentFormatAttempt().streamSessionId());
    assertThat(playback.access(request(initial, command))).contains(pending);
    playback.close(request(initial, command));
    drain(work);
    assertThat(playback.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should settle a terminal failure when replacement scheduling is rejected")
  void shouldSettleATerminalFailureWhenReplacementSchedulingIsRejected() {
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(
                _ -> {
                  throw new RejectedExecutionException();
                })
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    var result = playback.reportFormatFailure(report(initial, command).build()).orElseThrow();
    assertThat(result.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            failed ->
                assertThat(failed.reason()).isEqualTo(new PlaybackSessionFailure.StartupFailed()));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should discard a dispatched child when initial creation throws")
  void shouldDiscardADispatchedChildWhenInitialCreationThrows() {
    streams.duringNextCreation =
        _ -> {
          throw new IllegalStateException("fixture failure");
        };
    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start)).isInstanceOf(IllegalStateException.class);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should settle a terminal failure when replacement creation throws after dispatch")
  void shouldSettleATerminalFailureWhenReplacementCreationThrowsAfterDispatch() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    streams.duringNextCreation =
        _ -> {
          throw new IllegalStateException("fixture failure");
        };
    var result = service.reportFormatFailure(report(initial, command).build()).orElseThrow();
    assertThat(result.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            failed ->
                assertThat(failed.reason()).isEqualTo(new PlaybackSessionFailure.StartupFailed()));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should expire a terminal playback when it has no child stream")
  void shouldExpireATerminalPlaybackWhenItHasNoChildStream() {
    var command =
        startCommand()
            .options(
                StreamingOptions.builder()
                    .quality(VideoQuality.ORIGINAL)
                    .supportedCodecs(List.of("h264"))
                    .build())
            .build();
    var initial = accepted(service.start(command));
    service.reportFormatFailure(report(initial, command).build());
    clock.advance(Duration.ofHours(25));
    service.reapExpired(clock.instant());
    assertThat(service.access(request(initial, command))).isEmpty();
  }

  @Test
  @DisplayName("Should reject new playback when shutdown has closed registration")
  void shouldRejectNewPlaybackWhenShutdownHasClosedRegistration() {
    service.closeAll();
    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start))
        .isInstanceOf(PlaybackSessionClosedException.class);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should retain initial capabilities when caller lists change before replacement")
  void shouldRetainInitialCapabilitiesWhenCallerListsChangeBeforeReplacement() {
    var video = new ArrayList<>(List.of("h264", "hevc"));
    var audio = new ArrayList<>(List.of("aac"));
    var command =
        startCommand()
            .options(
                StreamingOptions.builder()
                    .quality(VideoQuality.AUTO)
                    .supportedCodecs(video)
                    .supportedAudioCodecs(audio)
                    .build())
            .build();
    var initial = accepted(service.start(command));
    video.clear();
    audio.clear();

    var recovered = service.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThat(recovered.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getOptions().supportedCodecs())
                  .containsExactly("h264", "hevc");
              assertThat(ready.session().getOptions().supportedAudioCodecs())
                  .containsExactly("aac");
            });
  }

  @Test
  @DisplayName("Should close owned streams when queued recovery cannot check authority")
  void shouldCloseOwnedStreamsWhenQueuedRecoveryCannotCheckAuthority() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    playback.reportFormatFailure(report(initial, command).build());
    authority.failWith(new IllegalStateException("Authority unavailable"));

    drain(work);

    assertThat(streams.getAllSessions()).isEmpty();
    assertThat(playback.access(request(initial, command))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should preserve the creation rejection when initial playback has no eligible format")
  void shouldPreserveTheCreationRejectionWhenInitialPlaybackHasNoEligibleFormat() {
    streams.rejection = new CreateStreamSessionRejection.NoEligibleVideoFormat();
    var command = startCommand().build();
    assertThat(service.start(command)).isEqualTo(Outcome.rejected(streams.rejection));
    assertThat(streams.getAllSessions()).isEmpty();
    service.closeAll();
  }

  @Test
  @DisplayName("Should retain the typed rejection when replacement has no eligible format")
  void shouldRetainTheTypedRejectionWhenReplacementHasNoEligibleFormat() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    streams.rejection = new CreateStreamSessionRejection.NoEligibleVideoFormat();
    var failed = service.reportFormatFailure(report(initial, command).build()).orElseThrow();
    assertThat(failed.formatAttemptCount()).isEqualTo(2);
    assertThat(failed.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            attempt ->
                assertThat(attempt.reason())
                    .isEqualTo(new PlaybackSessionFailure.CreationRejected(streams.rejection)));
    assertThat(service.reportFormatFailure(report(failed, command).build())).contains(failed);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should discard a late replacement when its playback closes during creation")
  void shouldDiscardALateReplacementWhenItsPlaybackClosesDuringCreation(
      boolean beforeRegistration) {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    Consumer<CreateStreamSessionCommand> close = _ -> service.close(request(initial, command));
    if (beforeRegistration) {
      streams.beforeNextCreation = close;
    }

    if (!beforeRegistration) {
      streams.duringNextCreation = close;
    }

    assertThat(service.reportFormatFailure(report(initial, command).build())).isEmpty();
    assertThat(service.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should expire a pending reservation when no replacement has started")
  void shouldExpireAPendingReservationWhenNoReplacementHasStarted() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    playback.reportFormatFailure(report(initial, command).build());
    clock.advance(Duration.ofHours(25));
    playback.reapExpired(clock.instant());
    drain(work);
    assertThat(playback.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should retain active playback when its child was accessed after logical status")
  void shouldRetainActivePlaybackWhenItsChildWasAccessedAfterLogicalStatus() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    var child = streams.getAllSessions().iterator().next();
    clock.advance(Duration.ofHours(23));
    child.setLastAccessedAt(clock.instant());
    clock.advance(Duration.ofHours(2));
    service.reapExpired(clock.instant());
    assertThat(service.access(request(initial, command))).contains(initial);
    clock.advance(Duration.ofHours(25));
    service.reapExpired(clock.instant());
    assertThat(service.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should remove all ownership when one child cleanup fails during shutdown")
  void shouldRemoveAllOwnershipWhenOneChildCleanupFailsDuringShutdown() {
    var command = startCommand().build();
    var first = accepted(service.start(command));
    var second = accepted(service.start(command));
    streams.failDestroy = true;
    assertThatThrownBy(service::closeAll)
        .isInstanceOf(IllegalStateException.class)
        .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
    assertThat(service.access(request(first, command))).isEmpty();
    assertThat(service.access(request(second, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
    service.closeAll();
  }

  @Test
  @DisplayName("Should preserve startup failure when cleanup also fails")
  void shouldPreserveStartupFailureWhenCleanupAlsoFails() {
    var failure = new IllegalArgumentException("Startup failed");
    streams.duringNextCreation =
        _ -> {
          throw failure;
        };
    streams.failDestroy = true;
    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start))
        .isSameAs(failure)
        .satisfies(result -> assertThat(result.getSuppressed()).hasSize(1));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should settle a replacement when retirement cleanup fails")
  void shouldSettleAReplacementWhenRetirementCleanupFails() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    streams.failDestroy = true;
    var failed = service.reportFormatFailure(report(initial, command).build()).orElseThrow();
    assertThat(failed.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            attempt ->
                assertThat(attempt.reason()).isEqualTo(new PlaybackSessionFailure.StartupFailed()));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should ignore stale reports when logical playback is already closed")
  void shouldIgnoreStaleReportsWhenLogicalPlaybackIsAlreadyClosed() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    service.close(request(initial, command));
    service.close(request(initial, command));
    service.closeOwningStream(initial.currentFormatAttempt().streamSessionId());
    service.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(
            initial.currentFormatAttempt().streamSessionId(), UUID.randomUUID(), "default"));
    assertThat(service.reportFormatFailure(report(initial, command).build())).isEmpty();
    assertThat(service.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should recover with defaults when the client omits quality and codec lists")
  void shouldRecoverWithDefaultsWhenTheClientOmitsQualityAndCodecLists() {
    var command = startCommand().options(StreamingOptions.builder().build()).build();
    var initial = accepted(service.start(command));
    assertThat(
            service
                .reportFormatFailure(report(initial, command).build())
                .orElseThrow()
                .formatAttemptCount())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("Should require a selected profile when starting or accessing logical playback")
  void shouldRequireASelectedProfileWhenStartingOrAccessingLogicalPlayback() {
    var account = accountScopedBuilder().build();
    var withoutProfile = startCommand().identity(account).build();
    assertThatThrownBy(() -> service.start(withoutProfile))
        .isInstanceOf(AuthenticationRequiredException.class);
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    var accountRequest =
        PlaybackSessionRequest.builder()
            .playbackSessionId(initial.playbackSessionId())
            .identity(account)
            .build();
    assertThatThrownBy(() -> service.access(accountRequest))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThat(service.access(request(initial, command))).contains(initial);
  }

  @Test
  @DisplayName("Should reject an absent creation command before registering logical playback")
  void shouldRejectAnAbsentCreationCommandBeforeRegisteringLogicalPlayback() {
    assertThatThrownBy(() -> service.start(null)).isInstanceOf(NullPointerException.class);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should close logical playback when a worker mismatch cannot pass live authority")
  void shouldCloseLogicalPlaybackWhenAWorkerMismatchCannotPassLiveAuthority(boolean throwsFailure) {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    if (throwsFailure) {
      authority.failWith(new IllegalStateException("Authority unavailable"));
    }

    if (!throwsFailure) {
      authority.deny();
    }

    service.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(
            initial.currentFormatAttempt().streamSessionId(), UUID.randomUUID(), "default"));
    assertThat(service.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should discard a failure claim when playback closes during its authority check")
  void shouldDiscardAFailureClaimWhenPlaybackClosesDuringItsAuthorityCheck() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    authority.onNextCheck(
        () -> service.closeOwningStream(initial.currentFormatAttempt().streamSessionId()));
    assertThat(service.reportFormatFailure(report(initial, command).build())).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should discard reserved creation when playback closes while its old child retires")
  void shouldDiscardReservedCreationWhenPlaybackClosesWhileItsOldChildRetires() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    streams.afterNextDestroy = () -> service.close(request(initial, command));
    assertThat(service.reportFormatFailure(report(initial, command).build())).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should preserve the original failure when creation and cleanup throw the same object")
  void shouldPreserveTheOriginalFailureWhenCreationAndCleanupThrowTheSameObject() {
    var failure = new IllegalStateException("Shared failure");
    streams.duringNextCreation =
        _ -> {
          throw failure;
        };
    streams.destroyFailure = failure;
    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start))
        .isSameAs(failure)
        .satisfies(result -> assertThat(result.getSuppressed()).isEmpty());
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should return the new pending reservation when initial copy fails before creation returns")
  void shouldReturnTheNewPendingReservationWhenInitialCopyFailsBeforeCreationReturns() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    streams.duringNextCreation =
        child ->
            playback.onVideoCopyOutputMismatch(
                new VideoCopyOutputMismatchEvent(
                    child.streamSessionId(), UUID.randomUUID(), "default"));
    var command = startCommand().build();
    var pending = accepted(playback.start(command));
    assertThat(pending.formatAttemptCount()).isEqualTo(2);
    assertThat(pending.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Pending.class);
    assertThat(streams.getAllSessions()).isEmpty();
    drain(work);
    assertThat(playback.access(request(pending, command)).orElseThrow().currentFormatAttempt())
        .isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(streams.getActiveSessionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should abandon the logical owner when its unpublished initial child has been replaced")
  void shouldAbandonTheLogicalOwnerWhenItsUnpublishedInitialChildHasBeenReplaced() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    playback.reportFormatFailure(report(initial, command).build());
    authority.deny();

    playback.abandonStart(initial.playbackSessionId());

    assertThat(playback.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
    drain(work);
    playback.abandonStart(initial.playbackSessionId());
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should finish asynchronous replacement and cleanup when its owned executor closes")
  void shouldFinishAsynchronousReplacementAndCleanupWhenItsOwnedExecutorCloses() {
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    try (playback) {
      playback.reportFormatFailure(report(initial, command).build());
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                var current = playback.access(request(initial, command)).orElseThrow();
                assertThat(current.formatAttemptCount()).isEqualTo(2);
                assertThat(current.currentFormatAttempt())
                    .isInstanceOf(PlaybackFormatAttempt.Ready.class);
                assertThat(streams.getActiveSessionCount()).isEqualTo(1);
              });
    }

    assertThat(playback.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  private static void drain(ConcurrentLinkedQueue<Runnable> work) {
    for (var next = work.poll(); next != null; next = work.poll()) {
      next.run();
    }
  }

  private PlaybackSessionRequest request(
      PlaybackSessionSnapshot snapshot, StartPlaybackSessionCommand command) {
    return PlaybackSessionRequest.builder()
        .playbackSessionId(snapshot.playbackSessionId())
        .identity(command.identity())
        .build();
  }

  private ReportPlaybackFormatFailureCommand.ReportPlaybackFormatFailureCommandBuilder report(
      PlaybackSessionSnapshot snapshot, StartPlaybackSessionCommand command) {
    return ReportPlaybackFormatFailureCommand.builder()
        .playbackSessionId(snapshot.playbackSessionId())
        .streamSessionId(snapshot.currentFormatAttempt().streamSessionId())
        .identity(command.identity())
        .reason(PlaybackFormatFailure.DECODE_ERROR);
  }

  private StartPlaybackSessionCommand.StartPlaybackSessionCommandBuilder startCommand() {
    return StartPlaybackSessionCommand.builder()
        .mediaFileId(UUID.randomUUID())
        .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
        .options(
            StreamingOptions.builder()
                .quality(VideoQuality.AUTO)
                .supportedCodecs(List.of("h264", "hevc"))
                .build());
  }

  private static PlaybackSessionSnapshot accepted(
      Outcome<PlaybackSessionSnapshot, CreateStreamSessionRejection> outcome) {
    return outcome.fold(
        result -> result,
        rejections -> {
          throw new AssertionError(rejections);
        });
  }

  private static class ChildStreams implements StreamingService {
    private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();
    private Consumer<CreateStreamSessionCommand> duringNextCreation;
    private Consumer<CreateStreamSessionCommand> beforeNextCreation;
    private CreateStreamSessionRejection rejection;
    private boolean failDestroy;
    private Runnable afterNextDestroy;
    private RuntimeException destroyFailure;

    @Override
    public Outcome<StreamSession, CreateStreamSessionRejection> createSession(
        CreateStreamSessionCommand command) {
      if (beforeNextCreation != null) {
        var callback = beforeNextCreation;
        beforeNextCreation = null;
        callback.accept(command);
      }

      if (rejection != null) {
        return Outcome.rejected(rejection);
      }

      var session =
          defaultSessionBuilder()
              .sessionId(command.streamSessionId())
              .mediaFileId(command.mediaFileId())
              .authority(command.identity().playbackAuthority())
              .options(command.options())
              .transcodeDecision(
                  command.videoFormatConstraint() == VideoFormatConstraint.ENCODE_ONLY
                      ? fullTranscodeDecision("h264")
                      : remuxDecision())
              .build();
      sessions.put(session.getSessionId(), session);
      if (duringNextCreation != null) {
        var callback = duringNextCreation;
        duringNextCreation = null;
        callback.accept(command);
      }

      return Outcome.accepted(session);
    }

    @Override
    public Optional<StreamSession> accessSession(PlaybackRequest request) {
      return Optional.ofNullable(sessions.get(request.streamSessionId()));
    }

    @Override
    public void destroySession(UUID id) {
      sessions.remove(id);
      if (afterNextDestroy != null) {
        var callback = afterNextDestroy;
        afterNextDestroy = null;
        callback.run();
      }

      if (destroyFailure != null) {
        throw destroyFailure;
      }

      if (failDestroy) {
        throw new IllegalStateException("Cleanup failed");
      }
    }

    @Override
    public void destroySession(UUID id, UUID profileId) {
      destroySession(id);
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return List.copyOf(sessions.values());
    }

    @Override
    public int getActiveSessionCount() {
      return sessions.size();
    }
  }
}
