package com.streamarr.server.fixtures.mesh;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("IntegrationTest")
@DisplayName("Mesh Media Exchange Tests")
class MeshMediaHandlerIT {

  private static final String SEGMENT = "published segment from the worker";

  @Test
  @DisplayName("Should confirm real media when the authorized worker returns the fixture probe")
  void shouldConfirmRealMediaWhenTheAuthorizedWorkerReturnsTheFixtureProbe() throws Exception {
    var media =
        ProbeMediaInfo.newBuilder()
            .setContainer(ProbeContainerInfo.newBuilder().setFormat("mov,mp4,m4a,3gp,3g2,mj2"))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setCodecType("video")
                    .setCodec("h264")
                    .setWidth(320)
                    .setHeight(180))
            .build();
    try (var rig = Rig.builder().media(media).build()) {
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
      assertThat(response.body()).isEqualTo(SEGMENT);
      var job = rig.worker.started.get(5, TimeUnit.SECONDS);
      assertThat(rig.worker.stopped.get(5, TimeUnit.SECONDS)).isEqualTo(job.getJobAttemptId());
      assertThat(rig.segments.segmentExists(fromProto(job.getStreamSessionId()), "segment0.ts"))
          .isFalse();
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
          new WorkerSessionServer(
              WorkerSessionServerConfiguration.builder().port(0).build(),
              segments,
              new SimpleMeterRegistry());
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
      worker = new ProbeWorker(media, segments);
      if (withoutWorker) {
        return;
      }

      worker.requests = TranscodeWorkerServiceGrpc.newStub(channel).establishWorkerSession(worker);
      worker.requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setRegistration(
                  WorkerRegistration.newBuilder()
                      .setWorker(
                          WorkerIdentity.newBuilder()
                              .setWorkerId(toProto(workerId))
                              .setBootId(toProto(UUID.randomUUID())))
                      .setAvailableSlots(1)
                      .setCapabilities(
                          WorkerCapabilities.newBuilder()
                              .addSourceNamespaceIds(toProto(MeshValidationServer.SOURCE_ID))
                              .addProbeVersions(1)))
              .build());
      worker.accepted.get(5, TimeUnit.SECONDS);
    }

    private HttpResponse<String> probe() throws Exception {
      return request("/media/probe");
    }

    private HttpResponse<String> request(String path) throws Exception {
      return client.send(
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + http.getAddress().getPort() + path))
              .timeout(Duration.ofSeconds(15))
              .build(),
          HttpResponse.BodyHandlers.ofString());
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
    private final CompletableFuture<Void> accepted = new CompletableFuture<>();
    private final CompletableFuture<VariantJob> started = new CompletableFuture<>();
    private final CompletableFuture<Uuid> stopped = new CompletableFuture<>();
    private StreamObserver<EstablishWorkerSessionRequest> requests;

    private ProbeWorker(ProbeMediaInfo media, MeshSegmentStore segments) {
      this.media = media;
      this.segments = segments;
    }

    @Override
    public void onNext(EstablishWorkerSessionResponse response) {
      if (response.hasSessionAccepted()) {
        accepted.complete(null);
        return;
      }

      if (response.hasStartProbe()) {
        var request = response.getStartProbe().getRequest();
        requests.onNext(
            EstablishWorkerSessionRequest.newBuilder()
                .setProbeResult(
                    ProbeAttemptResult.newBuilder()
                        .setProbeAttemptId(request.getProbeAttemptId())
                        .setProbeVersion(request.getProbeVersion())
                        .setMedia(media))
                .build());
        return;
      }

      if (response.hasStartVariant()) {
        var job = response.getStartVariant().getJob();
        started.complete(job);
        segments.storeSegment(
            fromProto(job.getStreamSessionId()),
            "segment0.ts",
            SEGMENT.getBytes(StandardCharsets.UTF_8));
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
