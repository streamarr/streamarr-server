package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.SegmentRequestFixture.segmentRequest;
import static com.streamarr.server.fixtures.StreamSessionFixture.abrSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.support.ServerSettingsTestSupport.resetServerSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.streaming.ProducerLifecycleService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.transcode.v1.EncoderPreset;
import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
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
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(segmentStore)
            .meterRegistry(new SimpleMeterRegistry())
            .build();
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
    if (worker != null) {
      worker.close();
    }

    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    if (server != null) {
      server.close();
    }

    resetServerSettings(dsl);
    if (serverAdmin != null) {
      authTestSupport.deleteIdentity(serverAdmin);
    }
  }

  @Test
  @DisplayName(
      "Should keep the first preset for a replacement attempt and send the new preset to a variant that starts later when the setting changes")
  void shouldKeepFirstPresetForReplacementAndSendNewPresetToLaterVariantWhenSettingChanges()
      throws Exception {
    var running = startedSession();
    var firstJob = worker.nextJobs(1).getFirst();
    assertThat(firstJob.getVariant().getVariantLabel()).isEqualTo("1080p");
    assertThat(firstJob.getVariant().getEncoderPreset())
        .isEqualTo(EncoderPreset.ENCODER_PRESET_FASTER);

    serverSettingsService.changeEncoderPreset(authTestSupport.identityOf(serverAdmin), "MEDIUM");
    worker.reportFailed(firstJob);
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> !server.isRunning(running.getSessionId(), "1080p"));
    lifecycle.recover(segmentRequest(running.getSessionId(), "1080p", "1080p/segment2.m4s"));

    var replacement = worker.nextJobs(1).getFirst();
    assertThat(replacement.getVariant().getVariantLabel()).isEqualTo("1080p");
    assertThat(replacement.getJobAttemptId()).isNotEqualTo(firstJob.getJobAttemptId());
    assertThat(replacement.getVariant().getEncoderPreset())
        .isEqualTo(EncoderPreset.ENCODER_PRESET_FASTER);

    lifecycle.prepareRequestedVariant(running.getSessionId(), "720p", "720p/segment2.m4s");

    var later = worker.nextJobs(1).getFirst();
    assertThat(later.getVariant().getVariantLabel()).isEqualTo("720p");
    assertThat(later.getVariant().getEncoderPreset())
        .isEqualTo(EncoderPreset.ENCODER_PRESET_MEDIUM);
  }

  private StreamSession startedSession() {
    var session =
        abrSessionBuilder()
            .sessionId(UUID.randomUUID())
            .sourcePath(SOURCE_ROOT.resolve("movie.mkv"))
            .transcodeDecision(fullTranscodeDecision("h264"))
            .build();
    registry.save(session);
    lifecycle.startFirstListedVariant(session);
    return session;
  }
}
