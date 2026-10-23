package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.streamarr.server.config.persistence.SecurityAuditActorSource;
import com.streamarr.server.domain.audit.AuditActor;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.services.probe.PlaybackProbeClaims;
import com.streamarr.server.services.streaming.ProbeCapacity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

@Tag("UnitTest")
@DisplayName("Probe scheduling configuration")
class ProbeSchedulingConfigurationTest {

  private static final long BOUND_SECONDS = 5;
  private static final String PROBE_TABLE = JdbcTaskRepository.DEFAULT_TABLE_NAME;

  private final ProbeSchedulingConfiguration configuration = new ProbeSchedulingConfiguration();

  @Test
  @DisplayName("Should run scheduler executions on virtual threads when customizing db-scheduler")
  void shouldRunSchedulerExecutionsOnVirtualThreadsWhenCustomizingDbScheduler()
      throws InterruptedException, ExecutionException {
    try (var executor = customizer().executorService().orElseThrow()) {
      assertThat(executor.submit(() -> Thread.currentThread().isVirtual()).get()).isTrue();
    }
  }

  @Test
  @DisplayName("Should run scheduler executions as the System actor when customizing db-scheduler")
  void shouldRunSchedulerExecutionsAsSystemActorWhenCustomizingDbScheduler()
      throws InterruptedException, ExecutionException, TimeoutException {
    try (var executor = customizer().executorService().orElseThrow()) {
      var actor = executor.submit(() -> new SecurityAuditActorSource().currentActor());

      assertThat(actor.get(BOUND_SECONDS, TimeUnit.SECONDS)).contains(new AuditActor.System());
    }
  }

  // Each scheduler shuts down its executor when it stops, so schedulers must not share one.
  @Test
  @DisplayName(
      "Should keep running scheduler executions when another scheduler's executor shuts down")
  void shouldKeepRunningSchedulerExecutionsWhenAnotherSchedulersExecutorShutsDown()
      throws InterruptedException, ExecutionException, TimeoutException {
    var customizer = customizer();
    try (var first = customizer.executorService().orElseThrow();
        var second = customizer.executorService().orElseThrow()) {
      first.shutdown();

      var execution = second.submit(() -> true);

      assertThat(execution.get(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("Should round-trip a probe task request when serializing task data")
  void shouldRoundTripAProbeTaskRequestWhenSerializingTaskData() {
    var serializer = configuration.probeTaskSerializer();
    var request =
        ProbeTaskRequest.builder()
            .mediaFileId(UUID.randomUUID())
            .libraryId(UUID.randomUUID())
            .filepathUri("file:///library/movie.mkv")
            .snapshot(new SourceFileSnapshot(1234, Instant.parse("2026-09-11T10:00:00.123456789Z")))
            .probeVersion(1)
            .build();

    var restored = serializer.deserialize(ProbeTaskRequest.class, serializer.serialize(request));

    assertThat(new JsonMapper().readTree(serializer.serialize(request)).isObject()).isTrue();
    assertThat(restored).isEqualTo(request);
    assertThat(
            configuration
                .dbSchedulerCustomizer(
                    serializer, new DriverManagerDataSource(), jdbcCustomization())
                .serializer())
        .contains(serializer);
  }

  @Test
  @DisplayName(
      "Should leave the probe executions in progress out of the free probe capacity when"
          + " db-scheduler sets the probe scheduler's threads")
  void
      shouldLeaveTheProbeExecutionsInProgressOutOfTheFreeProbeCapacityWhenDbSchedulerSetsThreads() {
    var environment =
        new MockEnvironment()
            .withProperty("db-scheduler.threads", "8")
            .withProperty("db-scheduler.polling-strategy", "lock-and-fetch")
            .withProperty("db-scheduler.polling-strategy-upper-limit-fraction-of-threads", "1.0");
    var customization =
        configuration.probeCapacityJdbcCustomization(
            () ->
                ProbeCapacity.builder()
                    .freeBackgroundProbeUnits(3)
                    .freeKeyframePacketScanUnits(1)
                    .build(),
            new PlaybackProbeClaims(Clock.systemUTC(), new SimpleMeterRegistry()),
            environment);

    // Two of the eight threads run probe executions, so db-scheduler asks for at most six probes.
    assertThat(customization.createGenericSelectForUpdateQuery(PROBE_TABLE, 6, "", true))
        .endsWith(" LIMIT 1");
    // One of the scan scheduler's sixteen threads runs a scan that no worker runs yet.
    assertThat(
            customization.createGenericSelectForUpdateQuery(
                KeyframePacketScanScheduler.TABLE_NAME, 15, "", false))
        .endsWith(" LIMIT 0");
  }

  @Test
  @DisplayName(
      "Should pick probes through the probe capacity customization when customizing db-scheduler")
  void shouldPickProbesThroughTheProbeCapacityCustomizationWhenCustomizingDbScheduler() {
    var customization = jdbcCustomization();

    assertThat(
            configuration
                .dbSchedulerCustomizer(
                    configuration.probeTaskSerializer(),
                    new DriverManagerDataSource(),
                    customization)
                .jdbcCustomization())
        .containsSame(customization);
  }

  private DbSchedulerCustomizer customizer() {
    return configuration.dbSchedulerCustomizer(
        configuration.probeTaskSerializer(), new DriverManagerDataSource(), jdbcCustomization());
  }

  private static ProbeCapacityJdbcCustomization jdbcCustomization() {
    return ProbeCapacityJdbcCustomization.builder()
        .capacitySource(() -> ProbeCapacity.NONE)
        .playbackProbeClaims(new PlaybackProbeClaims(Clock.systemUTC(), new SimpleMeterRegistry()))
        .probeSchedulerUpperLimit(1)
        .scanSchedulerUpperLimit(1)
        .build();
  }
}
