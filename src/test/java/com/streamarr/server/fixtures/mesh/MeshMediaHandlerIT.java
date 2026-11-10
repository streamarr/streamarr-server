package com.streamarr.server.fixtures.mesh;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.streamCopyDecision;
import static com.streamarr.server.fixtures.WorkerProbeResultFixture.version4MediaBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.fixtures.WorkerProbeReporter;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import com.streamarr.server.services.streaming.remote.protocol.WorkerIdentityMetadata;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.sun.net.httpserver.HttpServer;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("IntegrationTest")
@DisplayName("Mesh Media Exchange Tests")
class MeshMediaHandlerIT {

  private static final String PLACEHOLDER_INITIALIZATION_SEGMENT =
      "published initialization from the worker ";
  private static final String PLACEHOLDER_MEDIA_SEGMENT = "published segment from the worker";

  @Test
  @DisplayName("Should confirm real media when the authorized worker returns the fixture probe")
  void shouldConfirmRealMediaWhenTheAuthorizedWorkerReturnsTheFixtureProbe() throws Exception {
    var media = version4MediaBuilder();
    media.setContainer(ProbeContainerInfo.newBuilder().setFormat("mov,mp4,m4a,3gp,3g2,mj2"));
    media.getStreamsBuilder(0).setCodec("h264").setWidth(320).setHeight(180);
    try (var rig = Rig.builder().media(media.build()).build()) {
      var response = rig.probe();

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo("MEDIA_PROBE_COMPLETED");
    }
  }

  @ParameterizedTest
  @MethodSource("unexpectedMedia")
  @DisplayName("Should reject fixture proof when the worker returns different media")
  void shouldRejectFixtureProofWhenTheWorkerReturnsDifferentMedia(ProbeMediaInfo media)
      throws Exception {
    try (var rig = Rig.builder().media(media).build()) {
      var response = rig.probe();

      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.body()).doesNotContain("MEDIA_PROBE_COMPLETED");
    }
  }

  private static Stream<ProbeMediaInfo> unexpectedMedia() {
    var video =
        ProbeStreamInfo.newBuilder()
            .setCodecType("video")
            .setCodec("h264")
            .setWidth(320)
            .setHeight(180)
            .build();
    var media =
        ProbeMediaInfo.newBuilder()
            .setContainer(ProbeContainerInfo.newBuilder().setFormat("mov,mp4"))
            .addStreams(video)
            .build();
    return Stream.of(
        media.toBuilder()
            .setContainer(ProbeContainerInfo.newBuilder().setFormat("mesh-fixture"))
            .build(),
        media.toBuilder().setStreams(0, video.toBuilder().setCodec("hevc")).build(),
        media.toBuilder().setStreams(0, video.toBuilder().setWidth(640)).build(),
        media.toBuilder().setStreams(0, video.toBuilder().setHeight(360)).build(),
        media.toBuilder().setStreams(0, video.toBuilder().setCodecType("audio")).build(),
        media.toBuilder().clearStreams().build());
  }

  @Test
  @DisplayName("Should return the published segment when the worker executes the media job")
  void shouldReturnThePublishedSegmentWhenTheWorkerExecutesTheMediaJob() throws Exception {
    try (var rig = Rig.builder().media(ProbeMediaInfo.getDefaultInstance()).build()) {
      var response = rig.request("/media/segment");

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body())
          .isEqualTo(PLACEHOLDER_INITIALIZATION_SEGMENT + PLACEHOLDER_MEDIA_SEGMENT);
      var job = rig.worker.started.get(5, TimeUnit.SECONDS);
      assertThat(rig.worker.stopped.get(5, TimeUnit.SECONDS)).isEqualTo(job.getJobAttemptId());
      assertThat(rig.segments.segmentExists(fromProto(job.getStreamSessionId()), "segment0.m4s"))
          .isFalse();
    }
  }

  @Test
  @DisplayName("Should advertise the fixture's media segment count when dispatching the media job")
  void shouldAdvertiseFixturesMediaSegmentCountWhenDispatchingTheMediaJob() throws Exception {
    try (var rig = Rig.builder().media(ProbeMediaInfo.getDefaultInstance()).build()) {
      rig.request("/media/segment");

      var execution = rig.worker.started.get(5, TimeUnit.SECONDS).getExecution();
      assertThat(execution.getTargetSegmentDurationSeconds()).isEqualTo(2);
      assertThat(execution.getMediaSegmentCount())
          .as(
              "the 10.005 s fixture clip spans five 2 s media segments, the last taking in its 5 ms")
          .isEqualTo(5);
      assertThat(execution.getSegmentStartTicksList()).containsExactly(0L, 2L, 4L, 6L, 8L);
      assertThat(execution.getSegmentStartTimeBase().getNumerator()).isEqualTo(1);
      assertThat(execution.getSegmentStartTimeBase().getDenominator()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Standalone Worker Image")
  class StandaloneWorkerImage {

    private static final int STANDALONE_WORKER_SLOTS = 1;

    @TempDir static Path standaloneWorkerDirectory;

    private static Rig standaloneWorkerRig;
    private static WorkerContainerFixture standaloneWorker;

    @BeforeAll
    static void startStandaloneWorker() throws Exception {
      var mediaRoot = Files.createDirectory(standaloneWorkerDirectory.resolve("media"));
      var clip = MeshMediaHandlerIT.class.getResource("/BigBuckBunny_320x180_10s.mp4");
      assertThat(clip).isNotNull();
      Files.copy(Path.of(clip.toURI()), mediaRoot.resolve("mesh-fixture.mkv"));
      standaloneWorkerRig = Rig.builder().withoutWorker(true).build();
      standaloneWorker =
          WorkerContainerFixture.builder()
              .workerSessions(standaloneWorkerRig.server)
              .sourceNamespaceId(MeshValidationServer.SOURCE_ID)
              .sourceRoot(mediaRoot)
              .availableSlots(STANDALONE_WORKER_SLOTS)
              .build();
      standaloneWorker.start();
      await()
          .atMost(10, TimeUnit.SECONDS)
          .until(
              () ->
                  !standaloneWorkerRig
                      .server
                      .eligibleWorkers(MeshValidationServer.SOURCE_ID, streamCopyDecision())
                      .isEmpty());
    }

    @AfterAll
    static void stopStandaloneWorker() throws Exception {
      try {
        if (standaloneWorker != null) {
          standaloneWorker.close();
        }
      } finally {
        if (standaloneWorkerRig != null) {
          standaloneWorkerRig.close();
        }
      }
    }

    // The server frees a stopped job's slot and a finished probe's unit before the handler answers.
    @BeforeEach
    void requireIdleStandaloneWorker() {
      assertThat(isStandaloneWorkerIdle())
          .as("the previous test left the shared worker's slot and probe unit free")
          .isTrue();
    }

    @Test
    @DisplayName("Should confirm real media when the standalone worker image probes the fixture")
    void shouldConfirmRealMediaWhenTheStandaloneWorkerImageProbesTheFixture() throws Exception {
      var response = standaloneWorkerRig.probe();

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo("MEDIA_PROBE_COMPLETED");
    }

    @Test
    @DisplayName(
        "Should return decodable fMP4 when the standalone worker image transcodes the fixture")
    void shouldReturnDecodableFmp4WhenTheStandaloneWorkerImageTranscodesTheFixture(
        @TempDir Path tempDir) throws Exception {
      var response = standaloneWorkerRig.requestMedia("/media/segment");

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(Fmp4Fixture.firstBoxType(response.body())).isEqualTo("ftyp");
      var media = Files.write(tempDir.resolve("mesh-media.mp4"), response.body());
      assertThat(standaloneWorker.decodedVideoFrameCount(media)).isPositive();
    }

    private static boolean isStandaloneWorkerIdle() {
      var server = standaloneWorkerRig.server;
      return server.availableSlots(MeshValidationServer.SOURCE_ID) == STANDALONE_WORKER_SLOTS
          && server
                  .probeCapacity(MeshValidationServer.SOURCE_ID, ProbeVersion.CURRENT)
                  .runningProbes()
              == 0;
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/media/probe", "/media/segment"})
  @DisplayName("Should reject media proof when no worker is connected")
  void shouldRejectMediaProofWhenNoWorkerIsConnected(String path) throws Exception {
    try (var rig = Rig.builder().withoutWorker(true).build()) {
      var response = rig.request(path);

      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.body()).isEmpty();
    }
  }

  @DisplayName("Media Exchange Fixture")
  private static final class Rig implements AutoCloseable {

    private final WorkerSessionServer server;
    private final HttpServer http;
    private final ManagedChannel channel;
    private final HttpClient client = HttpClient.newHttpClient();
    private final MeshSegmentStore segments = new MeshSegmentStore();
    private final ProbeWorker worker;

    @Builder
    private Rig(ProbeMediaInfo media, boolean withoutWorker) throws Exception {
      server =
          WorkerSessionServer.builder()
              .eventPublisher(new CapturingEventPublisher())
              .configuration(WorkerSessionServerConfiguration.builder().port(0).build())
              .segmentStore(segments)
              .meterRegistry(new SimpleMeterRegistry())
              .build();
      server.start();
      http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      http.createContext("/media/", new MeshMediaHandler(server, segments));
      http.start();
      var workerId = UUID.randomUUID();
      var headers = new Metadata();
      headers.put(WorkerIdentityMetadata.WORKER_ID, workerId.toString());
      channel =
          NettyChannelBuilder.forAddress("127.0.0.1", server.port())
              .usePlaintext()
              .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
              .build();
      var identity =
          WorkerIdentity.newBuilder()
              .setWorkerId(toProto(workerId))
              .setBootId(toProto(UUID.randomUUID()))
              .build();
      worker =
          new ProbeWorker(
              media, segments, WorkerProbeReporter.builder().channel(channel).worker(identity));
      if (withoutWorker) {
        return;
      }

      worker.requests = TranscodeWorkerServiceGrpc.newStub(channel).establishWorkerSession(worker);
      worker.requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setRegistration(
                  WorkerRegistration.newBuilder()
                      .setWorker(identity)
                      .setAvailableSlots(1)
                      .setBackgroundProbeCapacity(1)
                      .setKeyframePacketScanCapacity(1)
                      .setCapabilities(
                          WorkerCapabilities.newBuilder()
                              .addSourceNamespaceIds(toProto(MeshValidationServer.SOURCE_ID))
                              .addProbeVersions(ProbeVersion.CURRENT)
                              .addVideoEncoders(
                                  softwareEncoder(
                                      VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264"))))
              .build());
      worker.accepted.get(5, TimeUnit.SECONDS);
    }

    private HttpResponse<String> probe() throws Exception {
      return request("/media/probe");
    }

    private HttpResponse<String> request(String path) throws Exception {
      return client.send(httpRequest(path), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> requestMedia(String path) throws Exception {
      return client.send(httpRequest(path), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest httpRequest(String path) {
      return HttpRequest.newBuilder(
              URI.create("http://127.0.0.1:" + http.getAddress().getPort() + path))
          .timeout(Duration.ofSeconds(15))
          .build();
    }

    @Override
    public void close() throws Exception {
      client.close();
      http.stop(0);
      channel.shutdownNow();
      assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      server.close();
    }
  }

  @DisplayName("Scripted Media Worker")
  private static final class ProbeWorker implements StreamObserver<EstablishWorkerSessionResponse> {

    private final ProbeMediaInfo media;
    private final MeshSegmentStore segments;
    // Lacks the worker session until the server accepts it.
    private final WorkerProbeReporter.WorkerProbeReporterBuilder reporter;
    private final CompletableFuture<Void> accepted = new CompletableFuture<>();
    private final CompletableFuture<VariantJob> started = new CompletableFuture<>();
    private final CompletableFuture<Uuid> stopped = new CompletableFuture<>();
    private StreamObserver<EstablishWorkerSessionRequest> requests;

    private ProbeWorker(
        ProbeMediaInfo media,
        MeshSegmentStore segments,
        WorkerProbeReporter.WorkerProbeReporterBuilder reporter) {
      this.media = media;
      this.segments = segments;
      this.reporter = reporter;
    }

    @Override
    public void onNext(EstablishWorkerSessionResponse response) {
      if (response.hasSessionAccepted()) {
        reporter.workerSessionId(response.getSessionAccepted().getWorkerSessionId());
        accepted.complete(null);
        return;
      }

      if (response.hasStartProbe()) {
        var request = response.getStartProbe().getRequest();
        reporter
            .build()
            .report(
                ProbeAttemptResult.newBuilder()
                    .setProbeAttemptId(request.getProbeAttemptId())
                    .setProbeVersion(request.getProbeVersion())
                    .setMedia(media)
                    .build());
        return;
      }

      if (response.hasStartVariant()) {
        var job = response.getStartVariant().getJob();
        started.complete(job);
        var streamSessionId = fromProto(job.getStreamSessionId());
        segments.storeSegment(
            streamSessionId,
            "init.mp4",
            PLACEHOLDER_INITIALIZATION_SEGMENT.getBytes(StandardCharsets.UTF_8));
        segments.storeSegment(
            streamSessionId,
            "segment0.m4s",
            PLACEHOLDER_MEDIA_SEGMENT.getBytes(StandardCharsets.UTF_8));
        return;
      }

      if (response.hasStopVariant()) {
        stopped.complete(response.getStopVariant().getJobAttemptId());
      }
    }

    @Override
    public void onError(Throwable failure) {
      accepted.completeExceptionally(failure);
    }

    @Override
    public void onCompleted() {
      accepted.completeExceptionally(new IllegalStateException("Worker session ended"));
    }
  }
}
