package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.MediaFileStreamInfo;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbeAttemptFailure;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.events.library.MediaFileProbeTaskRequested;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeAttemptEnds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;

@Tag("UnitTest")
@DisplayName("Playback probe service")
class PlaybackProbeServiceTest {

  private static final Duration WAIT_LIMIT = Duration.ofSeconds(20);
  private static final ProbeInputs REQUESTED_INPUTS =
      new ProbeInputs(new SourceFileSnapshot(1000, Instant.EPOCH), ProbeVersion.CURRENT);

  private final UUID mediaFileId = UUID.randomUUID();
  private final MutableClock clock = new MutableClock();
  private final FakeMediaFileContainerInfoRepository outcomes =
      new FakeMediaFileContainerInfoRepository();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ProbeAttemptEnds attemptEnds = new ProbeAttemptEnds(meterRegistry);
  private final CapturingEventPublisher events = new CapturingEventPublisher();
  private final CompletableFuture<Void> probeRequested = new CompletableFuture<>();
  private final ExecutorService playbackThreads = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void stopPlaybackThreads() {
    playbackThreads.shutdownNow();
  }

  @Nested
  @DisplayName("File with a stored probe outcome")
  class StoredOutcome {

    @ParameterizedTest
    @EnumSource(ProbeError.class)
    @DisplayName("Should retain the terminal failure when playback has a failed probe outcome")
    void shouldRetainTheTerminalFailureWhenPlaybackHasAFailedProbeOutcome(ProbeError reason) {
      outcomes.store(mediaError(reason));

      assertThat(service().read(mediaFileId))
          .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.ProbeFailed(reason)));
      assertThat(events.getEventsOfType(MediaFileProbeTaskRequested.class)).isEmpty();
    }

    @Test
    @DisplayName("Should supply persisted properties when playback has a successful probe outcome")
    void shouldSupplyPersistedPropertiesWhenPlaybackHasASuccessfulProbeOutcome() {
      outcomes.store(success());

      var probe = probeOf(service().read(mediaFileId));

      assertThat(probe.duration()).isEqualTo(Duration.ofSeconds(73));
      assertThat(probe.bitrate()).isEqualTo(4_000_000);
      assertThat(probe.videoCodec()).isEqualTo("h264");
      assertThat(probe.width()).isEqualTo(1920);
      assertThat(probe.height()).isEqualTo(1080);
      assertThat(events.getEventsOfType(MediaFileProbeTaskRequested.class)).isEmpty();
    }

    @Test
    @DisplayName("Should keep playing a file's earlier outcome until its new outcome is stored")
    void shouldKeepPlayingAFilesEarlierOutcomeUntilItsNewOutcomeIsStored() {
      var snapshot = REQUESTED_INPUTS.snapshot();
      var earlierVersion = 1;
      outcomes.store(
          MediaFileContainerInfo.builder()
              .mediaFileId(mediaFileId)
              .snapshot(snapshot)
              .probeVersion(earlierVersion)
              .container(ProbeContainer.builder().build())
              .streams(List.of(videoStream("h264")))
              .build());
      outcomes.trySaveProbeRequest(mediaFileId, REQUESTED_INPUTS);
      var service = service();

      var beforeReprobe = probeOf(service.read(mediaFileId)).videoCodec();
      outcomes.publish(
          ProbePublication.builder()
              .mediaFileId(mediaFileId)
              .snapshot(snapshot)
              .probeVersion(ProbeVersion.CURRENT)
              .outcome(
                  new ProbeOutcome.Success(
                      ProbeContainer.builder().build(),
                      List.of(
                          StreamInfo.builder()
                              .codecType("video")
                              .codec(Optional.of("hevc"))
                              .build()),
                      new KeyframeKnowledge.PacketScanRequired(
                          KeyframePacketScanReason.NO_CONTAINER_INDEX)))
              .build());
      var afterReprobe = probeOf(service.read(mediaFileId)).videoCodec();

      assertThat(earlierVersion).isLessThan(ProbeVersion.CURRENT);
      assertThat(beforeReprobe).isEqualTo("h264");
      assertThat(afterReprobe).isEqualTo("hevc");
    }
  }

  @Nested
  @DisplayName("File without a probe outcome")
  class MissingOutcome {

    @Test
    @DisplayName(
        "Should start playback with the probe result when the probe finishes during the wait")
    void shouldStartPlaybackWithTheProbeResultWhenTheProbeFinishesDuringTheWait() throws Exception {
      var playback = readInBackground(service());
      probeRequested.get(10, TimeUnit.SECONDS);

      outcomes.store(success());
      attemptEnds.signalEnd(mediaFileId);

      assertThat(probeOf(playback.get(10, TimeUnit.SECONDS)).videoCodec()).isEqualTo("h264");
      assertThat(events.getEventsOfType(MediaFileProbeTaskRequested.class))
          .containsExactly(new MediaFileProbeTaskRequested(mediaFileId));
      assertThat(probeWaits()).isZero();
    }

    @Test
    @DisplayName("Should return the terminal probe error when the probe stores it during the wait")
    void shouldReturnTheTerminalProbeErrorWhenTheProbeStoresItDuringTheWait() throws Exception {
      var playback = readInBackground(service());
      probeRequested.get(10, TimeUnit.SECONDS);

      outcomes.store(mediaError(ProbeError.NO_VIDEO_STREAM));
      attemptEnds.signalEnd(mediaFileId);

      assertThat(playback.get(10, TimeUnit.SECONDS))
          .isEqualTo(
              Outcome.rejected(
                  new CreateStreamSessionRejection.ProbeFailed(ProbeError.NO_VIDEO_STREAM)));
    }

    @Test
    @DisplayName("Should refuse as not ready when the wait reaches the limit")
    void shouldRefuseAsNotReadyWhenTheWaitReachesTheLimit() throws Exception {
      var playback = readInBackground(service());
      probeRequested.get(10, TimeUnit.SECONDS);

      clock.advance(WAIT_LIMIT);
      attemptEnds.signalEnd(mediaFileId);

      assertThat(playback.get(10, TimeUnit.SECONDS)).isEqualTo(notReady());
      assertThat(probeState().requested()).contains(REQUESTED_INPUTS);
      assertThat(probeWaits()).isZero();
    }

    @Test
    @DisplayName("Should refuse as not ready when no attempt ends before the limit")
    void shouldRefuseAsNotReadyWhenNoAttemptEndsBeforeTheLimit() throws Exception {
      var service =
          serviceBuilder()
              .clock(Clock.systemUTC())
              .properties(
                  StreamingProperties.builder().probeWaitLimit(Duration.ofMillis(1)).build())
              .build();

      assertThat(readInBackground(service).get(10, TimeUnit.SECONDS)).isEqualTo(notReady());
      assertThat(probeWaits()).isZero();
    }

    @Test
    @DisplayName(
        "Should request the probe and refuse at once when no compatible worker is connected")
    void shouldRequestTheProbeAndRefuseAtOnceWhenNoCompatibleWorkerIsConnected() throws Exception {
      var askedVersions = new CopyOnWriteArrayList<Integer>();
      var service =
          serviceBuilder()
              .probeWorkers(
                  version -> {
                    askedVersions.add(version);
                    return false;
                  })
              .build();

      assertThat(readInBackground(service).get(10, TimeUnit.SECONDS)).isEqualTo(notReady());
      assertThat(askedVersions).containsExactly(ProbeVersion.CURRENT);
      assertThat(probeState().requested()).contains(REQUESTED_INPUTS);
    }

    @Test
    @DisplayName("Should refuse as not ready when the probe attempt fails with a retryable reason")
    void shouldRefuseAsNotReadyWhenTheProbeAttemptFailsWithARetryableReason() throws Exception {
      var playback = readInBackground(service());
      probeRequested.get(10, TimeUnit.SECONDS);

      saveFailure(ItemFailureReason.SOURCE_INACCESSIBLE);
      attemptEnds.signalEnd(mediaFileId);

      assertThat(playback.get(10, TimeUnit.SECONDS)).isEqualTo(notReady());
    }

    @Test
    @DisplayName(
        "Should keep waiting when the saved failure came from an attempt before the request")
    void shouldKeepWaitingWhenTheSavedFailureCameFromAnAttemptBeforeTheRequest() throws Exception {
      outcomes.trySaveProbeRequest(mediaFileId, REQUESTED_INPUTS);
      saveFailure(ItemFailureReason.TEMPORARY);
      clock.advance(Duration.ofSeconds(1));
      var playback = readInBackground(service());
      probeRequested.get(10, TimeUnit.SECONDS);

      outcomes.store(success());
      attemptEnds.signalEnd(mediaFileId);

      assertThat(probeOf(playback.get(10, TimeUnit.SECONDS)).videoCodec()).isEqualTo("h264");
    }

    @Test
    @DisplayName("Should refuse as not ready when the source is removed during the wait")
    void shouldRefuseAsNotReadyWhenTheSourceIsRemovedDuringTheWait() throws Exception {
      var playback = readInBackground(service());
      probeRequested.get(10, TimeUnit.SECONDS);

      outcomes.withdrawProbeRequest(mediaFileId);
      attemptEnds.signalEnd(mediaFileId);

      assertThat(playback.get(10, TimeUnit.SECONDS)).isEqualTo(notReady());
    }

    @Test
    @DisplayName("Should refuse as not ready when the media file is deleted during the wait")
    void shouldRefuseAsNotReadyWhenTheMediaFileIsDeletedDuringTheWait() throws Exception {
      var playback = readInBackground(service());
      probeRequested.get(10, TimeUnit.SECONDS);

      outcomes.deleteMediaFile(mediaFileId);
      attemptEnds.signalEnd(mediaFileId);

      assertThat(playback.get(10, TimeUnit.SECONDS)).isEqualTo(notReady());
    }

    @Test
    @DisplayName(
        "Should refuse as not ready and keep the interrupt when the waiting thread is interrupted")
    void shouldRefuseAsNotReadyAndKeepTheInterruptWhenTheWaitingThreadIsInterrupted()
        throws Exception {
      var service = service();

      var interruptedPlayback =
          CompletableFuture.supplyAsync(
              () -> {
                Thread.currentThread().interrupt();
                var outcome = service.read(mediaFileId);
                return new InterruptedRead(outcome, Thread.interrupted());
              },
              playbackThreads);

      assertThat(interruptedPlayback.get(10, TimeUnit.SECONDS))
          .isEqualTo(new InterruptedRead(notReady(), true));
      assertThat(probeWaits()).isZero();
    }

    @Test
    @DisplayName("Should leave no wait behind when the probe that two viewers wait for finishes")
    void shouldLeaveNoWaitBehindWhenTheProbeThatTwoViewersWaitForFinishes() throws Exception {
      var secondViewerRequested = new CompletableFuture<Void>();
      var service =
          serviceBuilder()
              .probeWorkers(
                  _ -> {
                    if (probeRequested.isDone()) {
                      secondViewerRequested.complete(null);
                    }

                    probeRequested.complete(null);
                    return true;
                  })
              .build();
      var firstViewer = readInBackground(service);
      probeRequested.get(10, TimeUnit.SECONDS);
      var secondViewer = readInBackground(service);
      secondViewerRequested.get(10, TimeUnit.SECONDS);

      outcomes.store(success());
      attemptEnds.signalEnd(mediaFileId);

      assertThat(probeOf(firstViewer.get(10, TimeUnit.SECONDS)).videoCodec()).isEqualTo("h264");
      assertThat(probeOf(secondViewer.get(10, TimeUnit.SECONDS)).videoCodec()).isEqualTo("h264");
      assertThat(probeWaits()).isZero();
    }

    @Test
    @DisplayName("Should propagate the scheduling failure when a probe request cannot be enqueued")
    void shouldPropagateTheSchedulingFailureWhenAProbeRequestCannotBeEnqueued() {
      var schedulingFailure = new IllegalStateException("Probe task insert failed");
      var service =
          serviceBuilder()
              .eventPublisher(
                  _ -> {
                    throw schedulingFailure;
                  })
              .build();

      assertThatThrownBy(() -> service.read(mediaFileId)).isSameAs(schedulingFailure);
    }
  }

  private record InterruptedRead(
      Outcome<MediaProbe, CreateStreamSessionRejection> outcome, boolean interrupted) {}

  private PlaybackProbeService service() {
    return serviceBuilder().build();
  }

  private PlaybackProbeService.PlaybackProbeServiceBuilder serviceBuilder() {
    return PlaybackProbeService.builder()
        .reader(new PersistedProbeReader(outcomes))
        .outcomes(outcomes)
        .eventPublisher(libraryListener())
        .probeWorkers(_ -> true)
        .attemptEnds(attemptEnds)
        .clock(clock)
        .properties(StreamingProperties.builder().probeWaitLimit(WAIT_LIMIT).build());
  }

  // Saves the requested inputs as the library's listener does before the event returns.
  private ApplicationEventPublisher libraryListener() {
    return event -> {
      events.publishEvent(event);
      if (event instanceof MediaFileProbeTaskRequested(var requestedFileId)) {
        outcomes.trySaveProbeRequest(requestedFileId, REQUESTED_INPUTS);
        probeRequested.complete(null);
      }
    };
  }

  private CompletableFuture<Outcome<MediaProbe, CreateStreamSessionRejection>> readInBackground(
      PlaybackProbeService service) {
    return CompletableFuture.supplyAsync(() -> service.read(mediaFileId), playbackThreads);
  }

  private void saveFailure(ItemFailureReason reason) {
    assertThat(
            outcomes.trySaveProbeFailure(
                mediaFileId,
                REQUESTED_INPUTS,
                ProbeAttemptFailure.builder()
                    .reason(reason)
                    .detail("The worker could not read the source")
                    .failedAt(clock.instant())
                    .build()))
        .isTrue();
  }

  private double probeWaits() {
    return meterRegistry.get("streamarr.playback.probe_waits").gauge().value();
  }

  private ProbeState probeState() {
    return outcomes.findProbeStates(List.of(mediaFileId)).getFirst();
  }

  private MediaFileContainerInfo success() {
    return MediaFileContainerInfo.builder()
        .mediaFileId(mediaFileId)
        .snapshot(REQUESTED_INPUTS.snapshot())
        .probeVersion(ProbeVersion.CURRENT)
        .container(
            ProbeContainer.builder()
                .duration(Optional.of(Duration.ofSeconds(73)))
                .bitrate(OptionalLong.of(4_000_000))
                .build())
        .streams(
            List.of(
                MediaFileStreamInfo.builder().stream(
                        mediaFileId,
                        StreamInfo.builder()
                            .index(0)
                            .codecType("video")
                            .codec(Optional.of("h264"))
                            .width(OptionalInt.of(1920))
                            .height(OptionalInt.of(1080))
                            .build())
                    .build()))
        .build();
  }

  private MediaFileContainerInfo mediaError(ProbeError reason) {
    return MediaFileContainerInfo.builder()
        .mediaFileId(mediaFileId)
        .snapshot(REQUESTED_INPUTS.snapshot())
        .probeVersion(ProbeVersion.CURRENT)
        .probeError(reason)
        .build();
  }

  private MediaFileStreamInfo videoStream(String codec) {
    return MediaFileStreamInfo.builder().stream(
            mediaFileId,
            StreamInfo.builder().index(0).codecType("video").codec(Optional.of(codec)).build())
        .build();
  }

  private static Outcome<MediaProbe, CreateStreamSessionRejection> notReady() {
    return Outcome.rejected(new CreateStreamSessionRejection.ProbeNotReady());
  }

  private static MediaProbe probeOf(Outcome<MediaProbe, CreateStreamSessionRejection> read) {
    return read.fold(
        probe -> probe,
        rejections -> {
          throw new AssertionError("Expected playback readiness: " + rejections);
        });
  }
}
