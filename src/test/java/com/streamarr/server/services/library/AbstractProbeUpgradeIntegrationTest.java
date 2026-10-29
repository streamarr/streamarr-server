package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.UpgradeDatabase;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.events.library.ScanEndedEvent;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.OptionalDouble;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts the server on a database of its own whose probe results predate an upgrade. A subclass
 * imports {@link UpgradeDatabaseConfiguration} and {@link UpgradeProgress} from its own
 * configuration, which provides the {@link ProbeUpgradeFixture} and the Flyway strategy that stores
 * the old results.
 */
abstract class AbstractProbeUpgradeIntegrationTest extends AbstractIntegrationTest {

  @Autowired ProbeUpgradeFixture fixture;
  @Autowired UpgradeProgress progress;
  @Autowired JdbcTemplate jdbc;
  @Autowired MediaFileContainerInfoRepository outcomes;
  @Autowired StreamingService streaming;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private ProbeExecution probeExecution;
  @Autowired private Clock clock;

  @TestBean ProbeCapacitySource probeCapacitySource;
  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean SegmentStore segmentStore;
  @TestBean PlaybackAuthorityGate authorityGate;

  static ProbeCapacitySource probeCapacitySource() {
    return ProbeSchedulerStarter.FAKE_WORKERS;
  }

  static TranscodeExecutor transcodeExecutor() {
    return new FakeTranscodeExecutor();
  }

  static SegmentStore segmentStore() {
    return new FakeSegmentStore();
  }

  static PlaybackAuthorityGate authorityGate() {
    return (_, _) -> true;
  }

  static ProbeOutcome.Success completeProbe() {
    return ProbeFixture.completeProbe(
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(12))
            .framerate(OptionalDouble.of(24))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(6), Duration.ofSeconds(12)))
            .build());
  }

  // The source's identity, size, frame rate, dynamic range, pixel format and codec string.
  static StreamInfo.StreamInfoBuilder retainedVideo(StreamInfo source) {
    return StreamInfo.builder()
        .index(source.index())
        .codecType(source.codecType())
        .codec(source.codec())
        .width(source.width())
        .height(source.height())
        .framerate(source.framerate())
        .dynamicRange(source.dynamicRange())
        .bitDepth(source.bitDepth())
        .chromaSubsampling(source.chromaSubsampling())
        .codecString(source.codecString());
  }

  Scheduler startProbeScheduler() {
    return ProbeSchedulerStarter.fromContext(applicationContext)
        .start(probeExecution, progress.probeAttemptListener(), clock)
        .scheduler();
  }

  /** Signals each probe attempt and each library scan that ends. */
  static class UpgradeProgress {

    private static final long TIMEOUT_SECONDS = 20;

    private final Semaphore probeAttemptsEnded = new Semaphore(0);
    private final Semaphore scansEnded = new Semaphore(0);

    // db-scheduler calls the listener after the attempt's completion or failure handler has saved
    // its result.
    AbstractSchedulerListener probeAttemptListener() {
      return new AbstractSchedulerListener() {
        @Override
        public void onExecutionComplete(ExecutionComplete executionComplete) {
          probeAttemptsEnded.release();
        }
      };
    }

    @TransactionalEventListener(
        classes = ScanEndedEvent.class,
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true)
    void onScanEnded() {
      scansEnded.release();
    }

    void awaitProbeAttemptsEnded(int attempts) throws InterruptedException {
      awaitEnds(probeAttemptsEnded, attempts, "probe attempts");
    }

    void awaitScansEnded(int scans) throws InterruptedException {
      awaitEnds(scansEnded, scans, "library scans");
    }

    private static void awaitEnds(Semaphore ends, int count, String what)
        throws InterruptedException {
      assertThat(ends.tryAcquire(count, TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("%d more %s ended", count, what)
          .isTrue();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class UpgradeDatabaseConfiguration {

    // A database of its own gives the startup the schema that the old results were stored under,
    // without hand-built copies of historical tables, and keeps the other test classes' files out
    // of the startup re-scan.
    @Bean
    UpgradeDatabase upgradeDatabase(JdbcConnectionDetails connectionDetails) throws SQLException {
      return UpgradeDatabase.create(connectionDetails);
    }

    @Bean
    @FlywayDataSource
    DataSource dataSource(UpgradeDatabase database) {
      return database.dataSource();
    }
  }
}
