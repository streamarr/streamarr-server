package com.streamarr.server.services.library;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPidRunning;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.KeyframePacketScanScheduler;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.BitrateScanTaskRequest;
import com.streamarr.server.repositories.media.BitrateScanPriorityRepository;
import com.streamarr.server.repositories.media.JooqBitrateScanPriorityRepository;
import com.streamarr.server.services.events.library.UnmeasuredCopyStartedEvent;
import com.streamarr.server.support.BoundedTask;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Unmeasured Copy Listener Shutdown Integration Tests")
class UnmeasuredCopyListenerShutdownIT extends AbstractIntegrationTest {

  // A close that waited for its raise would wait for the 30 s of lock_timeout, or of the pool's
  // connection timeout.
  private static final Duration CLOSE_BOUND = Duration.ofSeconds(5);
  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final Table<?> SCAN_TABLE =
      DSL.table(DSL.name(KeyframePacketScanScheduler.TABLE_NAME));
  private static final String RAISE = "update%keyframe_scan_scheduled_tasks%priority%";
  private static final String LOCK_BITRATE_SCAN =
      "SELECT task_instance FROM keyframe_scan_scheduled_tasks"
          + " WHERE task_name = ? AND task_instance = ? FOR UPDATE";
  private static final String LOCK_BITRATE_SCAN_UNLESS_LOCKED = LOCK_BITRATE_SCAN + " SKIP LOCKED";

  @Autowired private BitrateScanPriorityRepository priorities;
  @Autowired private BitrateScanRequests bitrateScans;
  @Autowired private DSLContext dsl;
  @Autowired private HikariDataSource pool;

  private final RaiseOutcomeMeters meters = new RaiseOutcomeMeters();
  private final UUID mediaFileId = UUID.randomUUID();

  @BeforeEach
  void clearScanTable() {
    dsl.deleteFrom(SCAN_TABLE).execute();
  }

  @AfterEach
  void clearScanTableAgain() {
    dsl.deleteFrom(SCAN_TABLE).execute();
  }

  @Test
  @DisplayName(
      "Should cancel the raise and close while the scan's row stays locked when the server shuts"
          + " down")
  void shouldCancelTheRaiseAndCloseWhileTheScansRowStaysLockedWhenTheServerShutsDown()
      throws Exception {
    requestBitrateScan();
    var listener = new UnmeasuredCopyListener(priorities, meters);
    boolean rowLockedAfterTheClose;

    try (var holder = pool.getConnection();
        var observer = pool.getConnection()) {
      holder.setAutoCommit(false);
      assertThat(tryLockBitrateScan(holder, LOCK_BITRATE_SCAN)).isTrue();
      listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
      awaitBlockedBackendPidRunning(observer, backendPid(holder), RAISE);

      BoundedTask.runWithin(CLOSE_BOUND, listener::close);
      meters.awaitCounted("cancelled");

      rowLockedAfterTheClose = !tryLockBitrateScan(observer, LOCK_BITRATE_SCAN_UNLESS_LOCKED);
    }

    assertThat(rowLockedAfterTheClose).isTrue();
    assertThat(meters.countOf("failed")).isZero();
  }

  @Test
  @DisplayName(
      "Should cancel the raise and close while the pool still has no free connection when the"
          + " server shuts down")
  void shouldCancelTheRaiseAndCloseWhileThePoolStillHasNoFreeConnectionWhenTheServerShutsDown()
      throws Exception {
    int connectionsInUseAfterTheClose;

    try (var singleConnectionPool = singleConnectionPoolLike(pool);
        var _ = singleConnectionPool.getConnection()) {
      var listener =
          new UnmeasuredCopyListener(
              new JooqBitrateScanPriorityRepository(
                  DSL.using(singleConnectionPool, SQLDialect.POSTGRES)),
              meters);
      listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
      // HikariCP reports a thread that waits for a connection only through this number.
      await()
          .atMost(BOUND)
          .until(
              () -> singleConnectionPool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 1);

      BoundedTask.runWithin(CLOSE_BOUND, listener::close);
      meters.awaitCounted("cancelled");

      connectionsInUseAfterTheClose =
          singleConnectionPool.getHikariPoolMXBean().getActiveConnections();
    }

    assertThat(connectionsInUseAfterTheClose).isOne();
    assertThat(meters.countOf("failed")).isZero();
  }

  private static HikariDataSource singleConnectionPoolLike(HikariDataSource pool) {
    var config = new HikariConfig();
    config.setJdbcUrl(pool.getJdbcUrl());
    config.setUsername(pool.getUsername());
    config.setPassword(pool.getPassword());
    config.setDataSourceProperties(pool.getDataSourceProperties());
    config.setMaximumPoolSize(1);
    return new HikariDataSource(config);
  }

  private void requestBitrateScan() {
    bitrateScans.request(
        BitrateScanTaskRequest.builder()
            .mediaFileId(mediaFileId)
            .filepathUri("file:///media/movies/" + mediaFileId + ".mkv")
            .snapshot(new SourceFileSnapshot(3, Instant.EPOCH))
            .keyframePositionsDigest("digest")
            .measurementVersion(StreamMeasurements.CURRENT_VERSION)
            .build(),
        BitrateScanPriority.PROBED_BITRATE);
  }

  // Returns whether the statement locked the scan's row.
  private boolean tryLockBitrateScan(Connection connection, String lockSql) throws SQLException {
    try (var lock = connection.prepareStatement(lockSql)) {
      lock.setString(1, BitrateScanTask.NAME);
      lock.setString(2, mediaFileId.toString());
      try (var rows = lock.executeQuery()) {
        return rows.next();
      }
    }
  }
}
