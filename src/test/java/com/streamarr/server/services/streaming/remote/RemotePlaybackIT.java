package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.common.io.Closer;
import com.google.common.primitives.Bytes;
import com.streamarr.server.controllers.StreamController;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeStreamingService;
import com.streamarr.server.fakes.ForwardingPreparedSegment;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.RecordedStream;
import com.streamarr.server.fixtures.StoredSegments;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.properties.RemoteTranscodeProperties;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.streaming.DirectPlayMetrics;
import com.streamarr.server.services.streaming.DirectPlaySourceService;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.TranscodeDecisionRequest;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.Builder;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Tag("IntegrationTest")
@DisplayName("Remote Playback Integration Tests")
class RemotePlaybackIT {

  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  // The committed 10 s clip, and the first 8 s of it that each recording holds, both span two 6 s
  // media segments.
  private static final MediaSegmentTimeline.PlainGrid CLIP_TIMELINE =
      new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(10), Duration.ofSeconds(6));

  private static final String UNICODE_KEY =
      "東京 Café’s 🎬 %2F ..%2F dir/Ame\u0301lie’s 100%23 #1 한국 𝄞 (2001).mkv";

  // Tests that give FFmpeg the same scripted body share one worker and the server it connects to.
  private static final Map<String, SharedWorker> SHARED_WORKERS = new HashMap<>();

  @TempDir static Path sharedWorkersDir;

  @TempDir Path tempDir;

  @BeforeEach
  void resetSharedWorkers() throws IOException {
    for (var shared : SHARED_WORKERS.values()) {
      shared.reset();
    }
  }

  @AfterAll
  static void closeSharedWorkers() throws IOException {
    try (var closer = Closer.create()) {
      SHARED_WORKERS.values().forEach(closer::register);
      SHARED_WORKERS.clear();
    }
  }

  @Test
  @DisplayName(
      "Should serve the producer's initialization and media segments of probed media when using the standalone worker image")
  void
      shouldServeProducersInitializationAndMediaSegmentsOfProbedMediaWhenUsingStandaloneWorkerImage()
          throws Exception {
    var shared =
        sharedWorker(WorkerContainerFixture.emitRecordedStream(RecordedStream.START_AT_ZERO));
    var mediaRoot = shared.mediaRoot();
    var segmentStore = shared.segmentStore();
    var server = shared.server();
    var worker = shared.worker();
    var mediaFile = copyTestClip(mediaRoot.resolve("movie.mkv"));
    var streamSessionId = shared.newStreamSessionId();
    var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
    var probe =
        new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot)
            .probe(probeRequest(mediaFile));

    assertThat(probe)
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            outcome -> {
              assertThat(outcome.mediaProbe().videoCodec()).isEqualTo("h264");
              assertThat(outcome.mediaProbe().width()).isEqualTo(320);
              assertThat(outcome.mediaProbe().height()).isEqualTo(180);
            });

    var handle = executor.start(transcodeRequest(streamSessionId, mediaFile));
    segmentStore.publication(streamSessionId, "segment1.m4s").get(10, TimeUnit.SECONDS);
    var playback =
        rig(
            PlaybackRigConfiguration.builder()
                .streamSessionId(streamSessionId)
                .sourcePath(mediaFile)
                .segmentStore(segmentStore)
                .executor(executor)
                .heldRequests(server.heldSegmentRequests())
                .build());
    playback.session().setHandle(handle);
    var streamController = playback.controller();
    var bodies =
        List.of(
            segmentBody(() -> streamController.getInitSegment(streamSessionId)),
            segmentBody(() -> streamController.getSegment(streamSessionId, "segment0.m4s")),
            segmentBody(() -> streamController.getSegment(streamSessionId, "segment1.m4s")));

    assertThat(bodies)
        .extracting(Fmp4Fixture::firstBoxType)
        .as("the initialization segment, then media segments that each open with a fragment")
        .containsExactly("ftyp", "moof", "moof");
    assertThat(RecordedStream.START_AT_ZERO.bytes())
        .as("the producer's initialization segment reaches the client unchanged")
        .startsWith(bodies.getFirst());
    var servedMedia =
        Files.write(tempDir.resolve("served.mp4"), Bytes.concat(bodies.toArray(byte[][]::new)));
    var producerMedia =
        Files.write(tempDir.resolve("producer.mp4"), RecordedStream.START_AT_ZERO.bytes());
    assertThat(worker.videoPacketHashes(servedMedia))
        .as("every encoded video packet reaches the client in order")
        .isNotEmpty()
        .containsExactlyElementsOf(worker.videoPacketHashes(producerMedia));
    assertThat(worker.audioPacketHashes(servedMedia))
        .as("every encoded audio packet reaches the client in order")
        .isNotEmpty()
        .containsExactlyElementsOf(worker.audioPacketHashes(producerMedia));
  }

  @Test
  @DisplayName(
      "Should withhold an unfinished initialization segment when the producer's output ends inside it")
  void shouldWithholdUnfinishedInitializationSegmentWhenProducersOutputEndsInsideIt()
      throws Exception {
    var shared =
        sharedWorker(
            """
            head -c 100 %s
            exit 0
            """
                .formatted(RecordedStream.START_AT_ZERO.containerPath()));
    var mediaRoot = shared.mediaRoot();
    var segmentStore = shared.segmentStore();
    var mediaFile = copyTestClip(mediaRoot.resolve("movie.mkv"));
    var streamSessionId = shared.newStreamSessionId();
    var executor = new RemoteTranscodeExecutor(shared.server(), SOURCE_NAMESPACE_ID, mediaRoot);

    executor.start(transcodeRequest(streamSessionId, mediaFile));
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> !executor.isRunning(streamSessionId, StreamSession.defaultVariant()));

    assertThat(segmentStore.segmentExists(streamSessionId, "init.mp4"))
        .as("An unfinished initialization segment must never become available to playback")
        .isFalse();
  }

  @Test
  @DisplayName(
      "Should serve a segment requested before the worker's first upload arrives when using a remote worker")
  void shouldServeSegmentRequestedBeforeWorkersFirstUploadArrivesWhenUsingRemoteWorker()
      throws Exception {
    var shared =
        sharedWorker(WorkerContainerFixture.emitRecordedStream(RecordedStream.START_AT_ZERO));
    var mediaRoot = shared.mediaRoot();
    var segmentStore = shared.segmentStore();
    var server = shared.server();
    var worker = shared.worker();
    var mediaFile = copyTestClip(mediaRoot.resolve("movie.mkv"));
    var streamSessionId = shared.newStreamSessionId();
    segmentStore.holdPublicationUntilASegmentIsFoundMissing(streamSessionId);
    var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
    var playback =
        rig(
            PlaybackRigConfiguration.builder()
                .streamSessionId(streamSessionId)
                .sourcePath(mediaFile)
                .segmentStore(segmentStore)
                .executor(executor)
                .heldRequests(server.heldSegmentRequests())
                .build());

    var handle = executor.start(transcodeRequest(streamSessionId, mediaFile));
    playback.session().setHandle(handle);
    var served =
        segmentBody(() -> playback.controller().getSegment(streamSessionId, "segment0.m4s"));

    assertThat(Fmp4Fixture.firstBoxType(served)).isEqualTo("moof");
    var servedMedia =
        Files.write(
            tempDir.resolve("served-first-segment.mp4"),
            Fmp4Fixture.withInitializationSegment(segmentStore, streamSessionId, served));
    assertThat(worker.decodedVideoFrameCount(servedMedia)).isPositive();
  }

  @Test
  @DisplayName("Should preserve executable transcode settings when using a remote worker")
  void shouldPreserveExecutableTranscodeSettingsWhenUsingRemoteWorker() throws Exception {
    var shared = commandCapturingWorker();
    var mediaFile = copyTestClip(shared.mediaRoot().resolve("movie.mkv"));
    var requests =
        supportedTranscodeDecisions().stream()
            .map(
                decision ->
                    executableRequestBuilder(shared, mediaFile)
                        .framerate(OptionalDouble.of(23.976))
                        .transcodeDecision(decision)
                        .build())
            .toList();

    launchedCommands(shared, requests)
        .forEach(
            (request, command) ->
                assertCommandPreservesDecision(command, request.transcodeDecision()));
  }

  @Test
  @DisplayName("Should launch a stream copy when the request has no frame rate")
  void shouldLaunchAStreamCopyWhenTheRequestHasNoFrameRate() throws Exception {
    var shared = commandCapturingWorker();
    var mediaFile = copyTestClip(shared.mediaRoot().resolve("movie.mkv"));
    var streamCopyModes = EnumSet.of(TranscodeMode.REMUX, TranscodeMode.AUDIO_TRANSCODE);
    var requests =
        supportedTranscodeDecisions().stream()
            .filter(decision -> streamCopyModes.contains(decision.transcodeMode()))
            .map(
                decision ->
                    executableRequestBuilder(shared, mediaFile)
                        .framerate(OptionalDouble.empty())
                        .transcodeDecision(decision)
                        .build())
            .toList();

    var commands = launchedCommands(shared, requests);

    assertThat(commands).hasSize(2);
    commands.forEach(
        (request, command) -> assertCommandPreservesDecision(command, request.transcodeDecision()));
  }

  private TranscodeRequest.TranscodeRequestBuilder executableRequestBuilder(
      SharedWorker shared, Path mediaFile) {
    return TranscodeRequest.builder()
        .sessionId(shared.newStreamSessionId())
        .sourcePath(mediaFile)
        .targetSegmentDuration(4)
        .width(1920)
        .height(720)
        .bitrate(2_500_000)
        .seekPosition(12)
        .startSequenceNumber(3)
        .mediaSegmentCount(6)
        .variantLabel(StreamSession.defaultVariant())
        .encoderPreset(EncoderPreset.FASTER);
  }

  private static SharedWorker commandCapturingWorker() throws IOException {
    return sharedWorker("read -r -n 1 command\nexit 0\n");
  }

  private Map<TranscodeRequest, List<String>> launchedCommands(
      SharedWorker shared, List<TranscodeRequest> requests) throws Exception {
    var server = shared.server();
    var worker = shared.worker();
    var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, shared.mediaRoot());
    var commands = new LinkedHashMap<TranscodeRequest, List<String>>();

    for (var request : requests) {
      var handle = executor.start(request);
      await()
          .atMost(2, TimeUnit.SECONDS)
          .until(() -> worker.commandFor(handle.attemptId()).isPresent());
      commands.put(request, worker.commandFor(handle.attemptId()).orElseThrow());
      executor.stop(request.sessionId());
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> server.availableSlots(SOURCE_NAMESPACE_ID) == 1);
    }

    return commands;
  }

  @Test
  @DisplayName(
      "Should probe media whose names contain Unicode and percents when using the standalone worker image")
  void shouldProbeMediaWhoseNamesContainUnicodeAndPercentsWhenUsingStandaloneWorkerImage()
      throws Exception {
    var shared = sharedWorker();
    var mediaRoot = shared.mediaRoot();
    var mediaFile = copyTestClip(mediaRoot.resolve(UNICODE_KEY));
    var probe =
        new RemoteFfprobeService(shared.server(), SOURCE_NAMESPACE_ID, mediaRoot)
            .probe(probeRequest(mediaFile));

    assertThat(probe)
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            outcome -> assertThat(outcome.mediaProbe().videoCodec()).isEqualTo("h264"));
  }

  @Test
  @DisplayName(
      "Should stream media whose names contain Unicode and percents when using the standalone worker image")
  void shouldStreamMediaWhoseNamesContainUnicodeAndPercentsWhenUsingStandaloneWorkerImage()
      throws Exception {
    var shared = sharedWorker();
    var mediaRoot = shared.mediaRoot();
    var segmentStore = shared.segmentStore();
    var mediaFile = copyTestClip(mediaRoot.resolve(UNICODE_KEY));
    var streamSessionId = shared.newStreamSessionId();
    var executor = new RemoteTranscodeExecutor(shared.server(), SOURCE_NAMESPACE_ID, mediaRoot);
    executor.start(transcodeRequest(streamSessionId, mediaFile));

    assertThat(segmentStore.publication(streamSessionId, "segment0.m4s"))
        .as("first segment transcoded from the Unicode source")
        .succeedsWithin(Duration.ofSeconds(30));
    executor.stop(streamSessionId);
  }

  @Test
  @DisplayName(
      "Should encode AV1 on the standalone worker image when the client declares AV1 and the worker advertises its encoders")
  void shouldEncodeAv1OnStandaloneWorkerImageWhenClientDeclaresAv1AndWorkerAdvertisesItsEncoders()
      throws Exception {
    var shared = sharedWorker();
    var mediaRoot = shared.mediaRoot();
    var segmentStore = shared.segmentStore();
    var server = shared.server();
    var worker = shared.worker();
    var streamSessionId = shared.newStreamSessionId();
    var encoderBackendMismatches =
        shared.meterRegistry().get("streamarr.streaming.encoder_backend_mismatches").counter();
    var encoderBackendMismatchesBefore = encoderBackendMismatches.count();
    var mediaFile =
        worker.generateMedia(
            mediaRoot.resolve("h264-source.mkv"),
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=320x180:rate=24:duration=4",
                "-c:v",
                "libx264",
                "-g",
                "24"));
    var mediaProbe =
        switch (new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot)
            .probe(probeRequest(mediaFile))) {
          case ProbeOutcome.Success success -> success.mediaProbe();
          case ProbeOutcome.Failure failure -> throw new AssertionError(failure.toString());
        };
    var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
    var decision =
        new TranscodeDecisionService(
                StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
                new SimpleMeterRegistry())
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(mediaProbe)
                    .capabilityDeclaration(declarationBuilderFor(ProbedCodecFamily.AV1).build())
                    .encodableVideoCodecFamilies(executor.encodableVideoCodecFamilies())
                    .hardwareEncodableVideoCodecFamilies(
                        executor.hardwareEncodableVideoCodecFamilies())
                    .build())
            .orElseThrow();
    var timeline = new MediaSegmentTimeline.PlainGrid(mediaProbe.duration(), Duration.ofSeconds(6));

    assertThat(executor.encodableVideoCodecFamilies())
        .as("the codec families that the worker image advertises")
        .containsExactlyInAnyOrder("h264", "hevc", "av1");
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
    var handle =
        executor.start(
            TranscodeRequest.builder()
                .sessionId(streamSessionId)
                .sourcePath(mediaFile)
                .targetSegmentDuration(timeline.targetSegmentDurationSeconds())
                .mediaSegmentCount(timeline.mediaSegmentCount())
                .framerate(mediaProbe.framerate())
                .transcodeDecision(decision)
                .width(mediaProbe.width())
                .height(mediaProbe.height())
                .bitrate(1_000_000)
                .encoderPreset(EncoderPreset.FASTER)
                .build());

    assertThat(handle.encoder())
        .as("the encoder backend that the variant is pinned to")
        .hasValueSatisfying(
            encoder -> {
              assertThat(encoder.backend().encoder()).isEqualTo("libsvtav1");
              assertThat(encoder.keyframeVerified()).isTrue();
            });
    assertThat(segmentStore.publication(streamSessionId, "segment0.m4s"))
        .as("the first media segment of the AV1 encode")
        .succeedsWithin(Duration.ofSeconds(60));
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> !executor.isRunning(streamSessionId, StreamSession.defaultVariant()));
    assertThat(encoderBackendMismatches.count() - encoderBackendMismatchesBefore)
        .as("job attempts stopped because the worker ran another encoder backend")
        .isZero();
    assertThat(
            new String(
                StoredSegments.bytes(
                    segmentStore, streamSessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME),
                StandardCharsets.ISO_8859_1))
        .as("the AV1 sample entry in the initialization segment")
        .contains("av01");
  }

  @Test
  @DisplayName(
      "Should stream an MP3 source whose sample rate fragmented MP4 cannot carry when the client declares AAC")
  void shouldStreamMp3SourceWhoseSampleRateFragmentedMp4CannotCarryWhenTheClientDeclaresAac()
      throws Exception {
    var shared = sharedWorker();
    var mediaRoot = shared.mediaRoot();
    var segmentStore = shared.segmentStore();
    var server = shared.server();
    var worker = shared.worker();
    var streamSessionId = shared.newStreamSessionId();
    var mediaFile =
        worker.generateMedia(
            mediaRoot.resolve("eight-kilohertz-mp3.mkv"),
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=64x36:rate=24:duration=4",
                "-f",
                "lavfi",
                "-i",
                "sine=sample_rate=8000:duration=4",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-g",
                "24",
                "-c:a",
                "libmp3lame"));
    var mediaProbe =
        switch (new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot)
            .probe(probeRequest(mediaFile))) {
          case ProbeOutcome.Success success -> success.mediaProbe();
          case ProbeOutcome.Failure failure -> throw new AssertionError(failure.toString());
        };
    assertThat(mediaProbe.audioCodec()).isEqualTo("mp3");
    var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
    var decision =
        new TranscodeDecisionService(
                StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
                new SimpleMeterRegistry())
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(mediaProbe)
                    .capabilityDeclaration(defaultDeclarationBuilder().build())
                    .encodableVideoCodecFamilies(executor.encodableVideoCodecFamilies())
                    .build())
            .orElseThrow();
    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    var timeline = new MediaSegmentTimeline.PlainGrid(mediaProbe.duration(), Duration.ofSeconds(6));

    executor.start(
        TranscodeRequest.builder()
            .sessionId(streamSessionId)
            .sourcePath(mediaFile)
            .targetSegmentDuration(timeline.targetSegmentDurationSeconds())
            .mediaSegmentCount(timeline.mediaSegmentCount())
            .framerate(mediaProbe.framerate())
            .transcodeDecision(decision)
            .width(mediaProbe.width())
            .height(mediaProbe.height())
            .bitrate(mediaProbe.bitrate())
            .encoderPreset(EncoderPreset.FASTER)
            .build());

    assertThat(segmentStore.publication(streamSessionId, "segment0.m4s"))
        .as("the first media segment of the MP3 source")
        .succeedsWithin(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("Should diagnose the locale when the worker starts under an ASCII locale")
  void shouldDiagnoseLocaleWhenWorkerStartsUnderAsciiLocale() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));

    try (var server = server(segmentStore);
        var worker = workerBuilder(server, mediaRoot).filenameLocale("POSIX").build()) {
      server.start();
      worker.start();

      assertThat(worker.logs())
          .contains("rather than UTF-8")
          .contains("the effective locale is LC_ALL=POSIX");
    }
  }

  @Test
  @DisplayName("Should refuse a remote transcode when no worker is connected")
  void shouldRefuseRemoteTranscodeWhenNoWorkerIsConnected() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));

    try (var server = server(segmentStore)) {
      server.start();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var request = transcodeRequest(UUID.randomUUID(), mediaFile);

      assertThat(executor.isHealthy()).isFalse();
      assertThatThrownBy(() -> executor.start(request))
          .isInstanceOf(TranscodeException.class)
          .hasMessage("No connected transcode worker can run this variant");
    }
  }

  @Test
  @DisplayName(
      "Should reject media outside the configured source namespace when using a remote worker")
  void shouldRejectMediaOutsideConfiguredSourceNamespaceWhenUsingRemoteWorker() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var outsideFile = Files.writeString(tempDir.resolve("outside.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));

    try (var server = server(segmentStore)) {
      server.start();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var request = transcodeRequest(UUID.randomUUID(), outsideFile);

      assertThatThrownBy(() -> executor.start(request))
          .isInstanceOf(TranscodeException.class)
          .hasMessage("Media source is outside the configured source namespace");
    }
  }

  // With an empty scripted body, the worker runs FFmpeg as it launched it.
  private static SharedWorker sharedWorker() throws IOException {
    return sharedWorker("");
  }

  private static SharedWorker sharedWorker(String ffmpegScript) throws IOException {
    var started = SHARED_WORKERS.get(ffmpegScript);
    if (started != null) {
      return started;
    }

    var directory = Files.createTempDirectory(sharedWorkersDir, "worker-");
    var mediaRoot = Files.createDirectory(directory.resolve("media"));
    var segmentStore = new PublishingSegmentStore(directory.resolve("server-segments"));
    var meterRegistry = new SimpleMeterRegistry();
    var server = server(segmentStore, meterRegistry);
    var shared =
        SharedWorker.builder()
            .mediaRoot(mediaRoot)
            .segmentStore(segmentStore)
            .meterRegistry(meterRegistry)
            .server(server)
            .worker(workerBuilder(server, mediaRoot).ffmpegScript(ffmpegScript).build())
            .streamSessionIds(new HashSet<>())
            .build();
    shared.start();
    SHARED_WORKERS.put(ffmpegScript, shared);
    return shared;
  }

  private static WorkerSessionServer server(LocalSegmentStore segmentStore) {
    return server(segmentStore, new SimpleMeterRegistry());
  }

  private static WorkerSessionServer server(
      LocalSegmentStore segmentStore, SimpleMeterRegistry meterRegistry) {
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

  private Path copyTestClip(Path mediaFile) throws Exception {
    var source = getClass().getResource("/BigBuckBunny_320x180_10s.mp4");
    assertThat(source).isNotNull();
    Files.createDirectories(mediaFile.getParent());
    return Files.copy(Path.of(source.toURI()), mediaFile);
  }

  private ProbeExecutionRequest probeRequest(Path sourcePath) {
    return ProbeExecutionRequest.builder()
        .sourcePath(sourcePath)
        .attemptId(UUID.randomUUID())
        .probeVersion(ProbeVersion.CURRENT)
        .priority(ProbePriority.BACKGROUND)
        .build();
  }

  @Test
  @DisplayName("Should refuse dispatch when no worker connection can run the variant")
  void shouldRefuseDispatchWhenNoWorkerConnectionCanRunTheVariant() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));

    try (var server = server(segmentStore)) {
      server.start();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var request = transcodeRequest(UUID.randomUUID(), mediaRoot.resolve("movie.mkv"));

      // The thrown refusal is what recovery classifies as ReplaceResult.Refused — the exact
      // contract that moves it to the next execution target.
      assertThatThrownBy(() -> executor.start(request)).isInstanceOf(TranscodeException.class);
      var ghostWorker = new ExecutionTargetId("ghost-worker");
      assertThatThrownBy(() -> executor.start(request, ghostWorker))
          .isInstanceOf(TranscodeException.class)
          .hasMessageContaining("ghost-worker");
    }
  }

  private record PlaybackRig(StreamController controller, StreamSession session) {}

  @Builder
  private record PlaybackRigConfiguration(
      UUID streamSessionId,
      Path sourcePath,
      LocalSegmentStore segmentStore,
      RemoteTranscodeExecutor executor,
      HeldSegmentRequests heldRequests) {}

  private PlaybackRig rig(PlaybackRigConfiguration configuration) {
    var session =
        StreamSession.builder()
            .sessionId(configuration.streamSessionId())
            .mediaFileId(UUID.randomUUID())
            .authority(StreamSessionFixture.playbackAuthorityFor(UUID.randomUUID()))
            .sourcePath(configuration.sourcePath())
            .mediaProbe(StreamSessionFixture.defaultProbeBuilder().build())
            .transcodeDecision(transcodeDecision())
            .build();
    var registry = new FakeRuntimeStreamSessionRegistry();
    registry.save(session);
    var streamingService = new FakeStreamingService(registry);
    var properties =
        StreamingProperties.builder()
            .targetSegmentDuration(CLIP_TIMELINE.targetSegmentDuration())
            .producerStallThreshold(Duration.ofSeconds(5))
            .build();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .segmentStore(configuration.segmentStore())
            .transcodeExecutor(configuration.executor())
            .properties(properties)
            .runtimeRegistry(registry)
            .heldRequests(configuration.heldRequests())
            .build();
    var authorizationService =
        new FakeAuthorizationService(() -> identity(configuration.streamSessionId()), "it-token");
    var directPlayMetrics = new DirectPlayMetrics(new SimpleMeterRegistry());
    var controller =
        new StreamController(
            streamingService,
            new HlsPlaylistService(properties),
            rig.coordinator(),
            authorizationService,
            new DirectPlaySourceService(
                streamingService,
                new RemoteTranscodeProperties(UUID.randomUUID(), "/media"),
                directPlayMetrics),
            directPlayMetrics);
    return new PlaybackRig(controller, session);
  }

  private TranscodeRequest transcodeRequest(UUID streamSessionId, Path mediaFile) {
    return TranscodeRequest.builder()
        .sessionId(streamSessionId)
        .sourcePath(mediaFile)
        .targetSegmentDuration(CLIP_TIMELINE.targetSegmentDurationSeconds())
        .mediaSegmentCount(CLIP_TIMELINE.mediaSegmentCount())
        .framerate(OptionalDouble.of(24))
        .transcodeDecision(transcodeDecision())
        .width(64)
        .height(36)
        .bitrate(12_000)
        .variantLabel(StreamSession.defaultVariant())
        .encoderPreset(EncoderPreset.FASTER)
        .build();
  }

  private TranscodeDecision transcodeDecision() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.FULL_TRANSCODE)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude())
        .videoSource(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                    .build()))
        .build();
  }

  private List<TranscodeDecision> supportedTranscodeDecisions() {
    return List.of(
        decisionBuilder()
            .transcodeMode(TranscodeMode.REMUX)
            .audioDecision(
                AudioDecision.builder()
                    .mode(AudioMode.COPY)
                    .codec("aac")
                    .channels(2)
                    .bitrate(128_000)
                    .sourceCodecString(Optional.of("mp4a.40.2"))
                    .build())
            .build(),
        decisionBuilder()
            .transcodeMode(TranscodeMode.AUDIO_TRANSCODE)
            .audioDecision(AudioDecision.stereoAac())
            .build(),
        decisionBuilder()
            .transcodeMode(TranscodeMode.VIDEO_TRANSCODE)
            .audioDecision(AudioDecision.none())
            .build(),
        decisionBuilder()
            .transcodeMode(TranscodeMode.FULL_TRANSCODE)
            .audioDecision(
                AudioDecision.builder()
                    .mode(AudioMode.COPY)
                    .codec("aac")
                    .channels(2)
                    .bitrate(128_000)
                    .sourceCodecString(Optional.of("mp4a.40.2"))
                    .build())
            .build(),
        decisionBuilder()
            .transcodeMode(TranscodeMode.FULL_TRANSCODE)
            .audioDecision(AudioDecision.stereoAac())
            .build());
  }

  private TranscodeDecision.TranscodeDecisionBuilder decisionBuilder() {
    return TranscodeDecision.builder()
        .videoCodecFamily("h264")
        .subtitleDecision(SubtitleDecision.exclude())
        .videoSource(Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()));
  }

  // The controller closes an opened segment when its request ends, so a direct call needs one.
  private static byte[] segmentBody(Supplier<ResponseEntity<Resource>> request) throws IOException {
    var attributes = new ServletRequestAttributes(new MockHttpServletRequest());
    RequestContextHolder.setRequestAttributes(attributes);
    try {
      var response = request.get();
      assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
      assertThat(response.getHeaders().getContentType()).hasToString("video/mp4");
      return response.getBody().getContentAsByteArray();
    } finally {
      attributes.requestCompleted();
      RequestContextHolder.resetRequestAttributes();
    }
  }

  // A copy that encodes its audio reads from one 4 s period earlier, so that its audio encoder has
  // its lead before the first segment's start.
  private static double copySeekOf(TranscodeDecision decision) {
    if (decision.transcodeMode() == TranscodeMode.AUDIO_TRANSCODE) {
      return 8;
    }

    return 12;
  }

  private void assertCommandPreservesDecision(List<String> command, TranscodeDecision decision) {
    var streamCopiesVideo =
        switch (decision.transcodeMode()) {
          case REMUX, AUDIO_TRANSCODE -> true;
          case VIDEO_TRANSCODE, FULL_TRANSCODE -> false;
        };
    assertThat(command)
        .as("source and absolute timeline for %s", decision)
        .containsSubsequence("-i", "/media/movie.mkv")
        .containsSubsequence("-map", "-0:s")
        .contains("-copyts", "-start_at_zero")
        .doesNotContain("-start_number");
    assertThat(command)
        .as("fragmented MP4 on standard output for %s", decision)
        .containsSubsequence("-f", "mp4")
        .containsSubsequence("-movflags", "cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont")
        .containsSubsequence("-f", "mp4", "pipe:1")
        .doesNotContain("-hls_time", "-hls_segment_type");
    assertAudioPreservesDecision(command, decision);
    var seek = Double.parseDouble(argument(command, "-ss"));
    if (streamCopiesVideo) {
      assertThat(argument(command, "-c:v")).as("video codec for %s", decision).isEqualTo("copy");
      assertThat(seek).as("stream copy seek for %s", decision).isEqualTo(copySeekOf(decision));
      return;
    }

    assertThat(argument(command, "-c:v")).as("video codec for %s", decision).isEqualTo("libx264");
    assertThat(seek)
        .as("encode seek at or before the first advertised boundary for %s", decision)
        .isLessThanOrEqualTo(12);
    assertThat(argument(command, "-vf"))
        .as("video filters for %s", decision)
        .startsWith(
            "scale=-2:720:out_color_matrix=bt709:out_range=tv:out_primaries=bt709:out_transfer=bt709,format=yuv420p,fps=24000/1001:");
    assertThat(command)
        .as("video encoding settings for %s", decision)
        .containsSubsequence("-r:v:0", "24000/1001")
        .containsSubsequence("-b:v", "2500000")
        .containsSubsequence("-maxrate", "2500000")
        .containsSubsequence("-bufsize", "5000000")
        .containsSubsequence("-force_key_frames:0", "12,16,20");
  }

  private void assertAudioPreservesDecision(List<String> command, TranscodeDecision decision) {
    switch (decision.audioDecision().mode()) {
      case COPY -> assertThat(argument(command, "-c:a")).isEqualTo("copy");
      case TRANSCODE ->
          assertThat(command)
              .as("stereo AAC audio for %s", decision)
              .containsSubsequence("-c:a", "aac")
              .containsSubsequence("-ch_layout", "stereo")
              .containsSubsequence("-b:a", "128k");
      case NONE -> assertThat(command).doesNotContain("-c:a", "0:a:0");
    }
  }

  private String argument(List<String> command, String flag) {
    assertThat(command).as("FFmpeg option %s", flag).contains(flag);
    return command.get(command.indexOf(flag) + 1);
  }

  private AuthenticatedIdentity identity(UUID streamSessionId) {
    return AuthenticatedIdentityFixture.defaultIdentityBuilder()
        .streamSessionId(streamSessionId)
        .build();
  }

  @Builder
  private record SharedWorker(
      Path mediaRoot,
      PublishingSegmentStore segmentStore,
      SimpleMeterRegistry meterRegistry,
      WorkerSessionServer server,
      WorkerContainerFixture worker,
      Set<UUID> streamSessionIds)
      implements Closeable {

    // A pair that fails to start is closed here, so sharedWorker keeps only pairs that started.
    private void start() throws IOException {
      var started = false;
      try {
        server.start();
        worker.start();
        started = true;
      } finally {
        if (!started) {
          close();
        }
      }
    }

    // reset() stops every job attempt that a test starts for this stream session.
    private UUID newStreamSessionId() {
      var streamSessionId = UUID.randomUUID();
      streamSessionIds.add(streamSessionId);
      return streamSessionId;
    }

    // Once stopped, a job attempt of an earlier test can neither upload nor hold the worker's one
    // slot in the next test. A copy holds no slot, so the slot wait cannot see a running one.
    private void reset() throws IOException {
      streamSessionIds.forEach(server::destroyStreamSession);
      streamSessionIds.clear();
      await()
          .pollDelay(Duration.ZERO)
          .atMost(Duration.ofSeconds(30))
          .until(() -> server.availableSlots(SOURCE_NAMESPACE_ID) == 1);
      FileUtils.cleanDirectory(mediaRoot.toFile());
    }

    @Override
    public void close() throws IOException {
      try (var closer = Closer.create()) {
        closer.register(server::close);
        closer.register(worker::close);
      }
    }
  }

  private static final class PublishingSegmentStore extends LocalSegmentStore {
    private static final CountDownLatch NOT_HELD = new CountDownLatch(0);
    private final Map<SessionSegment, CompletableFuture<Void>> publications =
        new ConcurrentHashMap<>();
    private final Map<UUID, CountDownLatch> heldPublications = new ConcurrentHashMap<>();

    private PublishingSegmentStore(Path baseDir) {
      super(baseDir);
    }

    private CompletableFuture<Void> publication(UUID sessionId, String segmentName) {
      return publications.computeIfAbsent(
          new SessionSegment(sessionId, segmentName), _ -> new CompletableFuture<>());
    }

    private void holdPublicationUntilASegmentIsFoundMissing(UUID sessionId) {
      heldPublications.put(sessionId, new CountDownLatch(1));
    }

    @Override
    public boolean segmentExists(UUID sessionId, String segmentName) {
      var exists = super.segmentExists(sessionId, segmentName);
      if (!exists) {
        heldPublications.getOrDefault(sessionId, NOT_HELD).countDown();
      }

      return exists;
    }

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName) {
      var prepared = super.prepareSegment(sessionId, segmentName);
      return new ForwardingPreparedSegment(prepared) {
        @Override
        public SegmentPublication publish() {
          awaitMissingSegment(heldPublications.getOrDefault(sessionId, NOT_HELD));
          var outcome = prepared.publish();
          publication(sessionId, segmentName).complete(null);
          return outcome;
        }
      };
    }

    private static void awaitMissingSegment(CountDownLatch requestedWhileMissing) {
      try {
        assertThat(requestedWhileMissing.await(5, TimeUnit.SECONDS))
            .as("Playback must observe the missing segment before the first upload is published")
            .isTrue();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while awaiting the first segment request", exception);
      }
    }
  }

  private record SessionSegment(UUID sessionId, String segmentName) {}
}
