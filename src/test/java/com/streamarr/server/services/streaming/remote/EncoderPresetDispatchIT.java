package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.fixtures.StreamSessionFixture.abrSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.jooq.generated.tables.ServerSettings.SERVER_SETTINGS;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.EncoderPresetChoice;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.streaming.ProducerLifecycleService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.transcode.v1.EncoderPreset;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Drives variant jobs through a real worker session to a loopback worker while the encoder preset
 * setting lives in PostgreSQL.
 */
@Tag("IntegrationTest")
@DisplayName("Encoder Preset Dispatch Integration Tests")
class EncoderPresetDispatchIT extends AbstractIntegrationTest {

  private static final Path SOURCE_ROOT = Path.of("/media");

  @Autowired private ServerSettingsService serverSettingsService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity serverAdmin;
  private WorkerSessionServer server;
  private ManagedChannel channel;
  private LoopbackWorker worker;
  private FakeRuntimeStreamSessionRegistry registry;
  private ProducerLifecycleService lifecycle;

  @BeforeEach
  void setUp() throws Exception {
    serverAdmin = authTestSupport.createAdminIdentity();
    var segmentStore = new FakeSegmentStore();
    server =
        new WorkerSessionServer(
            serverConfigurationBuilder().build(), segmentStore, new SimpleMeterRegistry());
    server.start();
    channel = plaintextChannelBuilder(server.port(), WORKER_ID).build();
    worker = LoopbackWorker.register(channel);
    assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
    registry = new FakeRuntimeStreamSessionRegistry();
    lifecycle =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(
                new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, SOURCE_ROOT))
            .segmentStore(segmentStore)
            .properties(
                StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build())
            .runtimeRegistry(registry)
            .serverSettings(serverSettingsService)
            .build()
            .lifecycle();
  }

  @AfterEach
  void tearDown() throws Exception {
    worker.close();
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    server.close();
    dsl.update(SERVER_SETTINGS)
        .set(SERVER_SETTINGS.ENCODER_PRESET, EncoderPresetChoice.AUTOMATIC.name())
        .setNull(SERVER_SETTINGS.LAST_MODIFIED_BY)
        .execute();
    authTestSupport.deleteIdentity(serverAdmin);
  }

  @Test
  @DisplayName(
      "Should keep the first preset for a replacement attempt and send the new preset to a later variant when the setting changes")
  void shouldKeepFirstPresetForReplacementAndSendNewPresetToLaterVariantWhenSettingChanges()
      throws Exception {
    var running = startedSession();
    var firstJobs = worker.nextJobs(running.getVariants().size());
    assertThat(firstJobs)
        .extracting(job -> job.getVariant().getEncoderPreset())
        .containsOnly(EncoderPreset.ENCODER_PRESET_FASTER);

    serverSettingsService.changeEncoderPreset(authTestSupport.identityOf(serverAdmin), "MEDIUM");
    var failed = jobFor(firstJobs, "720p");
    worker.reportFailed(failed);
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> !server.isRunning(running.getSessionId(), "720p"));
    lifecycle.recover(running.getSessionId(), "720p", "720p/segment2.m4s");

    var replacement = worker.nextJobs(1).getFirst();
    assertThat(replacement.getVariant().getVariantLabel()).isEqualTo("720p");
    assertThat(replacement.getJobAttemptId()).isNotEqualTo(failed.getJobAttemptId());
    assertThat(replacement.getVariant().getEncoderPreset())
        .isEqualTo(EncoderPreset.ENCODER_PRESET_FASTER);

    var later = startedSession();
    assertThat(worker.nextJobs(later.getVariants().size()))
        .extracting(job -> job.getVariant().getEncoderPreset())
        .containsOnly(EncoderPreset.ENCODER_PRESET_MEDIUM);
  }

  private StreamSession startedSession() {
    var session =
        abrSessionBuilder()
            .sessionId(UUID.randomUUID())
            .sourcePath(SOURCE_ROOT.resolve("movie.mkv"))
            .transcodeDecision(fullTranscodeDecision("h264"))
            .build();
    registry.save(session);
    lifecycle.startAll(session, 0);
    return session;
  }

  private static VariantJob jobFor(List<VariantJob> jobs, String variantLabel) {
    return jobs.stream()
        .filter(job -> job.getVariant().getVariantLabel().equals(variantLabel))
        .findFirst()
        .orElseThrow();
  }

  private record LoopbackWorker(
      StreamObserver<EstablishWorkerSessionRequest> requests,
      BlockingQueue<EstablishWorkerSessionResponse> responses)
      implements AutoCloseable {

    static LoopbackWorker register(ManagedChannel channel) {
      var responses = new LinkedBlockingQueue<EstablishWorkerSessionResponse>();
      var requests =
          TranscodeWorkerServiceGrpc.newStub(channel)
              .establishWorkerSession(new QueueingObserver(responses));
      requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setRegistration(
                  WorkerRegistration.newBuilder()
                      .setWorker(
                          WorkerIdentity.newBuilder()
                              .setWorkerId(toProto(WORKER_ID))
                              .setBootId(toProto(UUID.randomUUID())))
                      .setCapabilities(
                          WorkerCapabilities.newBuilder()
                              .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                              .addVideoEncoders(
                                  softwareEncoder(
                                      VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")))
                      .setAvailableSlots(8))
              .build());
      return new LoopbackWorker(requests, responses);
    }

    EstablishWorkerSessionResponse nextResponse() throws InterruptedException {
      var response = responses.poll(5, TimeUnit.SECONDS);
      assertThat(response).as("worker session response must arrive").isNotNull();
      return response;
    }

    /** The next jobs the server starts on this worker, skipping stop commands. */
    List<VariantJob> nextJobs(int count) throws InterruptedException {
      var jobs = new ArrayList<VariantJob>();
      while (jobs.size() < count) {
        var response = nextResponse();
        if (response.hasStartVariant()) {
          jobs.add(response.getStartVariant().getJob());
        }
      }

      return jobs;
    }

    void reportFailed(VariantJob job) {
      requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setJobAttemptFailed(
                  JobAttemptFailed.newBuilder()
                      .setJobAttemptId(job.getJobAttemptId())
                      .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED))
              .build());
    }

    @Override
    public void close() {
      requests.onCompleted();
    }
  }

  private record QueueingObserver(BlockingQueue<EstablishWorkerSessionResponse> responses)
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      responses.add(value);
    }

    @Override
    public void onError(Throwable throwable) {
      // The test observes the session only through the commands it receives.
    }

    @Override
    public void onCompleted() {
      // The server ends the session when the test closes the channel.
    }
  }
}
