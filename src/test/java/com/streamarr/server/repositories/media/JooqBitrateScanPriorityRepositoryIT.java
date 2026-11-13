package com.streamarr.server.repositories.media;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPidRunning;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.github.kagkarlsson.scheduler.task.Priority;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.KeyframePacketScanScheduler;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.BitrateScanPriority;
import com.streamarr.server.domain.task.BitrateScanTaskRequest;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.services.library.BitrateScanRequests;
import com.streamarr.server.services.library.BitrateScanTask;
import com.streamarr.server.services.library.KeyframePacketScanRequests;
import com.streamarr.server.services.library.KeyframePacketScanTask;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.Builder;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Bitrate Scan Priority Repository Integration Tests")
class JooqBitrateScanPriorityRepositoryIT extends AbstractIntegrationTest {

  private static final long BOUND_SECONDS = 15;
  private static final Table<?> SCAN_TABLE =
      DSL.table(DSL.name(KeyframePacketScanScheduler.TABLE_NAME));
  private static final Field<String> TASK_NAME = DSL.field(DSL.name("task_name"), String.class);
  private static final Field<String> TASK_INSTANCE =
      DSL.field(DSL.name("task_instance"), String.class);
  private static final Field<Integer> PRIORITY = DSL.field(DSL.name("priority"), Integer.class);
  private static final Field<Instant> EXECUTION_TIME =
      DSL.field(DSL.name("execution_time"), Instant.class);
  private static final Field<Integer> CONSECUTIVE_FAILURES =
      DSL.field(DSL.name("consecutive_failures"), Integer.class);
  private static final Field<Long> VERSION = DSL.field(DSL.name("version"), Long.class);
  private static final Field<byte[]> TASK_DATA = DSL.field(DSL.name("task_data"), byte[].class);
  private static final String RESCHEDULE =
      "UPDATE keyframe_scan_scheduled_tasks SET execution_time = ?, version = version + 1"
          + " WHERE task_name = ? AND task_instance = ?";
  private static final String REMOVE =
      "DELETE FROM keyframe_scan_scheduled_tasks WHERE task_name = ? AND task_instance = ?";

  @Autowired private BitrateScanPriorityRepository repository;
  @Autowired private BitrateScanRequests bitrateScans;
  @Autowired private KeyframePacketScanRequests keyframePacketScans;
  @Autowired private DSLContext dsl;
  @Autowired private DataSource dataSource;

  private final ExecutorService raises = Executors.newVirtualThreadPerTaskExecutor();

  @BeforeEach
  void clearScanTable() {
    dsl.deleteFrom(SCAN_TABLE).execute();
  }

  @AfterEach
  void endRaisesAndClearScanTable() throws InterruptedException {
    raises.shutdownNow();
    assertThat(raises.awaitTermination(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    dsl.deleteFrom(SCAN_TABLE).execute();
  }

  @Test
  @DisplayName(
      "Should raise the scan's priority and keep its time, failures, version and inputs when the"
          + " scan waits at a lower priority")
  void
      shouldRaiseTheScansPriorityAndKeepItsTimeFailuresVersionAndInputsWhenTheScanWaitsAtALowerPriority() {
    var mediaFileId = UUID.randomUUID();
    requestBitrateScan(mediaFileId, BitrateScanPriority.PROBED_BITRATE);
    var retryAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
    dsl.update(SCAN_TABLE)
        .set(EXECUTION_TIME, retryAt)
        .set(CONSECUTIVE_FAILURES, 2)
        .where(TASK_INSTANCE.eq(mediaFileId.toString()))
        .execute();
    var waiting = bitrateScanOf(mediaFileId);

    var raised =
        repository.tryRaisePriority(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION);

    assertThat(raised).isTrue();
    assertThat(bitrateScanOf(mediaFileId))
        .isEqualTo(
            waiting.toBuilder()
                .priority(BitrateScanPriority.COPIED_BY_STREAM_SESSION.value())
                .build());
    assertThat(waiting.executionTime()).isEqualTo(retryAt);
  }

  @Test
  @DisplayName(
      "Should keep the scan's priority when the scan already has at least the requested priority")
  void shouldKeepTheScansPriorityWhenTheScanAlreadyHasAtLeastTheRequestedPriority() {
    var mediaFileId = UUID.randomUUID();
    requestBitrateScan(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION);
    var waiting = bitrateScanOf(mediaFileId);

    var raisedToTheSame =
        repository.tryRaisePriority(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION);
    var raisedToALower =
        repository.tryRaisePriority(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);

    assertThat(raisedToTheSame).isFalse();
    assertThat(raisedToALower).isFalse();
    assertThat(bitrateScanOf(mediaFileId)).isEqualTo(waiting);
  }

  @Test
  @DisplayName(
      "Should leave the file's keyframe packet scan and other files' bitrate scans unchanged when"
          + " the file's bitrate scan is raised")
  void
      shouldLeaveTheFilesKeyframePacketScanAndOtherFilesBitrateScansUnchangedWhenTheFilesBitrateScanIsRaised() {
    var mediaFileId = UUID.randomUUID();
    var otherFileId = UUID.randomUUID();
    requestBitrateScan(mediaFileId, BitrateScanPriority.PROBED_BITRATE);
    requestKeyframePacketScan(mediaFileId);
    requestBitrateScan(otherFileId, BitrateScanPriority.PROBED_BITRATE);

    repository.tryRaisePriority(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION);

    assertThat(dsl.select(TASK_NAME, TASK_INSTANCE, PRIORITY).from(SCAN_TABLE).fetch())
        .extracting(row -> tuple(row.value1(), row.value2(), row.value3()))
        .containsExactlyInAnyOrder(
            tuple(
                BitrateScanTask.NAME,
                mediaFileId.toString(),
                BitrateScanPriority.COPIED_BY_STREAM_SESSION.value()),
            tuple(KeyframePacketScanTask.NAME, mediaFileId.toString(), Priority.MEDIUM),
            tuple(
                BitrateScanTask.NAME,
                otherFileId.toString(),
                BitrateScanPriority.PROBED_BITRATE.value()));
  }

  @Test
  @DisplayName("Should write nothing when the file has no bitrate scan")
  void shouldWriteNothingWhenTheFileHasNoBitrateScan() {
    var mediaFileId = UUID.randomUUID();
    requestKeyframePacketScan(mediaFileId);

    var raised =
        repository.tryRaisePriority(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION);

    assertThat(raised).isFalse();
    assertThat(dsl.select(TASK_NAME, PRIORITY).from(SCAN_TABLE).fetch())
        .extracting(row -> tuple(row.value1(), row.value2()))
        .containsExactly(tuple(KeyframePacketScanTask.NAME, Priority.MEDIUM));
  }

  @Test
  @DisplayName(
      "Should raise the rescheduled scan when a chunk's completion reschedules the scan while the"
          + " raise waits for its row")
  void
      shouldRaiseTheRescheduledScanWhenAChunksCompletionReschedulesTheScanWhileTheRaiseWaitsForItsRow()
          throws Exception {
    var mediaFileId = UUID.randomUUID();
    requestBitrateScan(mediaFileId, BitrateScanPriority.PROBED_BITRATE);
    var waiting = bitrateScanOf(mediaFileId);
    var nextChunkAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

    try (var completion = dataSource.getConnection();
        var observer = dataSource.getConnection()) {
      completion.setAutoCommit(false);
      try (var reschedule = completion.prepareStatement(RESCHEDULE)) {
        reschedule.setTimestamp(1, Timestamp.from(nextChunkAt));
        reschedule.setString(2, BitrateScanTask.NAME);
        reschedule.setString(3, mediaFileId.toString());
        assertThat(reschedule.executeUpdate()).isOne();
      }

      var raise = raiseBehind(completion, observer, mediaFileId);
      completion.commit();

      assertThat(raise.get(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(bitrateScanOf(mediaFileId))
        .isEqualTo(
            waiting.toBuilder()
                .priority(BitrateScanPriority.COPIED_BY_STREAM_SESSION.value())
                .executionTime(nextChunkAt)
                .version(waiting.version() + 1)
                .build());
  }

  @Test
  @DisplayName(
      "Should write nothing when the scan finishes and its row is removed while the raise waits"
          + " for the row")
  void shouldWriteNothingWhenTheScanFinishesAndItsRowIsRemovedWhileTheRaiseWaitsForTheRow()
      throws Exception {
    var mediaFileId = UUID.randomUUID();
    requestBitrateScan(mediaFileId, BitrateScanPriority.PROBED_BITRATE);

    try (var completion = dataSource.getConnection();
        var observer = dataSource.getConnection()) {
      completion.setAutoCommit(false);
      try (var remove = completion.prepareStatement(REMOVE)) {
        remove.setString(1, BitrateScanTask.NAME);
        remove.setString(2, mediaFileId.toString());
        assertThat(remove.executeUpdate()).isOne();
      }

      var raise = raiseBehind(completion, observer, mediaFileId);
      completion.commit();

      assertThat(raise.get(BOUND_SECONDS, TimeUnit.SECONDS)).isFalse();
    }

    assertThat(dsl.fetchCount(SCAN_TABLE)).isZero();
  }

  // Starts the raise and returns once it waits for the row that the completion's transaction
  // holds.
  private Future<Boolean> raiseBehind(Connection completion, Connection observer, UUID mediaFileId)
      throws SQLException {
    var raise =
        raises.submit(
            () ->
                repository.tryRaisePriority(
                    mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION));
    awaitBlockedBackendPidRunning(
        observer, backendPid(completion), "update%keyframe_scan_scheduled_tasks%priority%");
    return raise;
  }

  private void requestBitrateScan(UUID mediaFileId, BitrateScanPriority priority) {
    bitrateScans.request(
        BitrateScanTaskRequest.builder()
            .mediaFileId(mediaFileId)
            .filepathUri("file:///media/movies/" + mediaFileId + ".mkv")
            .snapshot(new SourceFileSnapshot(3, Instant.EPOCH))
            .keyframePositionsDigest("digest")
            .measurementVersion(StreamMeasurements.CURRENT_VERSION)
            .build(),
        priority);
  }

  private void requestKeyframePacketScan(UUID mediaFileId) {
    keyframePacketScans.request(
        KeyframePacketScanTaskRequest.builder()
            .mediaFileId(mediaFileId)
            .filepathUri("file:///media/movies/" + mediaFileId + ".mkv")
            .snapshot(new SourceFileSnapshot(3, Instant.EPOCH))
            .probeVersion(ProbeVersion.CURRENT)
            .build());
  }

  @Builder(toBuilder = true)
  private record StoredScan(
      int priority,
      Instant executionTime,
      Integer consecutiveFailures,
      long version,
      String data) {}

  private StoredScan bitrateScanOf(UUID mediaFileId) {
    return dsl.select(PRIORITY, EXECUTION_TIME, CONSECUTIVE_FAILURES, VERSION, TASK_DATA)
        .from(SCAN_TABLE)
        .where(TASK_NAME.eq(BitrateScanTask.NAME))
        .and(TASK_INSTANCE.eq(mediaFileId.toString()))
        .fetchSingle(
            row ->
                StoredScan.builder()
                    .priority(row.value1())
                    .executionTime(row.value2())
                    .consecutiveFailures(row.value3())
                    .version(row.value4())
                    .data(HexFormat.of().formatHex(row.value5()))
                    .build());
  }
}
