package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.controllers.StreamController;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeSegmentProducingFfmpegProcessManager;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.streaming.HlsPlaylistService;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
  @DisplayName("Should serve a segment produced by an outbound transcode worker")
  void shouldServeSegmentProducedByOutboundTranscodeWorker() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentData = "remote playable segment".getBytes();
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore);
        var worker = worker(mediaRoot, segmentData)) {
      server.start();
      worker.start("localhost", server.port());
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);

      executor.start(transcodeRequest(streamSessionId, mediaFile));
      assertThat(executor.isHealthy()).isTrue();
      assertThat(executor.isRunning(streamSessionId)).isTrue();
      assertThat(executor.isRunning(streamSessionId, StreamSession.defaultVariant())).isTrue();
      var response =
          controller(streamSessionId, segmentStore).getSegment(streamSessionId, "segment0.ts");

      assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
      assertThat(response.getHeaders().getContentType().toString()).isEqualTo("video/mp2t");
      assertThat(response.getBody()).isEqualTo(segmentData);
      executor.stop(streamSessionId);
      assertThat(executor.isRunning(streamSessionId)).isFalse();
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

      assertThat(executor.isHealthy()).isFalse();
      assertThatThrownBy(() -> executor.start(transcodeRequest(UUID.randomUUID(), mediaFile)))
          .isInstanceOf(TranscodeException.class)
          .hasMessage("No connected transcode worker can run this rendition");
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

      assertThatThrownBy(() -> executor.start(transcodeRequest(UUID.randomUUID(), outsideFile)))
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

  private TranscodeWorker worker(Path mediaRoot, byte[] segmentData) throws URISyntaxException {
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
            new FakeSegmentProducingFfmpegProcessManager("segment0.ts", segmentData),
            new TranscodeCapabilityService(
                "ffmpeg",
                _ -> {
                  throw new IllegalStateException("Not used for remux");
                }));
    return new TranscodeWorker(configuration, engine);
  }

  private StreamController controller(UUID streamSessionId, LocalSegmentStore segmentStore) {
    var streamingService = mock(StreamingService.class);
    var session =
        StreamSession.builder()
            .sessionId(streamSessionId)
            .transcodeDecision(transcodeDecision())
            .build();
    when(streamingService.accessSession(any())).thenReturn(Optional.of(session));
    var authorizationService = mock(AuthorizationService.class);
    when(authorizationService.currentIdentity()).thenReturn(identity(streamSessionId));
    return new StreamController(
        streamingService, mock(HlsPlaylistService.class), segmentStore, authorizationService);
  }

  private TranscodeRequest transcodeRequest(UUID streamSessionId, Path mediaFile) {
    return TranscodeRequest.builder()
        .sessionId(streamSessionId)
        .sourcePath(mediaFile)
        .segmentDuration(6)
        .framerate(23.976)
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
        .containerFormat(ContainerFormat.MPEGTS)
        .needsKeyframeAlignment(true)
        .build();
  }

  private AuthenticatedIdentity identity(UUID streamSessionId) {
    return AuthenticatedIdentity.builder()
        .accountId(UUID.randomUUID())
        .role(AccountRole.USER)
        .sessionId(UUID.randomUUID())
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
}
