package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.controllers.StreamController;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.SubtitleMode;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentProducingFfmpegProcessManager;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.ProducerLifecycleService;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegTranscodeEngine;
import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.transcode.tls.PemTlsIdentity;
import com.streamarr.transcode.worker.TranscodeWorker;
import com.streamarr.transcode.worker.TranscodeWorkerConfiguration;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Remote Playback Integration Tests")
class RemotePlaybackIT extends AbstractIntegrationTest {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should serve sequential segments produced by an outbound transcode worker")
  void shouldServeSequentialSegmentsProducedByOutboundTranscodeWorker() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segments =
        Map.of(
            "segment0.ts", "first remote segment".getBytes(),
            "segment1.ts", "second remote segment".getBytes());
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore);
        var worker = worker(mediaRoot, segments)) {
      server.start();
      worker.start("localhost", server.port());
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);

      executor.start(transcodeRequest(streamSessionId, mediaFile));
      assertThat(executor.isHealthy()).isTrue();
      assertThat(executor.isRunning(streamSessionId)).isTrue();
      assertThat(executor.isRunning(streamSessionId, StreamSession.defaultVariant())).isTrue();
      await()
          .atMost(2, TimeUnit.SECONDS)
          .until(() -> segmentStore.segmentExists(streamSessionId, "segment1.ts"));
      var streamController = rig(streamSessionId, segmentStore, executor).controller();
      var first = streamController.getSegment(streamSessionId, "segment0.ts");
      var second = streamController.getSegment(streamSessionId, "segment1.ts");

      assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
      assertThat(first.getHeaders().getContentType()).hasToString("video/mp2t");
      assertThat(first.getBody()).isEqualTo(segments.get("segment0.ts"));
      assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
      assertThat(second.getHeaders().getContentType()).hasToString("video/mp2t");
      assertThat(second.getBody()).isEqualTo(segments.get("segment1.ts"));
      executor.stop(streamSessionId);
      assertThat(executor.isRunning(streamSessionId)).isFalse();
    }
  }

  @Test
  @DisplayName("Should serve the initialization segment and media produced by an outbound worker")
  void shouldServeInitializationSegmentAndMediaProducedByOutboundWorker() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segments =
        Map.of(
            "init.mp4", "remote initialization".getBytes(),
            "segment0.m4s", "remote media fragment".getBytes());
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore);
        var worker = worker(mediaRoot, segments)) {
      server.start();
      worker.start("localhost", server.port());
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);

      executor.start(transcodeRequest(streamSessionId, mediaFile, ContainerFormat.FMP4));
      await()
          .atMost(2, TimeUnit.SECONDS)
          .until(() -> segmentStore.segmentExists(streamSessionId, "segment0.m4s"));
      var streamController =
          rig(streamSessionId, segmentStore, executor, ContainerFormat.FMP4).controller();
      var initialization = streamController.getInitSegment(streamSessionId);
      var media = streamController.getSegment(streamSessionId, "segment0.m4s");

      assertThat(initialization.getHeaders().getContentType()).hasToString("video/mp4");
      assertThat(initialization.getBody()).isEqualTo(segments.get("init.mp4"));
      assertThat(media.getHeaders().getContentType()).hasToString("video/mp4");
      assertThat(media.getBody()).isEqualTo(segments.get("segment0.m4s"));
    }
  }

  @Test
  @DisplayName("Should serve a segment requested before the worker's first upload arrives")
  void shouldServeSegmentRequestedBeforeWorkersFirstUploadArrives() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segments = Map.of("segment0.ts", "first remote segment".getBytes());
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore);
        var worker = worker(mediaRoot, segments)) {
      server.start();
      worker.start("localhost", server.port());
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var playback = rig(streamSessionId, segmentStore, executor);

      var handle = executor.start(transcodeRequest(streamSessionId, mediaFile));
      playback.session().setHandle(handle);
      var response = playback.controller().getSegment(streamSessionId, "segment0.ts");

      assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
      assertThat(response.getBody()).isEqualTo(segments.get("segment0.ts"));
    }
  }

  @Test
  @DisplayName("Should preserve every supported transcode decision across the worker protocol")
  void shouldPreserveEverySupportedTranscodeDecisionAcrossWorkerProtocol() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new RecordingFfmpegProcessManager();
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));

    try (var server = server(segmentStore);
        var worker = worker(mediaRoot, processManager)) {
      server.start();
      worker.start("localhost", server.port());
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);

      for (var decision : supportedTranscodeDecisions()) {
        var streamSessionId = UUID.randomUUID();
        var request =
            TranscodeRequest.builder()
                .sessionId(streamSessionId)
                .sourcePath(mediaFile)
                .targetSegmentDuration(6)
                .framerate(23.976)
                .transcodeDecision(decision)
                .width(1920)
                .height(1080)
                .bitrate(5_000_000)
                .variantLabel(StreamSession.defaultVariant())
                .build();

        executor.start(request);
        await()
            .atMost(2, TimeUnit.SECONDS)
            .until(() -> processManager.commandFor(streamSessionId).isPresent());
        assertCommandPreservesDecision(
            processManager.commandFor(streamSessionId).orElseThrow(), decision);
        executor.stop(streamSessionId);
      }
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
  @DisplayName("Should reject media outside the configured source namespace")
  void shouldRejectMediaOutsideConfiguredSourceNamespace() throws Exception {
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

  private WorkerSessionServer server(LocalSegmentStore segmentStore) throws URISyntaxException {
    var configuration =
        WorkerSessionServerConfiguration.builder()
            .port(0)
            .trustDomain("streamarr.test")
            .tlsIdentity(
                PemTlsIdentity.builder()
                    .certificate(resource("server-cert.pem"))
                    .privateKey(resource("server-key.fixture"))
                    .trustBundle(resource("ca-cert.pem"))
                    .build())
            .build();
    return new WorkerSessionServer(configuration, segmentStore);
  }

  private TranscodeWorker worker(Path mediaRoot, Map<String, byte[]> segments)
      throws URISyntaxException {
    return worker(mediaRoot, new FakeSegmentProducingFfmpegProcessManager(segments));
  }

  private TranscodeWorker worker(Path mediaRoot, FakeFfmpegProcessManager processManager)
      throws URISyntaxException {
    var configuration =
        TranscodeWorkerConfiguration.builder()
            .workerId(WORKER_ID)
            .bootId(UUID.randomUUID())
            .availableSlots(1)
            .tlsIdentity(
                PemTlsIdentity.builder()
                    .certificate(resource("worker-cert.pem"))
                    .privateKey(resource("worker-key.fixture"))
                    .trustBundle(resource("ca-cert.pem"))
                    .build())
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, mediaRoot))
            .segmentBasePath(tempDir.resolve("worker-segments"))
            .build();
    var engine =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder("ffmpeg"),
            processManager,
            new TranscodeCapabilityService(
                "ffmpeg",
                _ -> {
                  throw new IllegalStateException("Not used for remux");
                }));
    return new TranscodeWorker(configuration, engine);
  }

  private record FixedSessionStreamingService(StreamSession session) implements StreamingService {

    @Override
    public StreamSession createSession(CreateStreamSessionCommand command) {
      throw new UnsupportedOperationException("Sessions are prebuilt in remote playback tests");
    }

    @Override
    public Optional<StreamSession> accessSession(PlaybackRequest request) {
      return Optional.of(session);
    }

    @Override
    public void destroySession(UUID sessionId) {
      // Session lifecycle is owned by the test rig.
    }

    @Override
    public void destroySession(UUID sessionId, UUID profileId) {
      // Session lifecycle is owned by the test rig.
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return List.of(session);
    }

    @Override
    public int getActiveSessionCount() {
      return 1;
    }
  }

  private record BoundAuthorizationService(AuthenticatedIdentity identity)
      implements AuthorizationService {

    @Override
    public AuthenticatedIdentity currentIdentity() {
      return identity;
    }

    @Override
    public String currentTokenValue() {
      return "it-token";
    }

    @Override
    public Instant currentTokenExpiry() {
      return Instant.now().plusSeconds(3600);
    }

    @Override
    public UUID requireAccountId() {
      return identity.accountId();
    }

    @Override
    public UUID requireHousehold() {
      return identity.householdId();
    }

    @Override
    public UUID requireProfile() {
      return identity.profileId();
    }

    @Override
    public boolean isServerAdmin() {
      return false;
    }

    @Override
    public void requireServerAdmin() {
      throw new UnsupportedOperationException("Not an admin surface");
    }

    @Override
    public void requireHouseholdRole(HouseholdRole minimum) {
      // Playback tests carry a full playback identity.
    }

    @Override
    public boolean canViewActivityOf(UUID profileId) {
      return true;
    }
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
      assertThatThrownBy(() -> executor.start(request, new ExecutionTargetId("ghost-worker")))
          .isInstanceOf(TranscodeException.class)
          .hasMessageContaining("ghost-worker");
    }
  }

  private record PlaybackRig(StreamController controller, StreamSession session) {}

  private PlaybackRig rig(
      UUID streamSessionId, LocalSegmentStore segmentStore, RemoteTranscodeExecutor executor) {
    return rig(streamSessionId, segmentStore, executor, ContainerFormat.MPEGTS);
  }

  private PlaybackRig rig(
      UUID streamSessionId,
      LocalSegmentStore segmentStore,
      RemoteTranscodeExecutor executor,
      ContainerFormat containerFormat) {
    var session =
        StreamSession.builder()
            .sessionId(streamSessionId)
            .mediaFileId(UUID.randomUUID())
            .authority(StreamSessionFixture.playbackAuthorityFor(UUID.randomUUID()))
            .transcodeDecision(transcodeDecision(containerFormat))
            .build();
    var streamingService = new FixedSessionStreamingService(session);
    var registry = new FakeRuntimeStreamSessionRegistry();
    registry.save(session);
    var properties =
        StreamingProperties.builder()
            .targetSegmentDuration(Duration.ofSeconds(6))
            .producerStallThreshold(Duration.ofSeconds(5))
            .build();
    var lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(executor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(registry)
            .sessionMutex(new MutexFactory<>())
            .build();
    var coordinator =
        SegmentDeliveryCoordinator.builder()
            .runtimeRegistry(registry)
            .segmentStore(segmentStore)
            .transcodeExecutor(executor)
            .producerLifecycle(lifecycle)
            .properties(properties)
            .clock(Clock.systemUTC())
            .pollInterval(Duration.ofMillis(50))
            .build();
    var authorizationService = new BoundAuthorizationService(identity(streamSessionId));
    var controller =
        new StreamController(
            streamingService,
            new HlsPlaylistService(properties),
            coordinator,
            authorizationService);
    return new PlaybackRig(controller, session);
  }

  private TranscodeRequest transcodeRequest(UUID streamSessionId, Path mediaFile) {
    return transcodeRequest(streamSessionId, mediaFile, ContainerFormat.MPEGTS);
  }

  private TranscodeRequest transcodeRequest(
      UUID streamSessionId, Path mediaFile, ContainerFormat containerFormat) {
    return TranscodeRequest.builder()
        .sessionId(streamSessionId)
        .sourcePath(mediaFile)
        .targetSegmentDuration(6)
        .framerate(23.976)
        .transcodeDecision(transcodeDecision(containerFormat))
        .width(1920)
        .height(1080)
        .bitrate(5_000_000)
        .variantLabel(StreamSession.defaultVariant())
        .build();
  }

  private TranscodeDecision transcodeDecision(ContainerFormat containerFormat) {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.FULL_TRANSCODE)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude())
        .containerFormat(containerFormat)
        .needsKeyframeAlignment(true)
        .build();
  }

  private List<TranscodeDecision> supportedTranscodeDecisions() {
    return List.of(
        decisionBuilder()
            .transcodeMode(TranscodeMode.REMUX)
            .audioDecision(AudioDecision.copy("aac", 2, 128_000))
            .subtitleDecision(SubtitleDecision.exclude())
            .containerFormat(ContainerFormat.MPEGTS)
            .build(),
        decisionBuilder()
            .transcodeMode(TranscodeMode.AUDIO_TRANSCODE)
            .audioDecision(AudioDecision.stereoAac())
            .subtitleDecision(subtitle(SubtitleMode.BURN_IN))
            .containerFormat(ContainerFormat.FMP4)
            .build(),
        decisionBuilder()
            .transcodeMode(TranscodeMode.VIDEO_TRANSCODE)
            .audioDecision(AudioDecision.none())
            .subtitleDecision(subtitle(SubtitleMode.SIDECAR))
            .containerFormat(ContainerFormat.MPEGTS)
            .build(),
        decisionBuilder()
            .transcodeMode(TranscodeMode.FULL_TRANSCODE)
            .audioDecision(AudioDecision.copy("aac", 2, 128_000))
            .subtitleDecision(subtitle(SubtitleMode.HLS))
            .containerFormat(ContainerFormat.FMP4)
            .build(),
        decisionBuilder()
            .transcodeMode(TranscodeMode.FULL_TRANSCODE)
            .audioDecision(AudioDecision.stereoAac())
            .subtitleDecision(subtitle(SubtitleMode.EMBED))
            .containerFormat(ContainerFormat.MPEGTS)
            .build());
  }

  private TranscodeDecision.TranscodeDecisionBuilder decisionBuilder() {
    return TranscodeDecision.builder().videoCodecFamily("h264").needsKeyframeAlignment(true);
  }

  private SubtitleDecision subtitle(SubtitleMode mode) {
    return new SubtitleDecision(mode, Optional.of("srt"), OptionalInt.of(1), Optional.of("eng"));
  }

  private void assertCommandPreservesDecision(List<String> command, TranscodeDecision decision) {
    var expectedVideoCodec =
        switch (decision.transcodeMode()) {
          case REMUX, AUDIO_TRANSCODE -> "copy";
          case VIDEO_TRANSCODE, FULL_TRANSCODE -> "libx264";
        };
    assertThat(argument(command, "-c:v")).isEqualTo(expectedVideoCodec);

    switch (decision.audioDecision().mode()) {
      case COPY -> assertThat(argument(command, "-c:a")).isEqualTo("copy");
      case TRANSCODE ->
          assertThat(argument(command, "-c:a")).isEqualTo(decision.audioDecision().codec());
      case NONE -> assertThat(command).doesNotContain("-c:a");
    }

    if (decision.subtitleDecision().mode() == SubtitleMode.EXCLUDE) {
      assertThat(command).containsSubsequence("-map", "-0:s");
    } else {
      assertThat(command).doesNotContain("-0:s");
    }

    var expectedSegmentType =
        decision.containerFormat() == ContainerFormat.FMP4 ? "fmp4" : "mpegts";
    assertThat(command).containsSubsequence("-hls_segment_type", expectedSegmentType);
  }

  private String argument(List<String> command, String flag) {
    return command.get(command.indexOf(flag) + 1);
  }

  private AuthenticatedIdentity identity(UUID streamSessionId) {
    return AuthenticatedIdentity.builder()
        .accountId(UUID.randomUUID())
        .role(AccountRole.USER)
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.PLAYBACK)
        .householdId(UUID.randomUUID())
        .householdRole(HouseholdRole.MEMBER)
        .profileId(UUID.randomUUID())
        .streamSessionId(streamSessionId)
        .build();
  }

  private Path resource(String name) throws URISyntaxException {
    var url = Objects.requireNonNull(getClass().getResource("/tls/" + name));
    return Path.of(url.toURI());
  }

  private static final class RecordingFfmpegProcessManager extends FakeFfmpegProcessManager {

    private final Map<UUID, List<String>> commands = new ConcurrentHashMap<>();

    @Override
    public Process startProcess(
        UUID sessionId, String variantLabel, List<String> command, Path workingDirectory) {
      commands.put(sessionId, List.copyOf(command));
      return super.startProcess(sessionId, variantLabel, command, workingDirectory);
    }

    private Optional<List<String>> commandFor(UUID streamSessionId) {
      return Optional.ofNullable(commands.get(streamSessionId));
    }
  }
}
