package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.UpgradeDatabase;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.sql.SQLException;
import java.time.Duration;
import java.util.OptionalDouble;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Starts the server on a database of its own whose probe results predate an upgrade. A subclass
 * imports {@link UpgradeDatabaseConfiguration} from its own configuration, which provides the
 * {@link ProbeUpgradeFixture} and the Flyway strategy that stores the old results.
 */
abstract class AbstractProbeUpgradeIntegrationTest extends AbstractIntegrationTest {

  @Autowired ProbeUpgradeFixture fixture;
  @Autowired JdbcTemplate jdbc;
  @Autowired Scheduler scheduler;
  @Autowired MediaFileContainerInfoRepository outcomes;
  @Autowired StreamingService streaming;

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
