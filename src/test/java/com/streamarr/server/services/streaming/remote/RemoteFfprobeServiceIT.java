package com.streamarr.server.services.streaming.remote;

import static com.google.protobuf.Duration.newBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.tlsIdentity;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("IntegrationTest")
@DisplayName("Remote Ffprobe Service Integration Tests")
class RemoteFfprobeServiceIT {

  @TempDir Path directory;

  @Test
  @DisplayName("Should fail for persisted retry when the worker disconnects during probing")
  void shouldFailForPersistedRetryWhenTheWorkerDisconnectsDuringProbing() throws Exception {
    try (var server = server();
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      server.start();
      try (var worker = new ProbeWorker(server.port(), 1)) {
        var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, directory);
        var request = request().build();
        var result = calls.submit(() -> service.probe(request));
        assertThat(worker.nextResponse().hasStartProbe()).isTrue();

        worker.disconnect();

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(ProbeExecutionException.class);
      }
    }
  }

  @Test
  @DisplayName("Should fail for persisted retry when no worker is available")
  void shouldFailForPersistedRetryWhenNoWorkerIsAvailable() throws Exception {
    try (var server = server()) {
      server.start();
      var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, directory);
      var request = request().build();

      assertThatThrownBy(() -> service.probe(request)).isInstanceOf(ProbeExecutionException.class);
    }
  }

  @Test
  @DisplayName("Should cancel the remote attempt when its waiting claim is interrupted")
  void shouldCancelTheRemoteAttemptWhenItsWaitingClaimIsInterrupted() throws Exception {
    try (var server = server();
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      server.start();
      try (var worker = new ProbeWorker(server.port(), 1)) {
        var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, directory);
        var request = request().build();
        var execution = new AtomicReference<Thread>();
        var interrupted = new AtomicBoolean();
        var result =
            calls.submit(
                () -> {
                  execution.set(Thread.currentThread());
                  try {
                    return service.probe(request);
                  } finally {
                    interrupted.set(Thread.currentThread().isInterrupted());
                  }
                });
        assertThat(worker.nextResponse().hasStartProbe()).isTrue();

        execution.get().interrupt();

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(ProbeExecutionException.class);
        assertThat(interrupted).isTrue();
        var cancellation = worker.nextResponse();
        assertThat(cancellation.hasCancelProbe()).isTrue();
        assertThat(cancellation.getCancelProbe().getProbeAttemptId())
            .isEqualTo(toProto(request.attemptId()));
      }
    }
  }

  @Test
  @DisplayName("Should preserve the attempt and version when a worker returns a complete probe")
  void shouldPreserveTheAttemptAndVersionWhenAWorkerReturnsACompleteProbe() throws Exception {
    try (var server = server();
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      server.start();
      try (var worker = new ProbeWorker(server.port(), 7)) {
        var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, directory);
        var request = request().probeVersion(7).build();

        var result = calls.submit(() -> service.probe(request));

        var dispatched = worker.nextResponse().getStartProbe().getRequest();
        assertThat(dispatched.getProbeAttemptId()).isEqualTo(toProto(request.attemptId()));
        assertThat(dispatched.getProbeVersion()).isEqualTo(7);
        assertThat(dispatched.getSource().getSourceNamespaceId())
            .isEqualTo(toProto(SOURCE_NAMESPACE_ID));
        assertThat(dispatched.getSource().getRelativeKey()).isEqualTo("movie.mkv");
        worker.reply(
            ProbeAttemptResult.newBuilder()
                .setProbeAttemptId(dispatched.getProbeAttemptId())
                .setProbeVersion(dispatched.getProbeVersion())
                .setMedia(
                    ProbeMediaInfo.newBuilder()
                        .addStreams(
                            ProbeStreamInfo.newBuilder().setCodecType("video").setWidth(1920)))
                .build());

        assertThat(result.get(5, TimeUnit.SECONDS))
            .isEqualTo(
                new ProbeOutcome.Success(
                    ProbeContainer.builder().build(),
                    List.of(
                        StreamInfo.builder()
                            .codecType("video")
                            .width(OptionalInt.of(1920))
                            .build())));
      }
    }
  }

  @Test
  @DisplayName("Should summarize first tracks when later worker streams have default flags")
  void shouldSummarizeFirstTracksWhenLaterWorkerStreamsHaveDefaultFlags() throws Exception {
    var media =
        ProbeMediaInfo.newBuilder()
            .setContainer(
                ProbeContainerInfo.newBuilder()
                    .setFormat("matroska")
                    .setDuration(newBuilder().setSeconds(90).setNanos(123))
                    .setBitrateBitsPerSecond(6_000_000))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(0)
                    .setCodecType("video")
                    .setCodec("h264")
                    .setWidth(1920)
                    .setHeight(1080)
                    .setFramerate(24))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(1)
                    .setCodecType("audio")
                    .setCodec("ac3")
                    .setChannels(6)
                    .setBitrateBitsPerSecond(384_000))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(2)
                    .setCodecType("video")
                    .setCodec("hevc")
                    .setWidth(3840)
                    .setHeight(2160)
                    .setFramerate(60)
                    .setIsDefault(true))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(3)
                    .setCodecType("audio")
                    .setCodec("aac")
                    .setChannels(2)
                    .setIsDefault(true))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(4)
                    .setCodecType("subtitle")
                    .setCodec("subrip")
                    .setLanguage("eng")
                    .setIsForced(true))
            .addStreams(ProbeStreamInfo.newBuilder().setIndex(5).setCodecType("data"))
            .addStreams(ProbeStreamInfo.newBuilder().setIndex(6).setCodecType("attachment"))
            .build();

    var complete = remoteOutcome(media);
    var probe = complete.mediaProbe();

    assertThat(probe.duration()).isEqualTo(Duration.ofSeconds(90, 123));
    assertThat(probe.bitrate()).isEqualTo(6_000_000);
    assertThat(probe.containerFormat()).contains("matroska");
    assertThat(probe.videoCodec()).isEqualTo("h264");
    assertThat(probe.width()).isEqualTo(1920);
    assertThat(probe.height()).isEqualTo(1080);
    assertThat(probe.framerate()).isEqualTo(24);
    assertThat(probe.audioCodec()).isEqualTo("ac3");
    assertThat(probe.audioChannels()).hasValue(6);
    assertThat(probe.audioBitrate()).hasValue(384_000);
    assertThat(probe.streams()).isEqualTo(complete.streams());
    assertThat(probe.streams())
        .extracting(StreamInfo::codecType)
        .containsExactly("video", "audio", "video", "audio", "subtitle", "data", "attachment");
    assertThat(probe.audioStreams()).extracting(StreamInfo::index).containsExactly(1, 3);
    assertThat(probe.subtitleStreams()).extracting(StreamInfo::index).containsExactly(4);
    assertThat(probe.subtitleStreams().getFirst().language()).contains("eng");
    assertThat(probe.subtitleStreams().getFirst().isForced()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should preserve absent summary fields when worker metadata omits optional values")
  void shouldPreserveAbsentSummaryFieldsWhenWorkerMetadataOmitsOptionalValues(boolean hasAudio)
      throws Exception {
    var media =
        ProbeMediaInfo.newBuilder().addStreams(ProbeStreamInfo.newBuilder().setCodecType("video"));
    if (hasAudio) {
      media.addStreams(ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("audio"));
    }

    var probe = remoteOutcome(media.build()).mediaProbe();

    assertThat(probe.duration()).isEqualTo(Duration.ZERO);
    assertThat(probe.bitrate()).isZero();
    assertThat(probe.containerFormat()).isEmpty();
    assertThat(probe.videoCodec()).isNull();
    assertThat(probe.width()).isZero();
    assertThat(probe.height()).isZero();
    assertThat(probe.framerate()).isZero();
    assertThat(probe.audioCodec()).isNull();
    assertThat(probe.audioChannels()).isEmpty();
    assertThat(probe.audioBitrate()).isEmpty();
    assertThat(probe.audioStreams()).hasSize(hasAudio ? 1 : 0);
    assertThat(probe.subtitleStreams()).isEmpty();
    assertThat(probe.streams().getFirst().language()).isEmpty();
    assertThat(probe.streams().getFirst().isDefault()).isFalse();
    assertThat(probe.streams().getFirst().isForced()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "PROBE_FAILURE_INVALID_MEDIA,INVALID_MEDIA",
    "PROBE_FAILURE_NO_VIDEO_STREAM,NO_VIDEO_STREAM"
  })
  @DisplayName("Should return a terminal media outcome when the worker rejects the media")
  void shouldReturnATerminalMediaOutcomeWhenTheWorkerRejectsTheMedia(
      ProbeFailure failure, ProbeError expected) throws Exception {
    var outcome = remoteReply(ProbeAttemptResult.newBuilder().setFailure(failure));

    assertThat(outcome).isEqualTo(new ProbeOutcome.Failure(expected));
  }

  private ProbeOutcome.Success remoteOutcome(ProbeMediaInfo media) throws Exception {
    var outcome = remoteReply(ProbeAttemptResult.newBuilder().setMedia(media));
    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    return (ProbeOutcome.Success) outcome;
  }

  private ProbeOutcome remoteReply(ProbeAttemptResult.Builder reply) throws Exception {
    try (var server = server();
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      server.start();
      try (var worker = new ProbeWorker(server.port(), 1)) {
        var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, directory);
        var request = request().build();
        var result = calls.submit(() -> service.probe(request));
        var dispatched = worker.nextResponse().getStartProbe().getRequest();
        worker.reply(
            reply
                .setProbeAttemptId(dispatched.getProbeAttemptId())
                .setProbeVersion(dispatched.getProbeVersion())
                .build());
        return result.get(5, TimeUnit.SECONDS);
      }
    }
  }

  private WorkerSessionServer server() throws Exception {
    return new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
  }

  private ProbeExecutionRequest.ProbeExecutionRequestBuilder request() {
    return ProbeExecutionRequest.builder()
        .sourcePath(directory.resolve("movie.mkv"))
        .attemptId(UUID.randomUUID())
        .probeVersion(1);
  }

  private static class ProbeWorker implements AutoCloseable {

    private final ManagedChannel channel;
    private final StreamObserver<EstablishWorkerSessionRequest> requests;
    private final BlockingQueue<EstablishWorkerSessionResponse> responses =
        new LinkedBlockingQueue<>();

    ProbeWorker(int port, int version) throws Exception {
      var identity = tlsIdentity("worker-cert.pem", "worker-key.fixture");
      channel =
          NettyChannelBuilder.forAddress("localhost", port)
              .sslContext(
                  GrpcSslContexts.forClient()
                      .keyManager(identity.certificate().toFile(), identity.privateKey().toFile())
                      .trustManager(identity.trustBundle().toFile())
                      .build())
              .build();
      requests =
          TranscodeWorkerServiceGrpc.newStub(channel)
              .establishWorkerSession(
                  new StreamObserver<>() {
                    @Override
                    public void onNext(EstablishWorkerSessionResponse value) {
                      responses.add(value);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                      // Server-side disconnect handling is observed through the pending probe call.
                    }

                    @Override
                    public void onCompleted() {
                      // Commands are asserted before this test peer closes its stream.
                    }
                  });
      requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setRegistration(
                  WorkerRegistration.newBuilder()
                      .setAvailableSlots(1)
                      .setWorker(
                          WorkerIdentity.newBuilder()
                              .setWorkerId(toProto(WORKER_ID))
                              .setBootId(toProto(UUID.randomUUID())))
                      .setCapabilities(
                          WorkerCapabilities.newBuilder()
                              .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                              .addProbeVersions(version)))
              .build());
      assertThat(nextResponse().hasSessionAccepted()).isTrue();
    }

    EstablishWorkerSessionResponse nextResponse() throws InterruptedException {
      var response = responses.poll(5, TimeUnit.SECONDS);
      assertThat(response).isNotNull();
      return response;
    }

    void reply(ProbeAttemptResult result) {
      requests.onNext(EstablishWorkerSessionRequest.newBuilder().setProbeResult(result).build());
    }

    void disconnect() {
      channel.shutdownNow();
    }

    @Override
    public void close() throws InterruptedException {
      requests.onCompleted();
      channel.shutdownNow();
      assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
