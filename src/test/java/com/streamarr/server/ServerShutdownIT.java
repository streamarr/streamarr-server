package com.streamarr.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.config.ArtworkPrioritySchedulers;
import com.streamarr.server.config.BitrateScansWaiting;
import com.streamarr.server.config.BoundedConnectionPoolClose;
import com.streamarr.server.config.KeyframePacketScanScheduler;
import com.streamarr.server.config.LibraryOperationScheduler;
import com.streamarr.server.config.ProbeSchedulerStop;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.LoopbackProbeWorker;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.KeyframePacketScanRequests;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.server.support.PostgresLockTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/**
 * Starts the server as a process starts it, with its schedulers running, lets an unanswering worker
 * hold a probe and a keyframe packet scan, and shuts the server down. The test's own context only
 * stores the fixtures.
 */
@Tag("IntegrationTest")
@DisplayName("Server shutdown integration tests")
class ServerShutdownIT extends AbstractIntegrationTest {

  // The deployments give the server 15 s to stop; it stops within about 10 s in every case.
  private static final Duration STOP_BOUND = Duration.ofSeconds(10);
  // Far below the 60 s after which db-scheduler finds an execution that no server holds dead.
  private static final Duration CLAIM_BOUND = Duration.ofSeconds(20);
  private static final String PROBE_TABLE = "scheduled_tasks";

  @TempDir Path sourceRoot;

  @Autowired private LibraryRepository libraries;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  private final List<ConfigurableApplicationContext> servers = new ArrayList<>();
  private final List<MediaFile> createdFiles = new ArrayList<>();
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    deleteScheduledWork();
    libraryId = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary()).getId();
  }

  @AfterEach
  void tearDown() {
    servers.forEach(ConfigurableApplicationContext::close);
    deleteScheduledWork();
    mediaFiles.deleteAll(createdFiles);
    libraries.deleteById(libraryId);
  }

  @Test
  @DisplayName(
      "Should stop within ten seconds, with the grace periods of both schedulers overlapping, when"
          + " an unanswering worker holds a probe and a keyframe packet scan")
  void shouldStopWithinTenSecondsWhenAnUnansweringWorkerHoldsAProbeAndAKeyframePacketScan()
      throws Exception {
    var held = heldWork();
    var server = startServer();
    var gracePeriod =
        server
            .getEnvironment()
            .getRequiredProperty("db-scheduler.shutdown-max-wait", Duration.class);

    try (var worker = connectedWorker(server)) {
      requestHeldWork(server, held);
      awaitStartedProbeAndScan(worker, held);

      var stopping = System.nanoTime();
      BoundedTask.runWithin(STOP_BOUND, server::close);
      var stopped = Duration.ofNanos(System.nanoTime() - stopping);

      assertThat(stopped)
          .as("Both schedulers wait their grace period at once, not one after the other")
          .isLessThan(gracePeriod.multipliedBy(2));
    }
  }

  @Test
  @DisplayName(
      "Should release the probe and the keyframe packet scan that a shutdown cut off without a"
          + " failure, and run both at once when the server starts again")
  void shouldReleaseTheCutOffWorkWithoutAFailureAndRunItAtOnceWhenTheServerStartsAgain()
      throws Exception {
    var held = heldWork();
    var server = startServer();
    ScheduledRow probeAtStop;
    ScheduledRow scanAtStop;
    try (var worker = connectedWorker(server)) {
      requestHeldWork(server, held);
      awaitStartedProbeAndScan(worker, held);
      probeAtStop = scheduledRow(PROBE_TABLE, held.probed());
      scanAtStop = scheduledRow(KeyframePacketScanScheduler.TABLE_NAME, held.scanned());
      BoundedTask.runWithin(STOP_BOUND, server::close);
    }

    assertThat(scheduledRow(PROBE_TABLE, held.probed()))
        .as("The probe is claimable at once, at its due time, and counts no failure")
        .isEqualTo(new ScheduledRow(false, 0, probeAtStop.executionTime()));
    assertThat(scheduledRow(KeyframePacketScanScheduler.TABLE_NAME, held.scanned()))
        .as("The keyframe packet scan is claimable at once, at its due time, and counts no failure")
        .isEqualTo(new ScheduledRow(false, 0, scanAtStop.executionTime()));

    var restarted = startServer();
    try (var worker = connectedWorker(restarted)) {
      awaitStartedProbeAndScan(worker, held);
    }
  }

  // The worker answers that it has no free unit, so the probe stores nothing, and its completion
  // locks the media file's row to defer it.
  @Test
  @DisplayName(
      "Should release a probe without a failure, at its due time, when its completion began before"
          + " the shutdown and waits until the shutdown's interrupt")
  void shouldReleaseAProbeWhenItsCompletionBeganBeforeTheShutdownAndWaitsUntilTheInterrupt()
      throws Exception {
    var probed = createMediaFile("probed.mkv");

    var atStart =
        stopTheServerWhileTheProbeWaitsForItsFileRow(
            probed, ProbeFailure.PROBE_FAILURE_NO_FREE_CAPACITY);

    assertThat(scheduledRow(PROBE_TABLE, probed))
        .as("The probe is claimable at once, at its due time, and counts no failure")
        .isEqualTo(new ScheduledRow(false, 0, atStart.executionTime()));
  }

  // The worker reports a failure that the probe retries, so the probe's failure handler locks the
  // media file's row to store the failure. The shutdown's interrupt ends that wait, and the server
  // leaves the probe for db-scheduler to find dead.
  @Test
  @DisplayName(
      "Should leave a probe marked as running, and run it again with one failure once db-scheduler"
          + " finds it dead, when the shutdown's interrupt cuts off its failure handler")
  void shouldLeaveAProbeRunningUntilFoundDeadWhenTheShutdownsInterruptCutsOffItsFailureHandler()
      throws Exception {
    var probed = createMediaFile("probed.mkv");

    var atStart =
        stopTheServerWhileTheProbeWaitsForItsFileRow(
            probed, ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);

    assertThat(scheduledRow(PROBE_TABLE, probed))
        .as("The probe stays marked as running, at its due time, and counts no failure")
        .isEqualTo(new ScheduledRow(true, 0, atStart.executionTime()));

    ageTheLastHeartbeat(probed);
    var restarted = startServer();
    try (var worker = connectedWorker(restarted)) {
      var again = worker.nextResponse(CLAIM_BOUND);
      assertThat(again).as("The restarted server runs the probe again").isNotNull();
      assertThat(again.hasStartProbe()).as("The server starts a probe: %s", again).isTrue();
      assertThat(scheduledRow(PROBE_TABLE, probed).consecutiveFailures())
          .as("db-scheduler counts one failure when it finds the probe dead")
          .isOne();
    }
  }

  // Spring stops the lifecycles of one phase together and waits for them all for at most the
  // phase's timeout. A lifecycle in another phase stops only after that wait.
  @Test
  @DisplayName(
      "Should stop the background schedulers, the scheduled tasks and the waiting bitrate scans'"
          + " count in the web server's graceful shutdown phase, and bound the connection pool's"
          + " close, when the server stops")
  void shouldStopTheBackgroundWorkInTheWebServersGracefulShutdownPhaseWhenTheServerStops() {
    var server = startServer();

    var webServerPhase = server.getBean(WebServerGracefulShutdownLifecycle.class).getPhase();
    assertThat(
            Stream.of(
                    LibraryOperationScheduler.class,
                    ArtworkPrioritySchedulers.class,
                    KeyframePacketScanScheduler.class,
                    ProbeSchedulerStop.class,
                    SimpleAsyncTaskScheduler.class,
                    BitrateScansWaiting.class)
                .map(lifecycle -> server.getBean(lifecycle).getPhase()))
        .containsOnly(webServerPhase);
    assertThat(server.getBeansOfType(BoundedConnectionPoolClose.class)).hasSize(1);
  }

  // A probe of one file and a keyframe packet scan of another. The scan's file holds a stored
  // outcome that needs the scan.
  private HeldWork heldWork() throws IOException {
    var probed = createMediaFile("probed.mkv");
    var scanned = createMediaFile("scanned.mkv");
    assertThat(
            outcomes.publish(
                ProbePublication.builder()
                    .mediaFileId(scanned.getId())
                    .snapshot(snapshotOf(scanned))
                    .probeVersion(ProbeVersion.CURRENT)
                    .outcome(
                        new ProbeOutcome.Success(
                            ProbeContainer.builder()
                                .format(Optional.of("mpegts"))
                                .duration(Optional.of(Duration.ofSeconds(60)))
                                .build(),
                            List.of(),
                            new KeyframeKnowledge.PacketScanRequired(
                                KeyframePacketScanReason.NO_CONTAINER_INDEX)))
                    .build()))
        .isTrue();
    return new HeldWork(probed, scanned);
  }

  private static void requestHeldWork(ConfigurableApplicationContext server, HeldWork held)
      throws IOException {
    requestProbe(server, held.probed());
    server
        .getBean(KeyframePacketScanRequests.class)
        .request(
            KeyframePacketScanTaskRequest.builder()
                .mediaFileId(held.scanned().getId())
                .filepathUri(held.scanned().getFilepathUri())
                .snapshot(snapshotOf(held.scanned()))
                .probeVersion(ProbeVersion.CURRENT)
                .build());
  }

  private static void requestProbe(ConfigurableApplicationContext server, MediaFile file)
      throws IOException {
    server
        .getBean(ProbeTaskRequests.class)
        .request(
            ProbeTaskRequest.builder()
                .mediaFileId(file.getId())
                .libraryId(file.getLibraryId())
                .filepathUri(file.getFilepathUri())
                .snapshot(snapshotOf(file))
                .probeVersion(ProbeVersion.CURRENT)
                .build());
  }

  // The worker never answers, so the probe and the scan run until the shutdown cuts them off.
  private static void awaitStartedProbeAndScan(LoopbackProbeWorker worker, HeldWork held)
      throws Exception {
    var started = new ArrayList<ProbeRequest>();
    while (started.size() < 2) {
      var response = worker.nextResponse(CLAIM_BOUND);
      assertThat(response).as("The server starts a probe").isNotNull();
      assertThat(response.hasStartProbe()).as("The server starts a probe: %s", response).isTrue();
      started.add(response.getStartProbe().getRequest());
    }

    assertThat(started)
        .extracting(
            request -> request.getSource().getRelativeKey(), ProbeRequest::getScanKeyframePackets)
        .containsExactlyInAnyOrder(
            tuple(held.probed().getFilename(), false), tuple(held.scanned().getFilename(), true));
  }

  // The test holds the media file's row while the worker answers the started probe. The probe locks
  // that row to store the answer, so the probe waits from before the shutdown until the shutdown's
  // interrupt ends the wait. Returns the probe's scheduled row as the probe started.
  private ScheduledRow stopTheServerWhileTheProbeWaitsForItsFileRow(
      MediaFile probed, ProbeFailure answer) throws Exception {
    var server = startServer();
    try (var worker = connectedWorker(server)) {
      requestProbe(server, probed);
      var started = worker.nextResponse(CLAIM_BOUND);
      assertThat(started.hasStartProbe()).as("The server starts a probe: %s", started).isTrue();
      var atStart = scheduledRow(PROBE_TABLE, probed);

      try (var held = PostgresLockTestSupport.lockRow(mediaFileRow(probed));
          var observer = dataSource.getConnection()) {
        worker.reply(failed(started.getStartProbe().getRequest(), answer));
        PostgresLockTestSupport.awaitBlockedBackendPid(
            observer, held.backendPid(), "transactionid");

        BoundedTask.runWithin(STOP_BOUND, server::close);
      }

      return atStart;
    }
  }

  private static LoopbackProbeWorker connectedWorker(ConfigurableApplicationContext server)
      throws Exception {
    return LoopbackProbeWorker.builder()
        .port(server.getBean(WorkerSessionServer.class).port())
        .probeVersion(ProbeVersion.CURRENT)
        .availableSlots(1)
        .backgroundProbeCapacity(1)
        .keyframePacketScanCapacity(1)
        .build();
  }

  // The server runs as a process runs it: db-scheduler is on, and the test's own configuration
  // classes stay out of its component scan.
  private ConfigurableApplicationContext startServer() {
    var server =
        new SpringApplicationBuilder(StreamarrServerApplication.class)
            .profiles("test")
            .initializers(
                context ->
                    context
                        .getBeanFactory()
                        .registerSingleton("testComponentExclusion", new TestComponentExclusion()))
            .run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--db-scheduler.enabled=true",
                "--server.port=0",
                "--streaming.remote.source-root=" + sourceRoot,
                "--spring.main.banner-mode=off");
    servers.add(server);
    return server;
  }

  private MediaFile createMediaFile(String filename) throws IOException {
    var source = Files.write(sourceRoot.resolve(filename), new byte[] {1, 2, 3});
    var file =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .status(MediaFileStatus.MATCHED)
                .filename(filename)
                .filepathUri(FilepathCodec.encode(source))
                .size(3)
                .build());
    createdFiles.add(file);
    return file;
  }

  private static SourceFileSnapshot snapshotOf(MediaFile file) throws IOException {
    return SourceFileSnapshot.of(
        Files.readAttributes(
            FilepathCodec.decode(file.getFilepathUri()), BasicFileAttributes.class));
  }

  private RowLockTarget mediaFileRow(MediaFile file) {
    return RowLockTarget.builder()
        .dataSource(dataSource)
        .table("media_file")
        .rowId(file.getId())
        .build();
  }

  private static ProbeAttemptResult failed(ProbeRequest request, ProbeFailure failure) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion())
        .setFailure(failure)
        .build();
  }

  private ScheduledRow scheduledRow(String table, MediaFile file) {
    return jdbc.queryForObject(
        "SELECT picked, consecutive_failures, execution_time FROM "
            + table
            + " WHERE task_instance = ?",
        (row, _) ->
            new ScheduledRow(
                row.getBoolean("picked"),
                row.getInt("consecutive_failures"),
                row.getTimestamp("execution_time").toInstant()),
        file.getId().toString());
  }

  // Stands in for the minute without a heartbeat after which db-scheduler finds an execution dead.
  private void ageTheLastHeartbeat(MediaFile file) {
    var aged =
        jdbc.update(
            "UPDATE "
                + PROBE_TABLE
                + " SET last_heartbeat = now() - interval '1 hour' WHERE task_instance = ?",
            file.getId().toString());
    assertThat(aged).isOne();
  }

  private void deleteScheduledWork() {
    jdbc.update("DELETE FROM " + PROBE_TABLE);
    jdbc.update("DELETE FROM " + KeyframePacketScanScheduler.TABLE_NAME);
  }

  private record HeldWork(MediaFile probed, MediaFile scanned) {}

  private record ScheduledRow(boolean picked, int consecutiveFailures, Instant executionTime) {}

  // The test context framework keeps test configuration out of a test's own context. A server
  // started by hand has no such filter, so it gets this one.
  private static final class TestComponentExclusion extends TypeExcludeFilter {

    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
      return reader.getAnnotationMetadata().isAnnotated(TestComponent.class.getName());
    }
  }
}
