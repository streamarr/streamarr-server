package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.common.primitives.Bytes;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.controllers.StreamController;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeStreamingService;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.RecordedStream;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

@Tag("IntegrationTest")
@DisplayName("Remote Playback Integration Tests")
class RemotePlaybackIT {

  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  // The committed 10 s clip, and the first 8 s of it that each recording holds, both span two 6 s
  // media segments.
  private static final int CLIP_MEDIA_SEGMENTS =
      new MediaSegmentTimeline(Duration.ofSeconds(10), Duration.ofSeconds(6)).mediaSegmentCount();

  private static final String UNICODE_KEY =
      "東京 Café’s 🎬 %2F ..%2F dir/Ame\u0301lie’s 100%23 #1 한국 𝄞 (2001).mkv";

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Should serve the producer's initialization and media segments of probed media when using the standalone worker image")
  void
      shouldServeProducersInitializationAndMediaSegmentsOfProbedMediaWhenUsingStandaloneWorkerImage()
          throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = copyTestClip(mediaRoot.resolve("movie.mkv"));
    var segmentStore = new PublishingSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore);
        var worker =
            workerBuilder(server, mediaRoot)
                .ffmpegScript(
                    WorkerContainerFixture.emitRecordedStream(RecordedStream.START_AT_ZERO))
                .build()) {
      server.start();
      worker.start();
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

      executor.start(transcodeRequest(streamSessionId, mediaFile));
      segmentStore.publication("segment1.m4s").get(10, TimeUnit.SECONDS);
      var streamController =
          rig(PlaybackRigConfiguration.builder()
                  .streamSessionId(streamSessionId)
                  .segmentStore(segmentStore)
                  .executor(executor)
                  .build())
              .controller();
      var responses =
          List.of(
              streamController.getInitSegment(streamSessionId),
              streamController.getSegment(streamSessionId, "segment0.m4s"),
              streamController.getSegment(streamSessionId, "segment1.m4s"));

      assertThat(responses)
          .allSatisfy(
              response -> {
                assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
                assertThat(response.getHeaders().getContentType()).hasToString("video/mp4");
              });
      var bodies = responses.stream().map(ResponseEntity::getBody).toList();
      assertThat(bodies)
          .extracting(Fmp4Fixture::firstBoxType)
          .as("the initialization segment, then media segments that each open with a fragment")
          .containsExactly("ftyp", "moof", "moof");
      assertThat(Bytes.concat(bodies.toArray(byte[][]::new)))
          .as("every byte FFmpeg wrote, in order and split only between segments")
          .isEqualTo(RecordedStream.START_AT_ZERO.bytes());
    }
  }

  @Test
  @DisplayName(
      "Should withhold an unfinished initialization segment when the producer's output ends inside it")
  void shouldWithholdUnfinishedInitializationSegmentWhenProducersOutputEndsInsideIt()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore);
        var worker =
            workerBuilder(server, mediaRoot)
                .ffmpegScript(
                    "head -c 100 %s\nexit 0\n"
                        .formatted(RecordedStream.START_AT_ZERO.containerPath()))
                .build()) {
      server.start();
      worker.start();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);

      executor.start(transcodeRequest(streamSessionId, mediaFile));
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> !executor.isRunning(streamSessionId, StreamSession.defaultVariant()));

      assertThat(segmentStore.segmentExists(streamSessionId, "init.mp4"))
          .as("An unfinished initialization segment must never become available to playback")
          .isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should serve a segment requested before the worker's first upload arrives when using a remote worker")
  void shouldServeSegmentRequestedBeforeWorkersFirstUploadArrivesWhenUsingRemoteWorker()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new FirstRequestSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore);
        var worker =
            workerBuilder(server, mediaRoot)
                .ffmpegScript(
                    WorkerContainerFixture.emitRecordedStream(RecordedStream.START_AT_ZERO))
                .build()) {
      server.start();
      worker.start();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var playback =
          rig(
              PlaybackRigConfiguration.builder()
                  .streamSessionId(streamSessionId)
                  .segmentStore(segmentStore)
                  .executor(executor)
                  .build());

      var handle = executor.start(transcodeRequest(streamSessionId, mediaFile));
      playback.session().setHandle(handle);
      var response = playback.controller().getSegment(streamSessionId, "segment0.m4s");

      assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
      assertThat(Fmp4Fixture.firstBoxType(response.getBody())).isEqualTo("moof");
      assertThat(RecordedStream.START_AT_ZERO.bytes())
          .startsWith(
              Fmp4Fixture.withInitializationSegment(
                  segmentStore, streamSessionId, response.getBody()));
    }
  }

  @Test
  @DisplayName("Should preserve executable transcode settings when using a remote worker")
  void shouldPreserveExecutableTranscodeSettingsWhenUsingRemoteWorker() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var requests =
        supportedTranscodeDecisions().stream()
            .map(
                decision ->
                    executableRequestBuilder(mediaFile)
                        .framerate(OptionalDouble.of(23.976))
                        .transcodeDecision(decision)
                        .build())
            .toList();

    launchedCommands(mediaRoot, requests)
        .forEach(
            (request, command) ->
                assertCommandPreservesDecision(command, request.transcodeDecision()));
  }

  @Test
  @DisplayName("Should launch a stream copy when the request has no frame rate")
  void shouldLaunchAStreamCopyWhenTheRequestHasNoFrameRate() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var streamCopyModes = EnumSet.of(TranscodeMode.REMUX, TranscodeMode.AUDIO_TRANSCODE);
    var requests =
        supportedTranscodeDecisions().stream()
            .filter(decision -> streamCopyModes.contains(decision.transcodeMode()))
            .map(
                decision ->
                    executableRequestBuilder(mediaFile)
                        .framerate(OptionalDouble.empty())
                        .transcodeDecision(decision)
                        .build())
            .toList();

    var commands = launchedCommands(mediaRoot, requests);

    assertThat(commands).hasSize(2);
    commands.forEach(
        (request, command) -> assertCommandPreservesDecision(command, request.transcodeDecision()));
  }

  private TranscodeRequest.TranscodeRequestBuilder executableRequestBuilder(Path mediaFile) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(mediaFile)
        .targetSegmentDuration(4)
        .width(1920)
        .height(720)
        .bitrate(2_500_000)
        .seekPosition(12)
        .startSequenceNumber(3)
        .mediaSegmentCount(6)
        .variantLabel(StreamSession.defaultVariant());
  }

  private Map<TranscodeRequest, List<String>> launchedCommands(
      Path mediaRoot, List<TranscodeRequest> requests) throws Exception {
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));

    try (var server = server(segmentStore);
        var worker =
            workerBuilder(server, mediaRoot)
                .ffmpegScript("read -r -n 1 command\nexit 0\n")
                .build()) {
      server.start();
      worker.start();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
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
  }

  @Test
  @DisplayName(
      "Should probe media whose names contain Unicode and percents when using the standalone worker image")
  void shouldProbeMediaWhoseNamesContainUnicodeAndPercentsWhenUsingStandaloneWorkerImage()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = copyTestClip(mediaRoot.resolve(UNICODE_KEY));
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));

    try (var server = server(segmentStore);
        var worker = workerBuilder(server, mediaRoot).build()) {
      server.start();
      worker.start();
      var probe =
          new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot)
              .probe(probeRequest(mediaFile));

      assertThat(probe)
          .isInstanceOfSatisfying(
              ProbeOutcome.Success.class,
              outcome -> assertThat(outcome.mediaProbe().videoCodec()).isEqualTo("h264"));
    }
  }

  @Test
  @DisplayName(
      "Should stream media whose names contain Unicode and percents when using the standalone worker image")
  void shouldStreamMediaWhoseNamesContainUnicodeAndPercentsWhenUsingStandaloneWorkerImage()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = copyTestClip(mediaRoot.resolve(UNICODE_KEY));
    var segmentStore = new PublishingSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore);
        var worker = workerBuilder(server, mediaRoot).build()) {
      server.start();
      worker.start();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      executor.start(transcodeRequest(streamSessionId, mediaFile));

      assertThat(segmentStore.publication("segment0.m4s"))
          .as("first segment transcoded from the Unicode source")
          .succeedsWithin(Duration.ofSeconds(30));
      executor.stop(streamSessionId);
    }
  }

  @Test
  @DisplayName(
      "Should fail the probe for retry when the worker reads filenames under an ASCII locale")
  void shouldFailProbeForRetryWhenWorkerReadsFilenamesUnderAsciiLocale() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("Café Meridian (2006).mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));

    try (var server = server(segmentStore);
        var worker = workerBuilder(server, mediaRoot).filenameLocale("POSIX").build()) {
      server.start();
      worker.start();
      var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var request = probeRequest(mediaFile);

      assertThatThrownBy(() -> service.probe(request))
          .isInstanceOf(ProbeExecutionException.class)
          .hasRootCauseMessage(
              "Worker probe reported a retryable failure: PROBE_FAILURE_SOURCE_UNAVAILABLE");
    }
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

  private WorkerSessionServer server(LocalSegmentStore segmentStore) {
    return new WorkerSessionServer(
        serverConfigurationBuilder().address("127.0.0.1").build(),
        segmentStore,
        new SimpleMeterRegistry());
  }

  private WorkerContainerFixture.WorkerContainerFixtureBuilder workerBuilder(
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
      UUID streamSessionId, LocalSegmentStore segmentStore, RemoteTranscodeExecutor executor) {}

  private PlaybackRig rig(PlaybackRigConfiguration configuration) {
    var session =
        StreamSession.builder()
            .sessionId(configuration.streamSessionId())
            .mediaFileId(UUID.randomUUID())
            .authority(StreamSessionFixture.playbackAuthorityFor(UUID.randomUUID()))
            .mediaProbe(StreamSessionFixture.defaultProbeBuilder().build())
            .transcodeDecision(transcodeDecision())
            .build();
    var registry = new FakeRuntimeStreamSessionRegistry();
    registry.save(session);
    var streamingService = new FakeStreamingService(registry);
    var properties =
        StreamingProperties.builder()
            .targetSegmentDuration(Duration.ofSeconds(6))
            .producerStallThreshold(Duration.ofSeconds(5))
            .build();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .segmentStore(configuration.segmentStore())
            .transcodeExecutor(configuration.executor())
            .properties(properties)
            .runtimeRegistry(registry)
            .pollInterval(Duration.ofMillis(50))
            .build();
    var authorizationService =
        new FakeAuthorizationService(() -> identity(configuration.streamSessionId()), "it-token");
    var controller =
        new StreamController(
            streamingService,
            new HlsPlaylistService(properties),
            rig.coordinator(),
            authorizationService);
    return new PlaybackRig(controller, session);
  }

  private TranscodeRequest transcodeRequest(UUID streamSessionId, Path mediaFile) {
    return TranscodeRequest.builder()
        .sessionId(streamSessionId)
        .sourcePath(mediaFile)
        .targetSegmentDuration(6)
        .mediaSegmentCount(CLIP_MEDIA_SEGMENTS)
        .framerate(OptionalDouble.of(23.976))
        .transcodeDecision(transcodeDecision())
        .width(1920)
        .height(1080)
        .bitrate(5_000_000)
        .variantLabel(StreamSession.defaultVariant())
        .build();
  }

  private TranscodeDecision transcodeDecision() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.FULL_TRANSCODE)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude())
        .needsKeyframeAlignment(true)
        .build();
  }

  private List<TranscodeDecision> supportedTranscodeDecisions() {
    return List.of(
        decisionBuilder()
            .transcodeMode(TranscodeMode.REMUX)
            .audioDecision(AudioDecision.copy("aac", 2, 128_000))
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
            .audioDecision(AudioDecision.copy("aac", 2, 128_000))
            .build(),
        decisionBuilder()
            .transcodeMode(TranscodeMode.FULL_TRANSCODE)
            .audioDecision(AudioDecision.stereoAac())
            .build());
  }

  private TranscodeDecision.TranscodeDecisionBuilder decisionBuilder() {
    return TranscodeDecision.builder()
        .videoCodecFamily("h264")
        .needsKeyframeAlignment(true)
        .subtitleDecision(SubtitleDecision.exclude());
  }

  private void assertCommandPreservesDecision(List<String> command, TranscodeDecision decision) {
    var expectedVideoCodec =
        switch (decision.transcodeMode()) {
          case REMUX, AUDIO_TRANSCODE -> "copy";
          case VIDEO_TRANSCODE, FULL_TRANSCODE -> "libx264";
        };
    assertThat(argument(command, "-c:v"))
        .as("video codec for %s", decision)
        .isEqualTo(expectedVideoCodec);
    assertThat(command)
        .as("source and absolute timeline for %s", decision)
        .containsSubsequence("-i", "/media/movie.mkv")
        .containsSubsequence("-map", "-0:s")
        .contains("-copyts", "-start_at_zero")
        .doesNotContain("-start_number");
    var seek = Double.parseDouble(argument(command, "-ss"));
    switch (decision.transcodeMode()) {
      case REMUX, AUDIO_TRANSCODE ->
          assertThat(seek).as("stream copy seek for %s", decision).isEqualTo(12);
      case VIDEO_TRANSCODE, FULL_TRANSCODE ->
          assertThat(seek)
              .as("encode seek at or before the first advertised boundary for %s", decision)
              .isLessThanOrEqualTo(12);
    }

    assertThat(command)
        .as("fragmented MP4 on standard output for %s", decision)
        .containsSubsequence("-f", "mp4")
        .containsSubsequence("-movflags", "cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont")
        .endsWith("pipe:1")
        .doesNotContain("-hls_time", "-hls_segment_type");

    if (expectedVideoCodec.equals("libx264")) {
      assertThat(command)
          .as("video encoding settings for %s", decision)
          .containsSubsequence("-vf", "scale=-2:720")
          .containsSubsequence("-b:v", "2500000")
          .containsSubsequence("-maxrate", "2500000")
          .containsSubsequence("-bufsize", "5000000")
          .containsSubsequence("-force_key_frames:0", "12,16,20");
    }

    switch (decision.audioDecision().mode()) {
      case COPY -> assertThat(argument(command, "-c:a")).isEqualTo("copy");
      case TRANSCODE ->
          assertThat(command)
              .as("stereo AAC audio for %s", decision)
              .containsSubsequence("-c:a", "aac")
              .containsSubsequence("-ac", "2")
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

  private static final class PublishingSegmentStore extends LocalSegmentStore {
    private final Map<String, CompletableFuture<Void>> publications = new ConcurrentHashMap<>();

    private PublishingSegmentStore(Path baseDir) {
      super(baseDir);
    }

    private CompletableFuture<Void> publication(String segmentName) {
      return publications.computeIfAbsent(segmentName, _ -> new CompletableFuture<>());
    }

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
      var prepared = super.prepareSegment(sessionId, segmentName, data);
      return new PreparedSegment() {
        @Override
        public SegmentPublication publish() {
          var outcome = prepared.publish();
          publication(segmentName).complete(null);
          return outcome;
        }

        @Override
        public void close() {
          prepared.close();
        }
      };
    }
  }

  private static final class FirstRequestSegmentStore extends LocalSegmentStore {
    private final CountDownLatch requestedWhileMissing = new CountDownLatch(1);

    private FirstRequestSegmentStore(Path baseDir) {
      super(baseDir);
    }

    @Override
    public boolean segmentExists(UUID sessionId, String segmentName) {
      var exists = super.segmentExists(sessionId, segmentName);
      if (!exists) {
        requestedWhileMissing.countDown();
      }

      return exists;
    }

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
      var prepared = super.prepareSegment(sessionId, segmentName, data);
      return new PreparedSegment() {
        @Override
        public SegmentPublication publish() {
          try {
            assertThat(requestedWhileMissing.await(5, TimeUnit.SECONDS))
                .as(
                    "Playback must observe the missing segment before the first upload is published")
                .isTrue();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while awaiting the first segment request", exception);
          }

          return prepared.publish();
        }

        @Override
        public void close() {
          prepared.close();
        }
      };
    }
  }
}
