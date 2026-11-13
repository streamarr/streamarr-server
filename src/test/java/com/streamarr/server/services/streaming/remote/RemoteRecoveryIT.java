package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.streamCopyDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.google.common.primitives.Bytes;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.RecordedStream;
import com.streamarr.server.fixtures.StoredSegments;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Acceptance proof for ADR 0019's distributed recovery: a variant whose worker attempt fails is
 * re-dispatched, on the next request that needs it, to another live worker connection; only when
 * every eligible connection has been tried does the failure surface as a terminal outcome.
 */
@Tag("IntegrationTest")
@DisplayName("Remote Recovery Integration Tests")
class RemoteRecoveryIT {

  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  // The test's two-minute timeout stops a slow start before this bound runs out, as it did when
  // the workers started one after the other.
  private static final Duration WORKER_START_BOUND = Duration.ofMinutes(2);

  @TempDir Path tempDir;

  // The tests that need one worker share this server and worker, and each sets the worker's
  // scripted FFmpeg body before it starts a job. A test that connects workers at chosen moments
  // starts its own.
  @TempDir static Path sharedTempDir;

  private static Path sharedMediaRoot;
  private static LocalSegmentStore sharedSegmentStore;
  private static MeterRegistry sharedMeterRegistry;
  private static WorkerSessionServer sharedServer;
  private static WorkerContainerFixture sharedWorker;

  @BeforeAll
  static void startSharedServerAndWorker() throws Exception {
    sharedMediaRoot = Files.createDirectory(sharedTempDir.resolve("media"));
    sharedSegmentStore = new LocalSegmentStore(sharedTempDir.resolve("server-segments"));
    sharedMeterRegistry = new SimpleMeterRegistry();
    sharedServer = server(sharedSegmentStore, sharedMeterRegistry);
    sharedServer.start();
    sharedWorker = workerBuilder(sharedServer, sharedMediaRoot).build();
    sharedWorker.start();
  }

  @AfterAll
  static void stopSharedServerAndWorker() {
    if (sharedWorker != null) {
      sharedWorker.close();
    }

    if (sharedServer != null) {
      sharedServer.close();
    }
  }

  @Test
  @DisplayName(
      "Should re-dispatch a failed variant to another live worker connection when recovering a producer")
  void shouldRedispatchFailedVariantToAnotherLiveWorkerConnectionWhenRecoveringProducer()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore)) {
      server.start();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var rig =
          recoveryRig(
              RecoveryRigConfiguration.builder()
                  .initialAttempt(initialAttempt(streamSessionId, mediaFile, TranscodeMode.REMUX))
                  .segmentStore(segmentStore)
                  .heldRequests(server.heldSegmentRequests())
                  .executor(executor)
                  .build());

      try (var failingWorker = workerBuilder(server, mediaRoot).ffmpegScript("exit 73\n").build()) {
        failingWorker.start();
        var handle = executor.start(rig.configuration().initialAttempt());
        rig.session().setHandle(handle);
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> !server.isRunning(streamSessionId, StreamSession.defaultVariant()));
      }

      try (var healthyWorker =
          workerBuilder(server, mediaRoot)
              .ffmpegScript(WorkerContainerFixture.emitRecordedStream(RecordedStream.START_AT_ZERO))
              .build()) {
        healthyWorker.start();
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(
                () -> !server.eligibleWorkers(SOURCE_NAMESPACE_ID, streamCopyDecision()).isEmpty());

        var delivery =
            rig.coordinator()
                .deliver(streamSessionId, StreamSession.defaultVariant(), "segment0.m4s");

        var media =
            switch (delivery) {
              case SegmentDelivery.Ready _ ->
                  Fmp4Fixture.withInitializationSegment(
                      segmentStore, streamSessionId, StoredSegments.bytes(delivery));
              default -> throw new AssertionError(delivery);
            };
        var recoveredMedia = Files.write(tempDir.resolve("recovered.mp4"), media);
        assertThat(healthyWorker.decodedVideoFrameCount(recoveredMedia)).isPositive();
        assertThat(rig.session().getHandle().orElseThrow().status())
            .isEqualTo(TranscodeStatus.ACTIVE);
      }
    }
  }

  @Test
  @DisplayName("Should surface a terminal outcome when every worker attempt fails")
  void shouldSurfaceTerminalOutcomeWhenEveryWorkerAttemptFails() throws Exception {
    var streamSessionId = UUID.randomUUID();
    var mediaFile =
        Files.writeString(sharedMediaRoot.resolve(streamSessionId + ".mkv"), "test media");
    var executor = new RemoteTranscodeExecutor(sharedServer, SOURCE_NAMESPACE_ID, sharedMediaRoot);
    var rig =
        recoveryRig(
            RecoveryRigConfiguration.builder()
                .initialAttempt(initialAttempt(streamSessionId, mediaFile, TranscodeMode.REMUX))
                .segmentStore(sharedSegmentStore)
                .heldRequests(sharedServer.heldSegmentRequests())
                .executor(executor)
                .build());
    prepareSharedWorker("exit 73\n");

    var handle = executor.start(rig.configuration().initialAttempt());
    rig.session().setHandle(handle);
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> !sharedServer.isRunning(streamSessionId, StreamSession.defaultVariant()));

    var delivery =
        rig.coordinator().deliver(streamSessionId, StreamSession.defaultVariant(), "segment0.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(rig.session().getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "VIDEO_TRANSCODE"})
  @DisplayName(
      "Should publish a replacement attempt's media segments when its initialization segment matches the stored one")
  void shouldPublishReplacementAttemptsMediaSegmentsWhenItsInitializationSegmentMatchesStoredOne(
      TranscodeMode mode) throws Exception {
    var streamSessionId = UUID.randomUUID();
    var mediaFile =
        Files.write(
            sharedMediaRoot.resolve(streamSessionId + ".mp4"),
            RecordedStream.START_AT_ZERO.bytes());
    var initialAttempt = initialAttempt(streamSessionId, mediaFile, mode);
    var rig =
        recoveryRig(
            RecoveryRigConfiguration.builder()
                .initialAttempt(initialAttempt)
                .segmentStore(sharedSegmentStore)
                .heldRequests(sharedServer.heldSegmentRequests())
                .executor(
                    new RemoteTranscodeExecutor(sharedServer, SOURCE_NAMESPACE_ID, sharedMediaRoot))
                .build());
    var worker =
        prepareSharedWorker(
            killableThenReplacedScript(initialAttempt.attemptId(), RecordedStream.START_AT_ZERO));
    var mismatchesBefore = initializationSegmentMismatches(sharedMeterRegistry);
    startThenKillInitialAttempt(rig, List.of(worker));

    var delivery =
        rig.coordinator().deliver(streamSessionId, StreamSession.defaultVariant(), "segment1.m4s");

    var media =
        switch (delivery) {
          case SegmentDelivery.Ready _ ->
              Bytes.concat(
                  Fmp4Fixture.withInitializationSegment(
                      sharedSegmentStore, streamSessionId, "segment0.m4s"),
                  StoredSegments.bytes(delivery));
          default -> throw new AssertionError(delivery);
        };
    var recoveredMedia = Files.write(tempDir.resolve("recovered.mp4"), media);
    var producerMedia =
        Files.write(tempDir.resolve("producer.mp4"), RecordedStream.START_AT_ZERO.bytes());
    assertThat(worker.videoPacketHashes(recoveredMedia))
        .as("the initial and replacement attempts preserve every video packet in order")
        .isNotEmpty()
        .containsExactlyElementsOf(worker.videoPacketHashes(producerMedia));
    assertThat(worker.audioPacketHashes(recoveredMedia))
        .as("the initial and replacement attempts preserve every audio packet in order")
        .isNotEmpty()
        .containsExactlyElementsOf(worker.audioPacketHashes(producerMedia));
    var replacementAttemptId = rig.session().getHandle().orElseThrow().attemptId();
    assertThat(replacementAttemptId)
        .as("recovery replaced the initial attempt")
        .isNotEqualTo(initialAttempt.attemptId());
    assertThat(worker.commandFor(replacementAttemptId))
        .as("the worker ran the replacement attempt")
        .isPresent();
    assertThat(initializationSegmentMismatches(sharedMeterRegistry))
        .as("no initialization segment was refused while this test ran")
        .isEqualTo(mismatchesBefore);
  }

  @Test
  @DisplayName(
      "Should keep the stored segments and try every worker when each replacement attempt's initialization segment differs")
  void
      shouldKeepStoredSegmentsAndTryEveryWorkerWhenEachReplacementAttemptsInitializationSegmentDiffers()
          throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var meterRegistry = new SimpleMeterRegistry();
    var streamSessionId = UUID.randomUUID();
    var initialAttempt = initialAttempt(streamSessionId, mediaFile, TranscodeMode.REMUX);
    var script =
        killableThenReplacedScript(
            initialAttempt.attemptId(), RecordedStream.REPLACEMENT_WITH_DIFFERING_INITIALIZATION);

    try (var server = server(segmentStore, meterRegistry);
        var firstWorker = workerBuilder(server, mediaRoot).ffmpegScript(script).build();
        var secondWorker = workerBuilder(server, mediaRoot).ffmpegScript(script).build()) {
      server.start();
      startTogether(firstWorker, secondWorker);
      var rig =
          recoveryRig(
              RecoveryRigConfiguration.builder()
                  .initialAttempt(initialAttempt)
                  .segmentStore(segmentStore)
                  .heldRequests(server.heldSegmentRequests())
                  .executor(new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot))
                  .build());
      startThenKillInitialAttempt(rig, List.of(firstWorker, secondWorker));
      var storedMedia =
          Fmp4Fixture.withInitializationSegment(segmentStore, streamSessionId, "segment0.m4s");

      var delivery =
          rig.coordinator()
              .deliver(streamSessionId, StreamSession.defaultVariant(), "segment1.m4s");

      assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
      assertThat(initializationSegmentMismatches(meterRegistry))
          .as("each eligible worker ran one replacement attempt and was refused")
          .isEqualTo(2);
      assertThat(
              Fmp4Fixture.withInitializationSegment(segmentStore, streamSessionId, "segment0.m4s"))
          .as("the initial attempt's initialization segment and segment 0 stay stored")
          .isEqualTo(storedMedia);
      assertThat(segmentStore.segmentExists(streamSessionId, "segment1.m4s")).isFalse();
      assertThat(rig.session().getHandle().orElseThrow().status())
          .isEqualTo(TranscodeStatus.FAILED);
    }
  }

  // The initial job attempt writes all but the last byte of the recording, so its output never
  // completes segment 1 however late the test kills it: it delivers segment 0, then stays alive
  // until the kill ends its output inside a box. Every other job attempt is a replacement attempt,
  // which writes the given recording and exits cleanly; the worker discards the recording's segment
  // 0 as preroll. The script knows an attempt by the id the worker names it with: an encoded
  // replacement attempt for segment 1 runs the initial attempt's command (RecordedStream), and
  // recovery may send a replacement attempt to a worker that has not run the initial attempt.
  private static String killableThenReplacedScript(
      UUID initialAttemptId, RecordedStream replacementRecording) {
    return """
        if [[ $STREAMARR_JOB_ATTEMPT_ID == %s ]]; then
          head -c -1 %s
          exec sleep 300
        fi
        %s
        """
        .formatted(
            initialAttemptId,
            RecordedStream.START_AT_ZERO.containerPath(),
            WorkerContainerFixture.emitRecordedStream(replacementRecording));
  }

  // An encode of an earlier test can still hold the shared worker's one slot, which this test's
  // encode needs.
  private static WorkerContainerFixture prepareSharedWorker(String ffmpegScript) {
    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(() -> sharedServer.availableSlots(SOURCE_NAMESPACE_ID) == 1);
    sharedWorker.replaceFfmpegScript(ffmpegScript);
    return sharedWorker;
  }

  private static void startTogether(WorkerContainerFixture first, WorkerContainerFixture second)
      throws Exception {
    try (var firstStart = BoundedTask.start(first::start);
        var secondStart = BoundedTask.start(second::start)) {
      firstStart.await(WORKER_START_BOUND);
      secondStart.await(WORKER_START_BOUND);
    }
  }

  private void startThenKillInitialAttempt(RecoveryRig rig, List<WorkerContainerFixture> workers)
      throws Exception {
    var configuration = rig.configuration();
    var streamSessionId = configuration.initialAttempt().sessionId();
    var handle = configuration.executor().start(configuration.initialAttempt());
    rig.session().setHandle(handle);
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(() -> configuration.segmentStore().segmentExists(streamSessionId, "segment0.m4s"));
    for (var worker : workers) {
      if (worker.commandFor(handle.attemptId()).isPresent()) {
        worker.killProducer(handle.attemptId());
      }
    }

    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(
            () ->
                !configuration
                    .executor()
                    .isRunning(streamSessionId, StreamSession.defaultVariant()));
    assertThat(configuration.segmentStore().segmentExists(streamSessionId, "segment1.m4s"))
        .as("the initial attempt ended without delivering segment 1")
        .isFalse();
  }

  private static double initializationSegmentMismatches(MeterRegistry meterRegistry) {
    return InitializationSegmentMismatchMetric.count(meterRegistry);
  }

  private record RecoveryRig(
      SegmentDeliveryCoordinator coordinator,
      StreamSession session,
      RecoveryRigConfiguration configuration) {}

  @Builder
  private record RecoveryRigConfiguration(
      TranscodeRequest initialAttempt,
      LocalSegmentStore segmentStore,
      HeldSegmentRequests heldRequests,
      RemoteTranscodeExecutor executor) {}

  private RecoveryRig recoveryRig(RecoveryRigConfiguration configuration) {
    var initialAttempt = configuration.initialAttempt();
    var session =
        StreamSession.builder()
            .sessionId(initialAttempt.sessionId())
            .mediaFileId(UUID.randomUUID())
            .authority(playbackAuthorityFor(UUID.randomUUID()))
            .sourcePath(initialAttempt.sourcePath())
            .mediaProbe(
                defaultProbeBuilder()
                    .framerate(OptionalDouble.of(24))
                    .width(64)
                    .height(36)
                    .bitrate(12_000)
                    .videoPlaybackFacts(initialAttempt.transcodeDecision().videoSource())
                    .build())
            .transcodeDecision(initialAttempt.transcodeDecision())
            .build();
    var registry = new FakeRuntimeStreamSessionRegistry();
    registry.save(session);
    var properties =
        StreamingProperties.builder()
            .targetSegmentDuration(Duration.ofSeconds(6))
            .producerStallThreshold(Duration.ofSeconds(3))
            .build();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .segmentStore(configuration.segmentStore())
            .transcodeExecutor(configuration.executor())
            .properties(properties)
            .runtimeRegistry(registry)
            .heldRequests(configuration.heldRequests())
            .build();
    return new RecoveryRig(rig.coordinator(), session, configuration);
  }

  private WorkerSessionServer server(LocalSegmentStore segmentStore) {
    return server(segmentStore, new SimpleMeterRegistry());
  }

  private static WorkerSessionServer server(
      LocalSegmentStore segmentStore, MeterRegistry meterRegistry) {
    return WorkerSessionServer.builder()
        .eventPublisher(new CapturingEventPublisher())
        .configuration(serverConfigurationBuilder().address("127.0.0.1").build())
        .segmentStore(segmentStore)
        .meterRegistry(meterRegistry)
        .build();
  }

  private static WorkerContainerFixture.WorkerContainerFixtureBuilder workerBuilder(
      WorkerSessionServer server, Path mediaRoot) {
    return WorkerContainerFixture.builder()
        .workerSessions(server)
        .sourceNamespaceId(SOURCE_NAMESPACE_ID)
        .sourceRoot(mediaRoot);
  }

  private static TranscodeRequest initialAttempt(
      UUID streamSessionId, Path mediaFile, TranscodeMode mode) {
    var sessionTimeline =
        new MediaSegmentTimeline.PlainGrid(
            defaultProbeBuilder().build().duration(), Duration.ofSeconds(6));
    return TranscodeRequest.builder()
        .sessionId(streamSessionId)
        .sourcePath(mediaFile)
        .targetSegmentDuration(sessionTimeline.targetSegmentDurationSeconds())
        .mediaSegmentCount(sessionTimeline.mediaSegmentCount())
        .framerate(OptionalDouble.of(24))
        .transcodeDecision(transcodeDecision(mode))
        .width(64)
        .height(36)
        .bitrate(12_000)
        .variantLabel(StreamSession.defaultVariant())
        .encoderPreset(EncoderPreset.FASTER)
        .build();
  }

  private static TranscodeDecision transcodeDecision(TranscodeMode mode) {
    return TranscodeDecision.builder()
        .transcodeMode(mode)
        .videoCodecFamily("h264")
        .audioDecision(
            AudioDecision.builder()
                .mode(AudioMode.COPY)
                .codec("aac")
                .channels(2)
                .bitrate(128_000)
                .sourceCodecString(Optional.of("mp4a.40.2"))
                .build())
        .subtitleDecision(SubtitleDecision.exclude())
        .videoSource(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .codecString(Optional.of("avc1.64000A"))
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                    .build()))
        .build();
  }
}
