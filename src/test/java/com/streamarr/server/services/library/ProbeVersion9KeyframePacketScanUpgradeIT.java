package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.config.KeyframePacketScanScheduler;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.properties.ProbeSchedulingProperties;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepositoryCustomImpl;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.KeyframePacketScanner;
import com.streamarr.server.support.UnstartedSchedulerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("IntegrationTest")
@DisplayName("Probe Version 9 Keyframe Packet Scan Upgrade Integration Tests")
@Import(ProbeVersion9KeyframePacketScanUpgradeIT.UpgradeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProbeVersion9KeyframePacketScanUpgradeIT extends AbstractProbeUpgradeIntegrationTest {

  private static final int VERSION_8 = ProbeVersion.FIRST_TO_REPORT_SCANNED_STREAM_ENDS - 1;
  private static final Duration HEADER_DURATION = Duration.ofSeconds(12);
  // The probe lists the video stream first and the audio stream second.
  private static final int VIDEO_STREAM_INDEX = 0;
  private static final int AUDIO_STREAM_INDEX = 1;
  // The audio stream ends after the video stream, and both end more than 1 s before the duration
  // that the container header states. A stream session ends at the later of the two scanned ends
  // only when the scan has stored both of them (ADR 0037, early ends).
  private static final Duration SCANNED_VIDEO_END = Duration.ofSeconds(10);
  private static final Duration SCANNED_AUDIO_END = Duration.ofMillis(10_500);
  private static final KeyframeKnowledge SCANNED_KEYFRAMES =
      new KeyframeKnowledge.Known(
          KeyframePositions.builder()
              .streamIndex(VIDEO_STREAM_INDEX)
              .timeBaseNumerator(1)
              .timeBaseDenominator(1000)
              .source(KeyframePositionSource.PACKET_SCAN)
              .keyframes(List.of(new KeyframePosition(0, 0), new KeyframePosition(6_000, 6_000)))
              .build());
  private static final long TIMEOUT_SECONDS = 20;

  @TestBean FfprobeService ffprobeService;
  @TestBean KeyframePacketScanner keyframePacketScanner;
  @Autowired private ApplicationContext applicationContext;

  private final Semaphore keyframePacketScansEnded = new Semaphore(0);

  // The file has no container index, so every probe of it asks for a keyframe packet scan.
  static FfprobeService ffprobeService() {
    var probe = new FakeFfprobeService();
    probe.answerWith(packetScanRequired().build());
    return probe;
  }

  // A worker that answers probe version 9 reports each stream's end beside the keyframe positions.
  static KeyframePacketScanner keyframePacketScanner() {
    return _ ->
        packetScanRequired()
            .keyframes(SCANNED_KEYFRAMES)
            .scannedStreamEnds(
                List.of(
                    new SourceStreamEnd(VIDEO_STREAM_INDEX, SCANNED_VIDEO_END),
                    new SourceStreamEnd(AUDIO_STREAM_INDEX, SCANNED_AUDIO_END)))
            .build();
  }

  private static ProbeOutcome.Success.SuccessBuilder packetScanRequired() {
    return ProbeFixture.completeProbeBuilder(
        defaultProbeBuilder()
            .duration(HEADER_DURATION)
            .keyframes(
                new KeyframeKnowledge.PacketScanRequired(
                    KeyframePacketScanReason.NO_CONTAINER_INDEX))
            .build());
  }

  @Test
  @DisplayName(
      "Should end a stream session at the stream ends that a new keyframe packet scan reads when the server starts after upgrading")
  void
      shouldEndAStreamSessionAtTheStreamEndsThatANewKeyframePacketScanReadsWhenTheServerStartsAfterUpgrading()
          throws InterruptedException {
    var mediaFileId = fixture.mediaFileIds().getFirst();
    assertThat(playedProbe(mediaFileId).mediaEnd())
        .as("the version 8 result, whose keyframe packet scan read no stream end")
        .isEqualTo(HEADER_DURATION);

    var probeScheduler = startProbeScheduler();
    var scanScheduler = startKeyframePacketScanScheduler();
    try {
      awaitKeyframePacketScansEnded(1);
      assertThat(playedProbe(mediaFileId).mediaEnd())
          .as("the later of the stream ends that the version 9 scan read")
          .isEqualTo(SCANNED_AUDIO_END);
      progress.awaitScansEnded(1);
    } finally {
      scanScheduler.stop();
      probeScheduler.stop();
    }
  }

  // The scan scheduler runs the context's scan execution, with the fake worker's scanner.
  private KeyframePacketScanScheduler startKeyframePacketScanScheduler() {
    var scheduler =
        KeyframePacketScanScheduler.builder()
            .schedulerProperties(
                UnstartedSchedulerConfiguration.schedulerProperties(
                    applicationContext.getEnvironment()))
            .customizer(pickingCustomizer())
            .dataSource(applicationContext.getBean(DataSource.class))
            .transactionManager(applicationContext.getBean(PlatformTransactionManager.class))
            .execution(applicationContext.getBean(KeyframePacketScanExecution.class))
            .bitrateScanExecution(applicationContext.getBean(BitrateScanExecution.class))
            .outcomes(outcomes)
            .bitrateScans(applicationContext.getBean(BitrateScanRepository.class))
            .meterRegistry(applicationContext.getBean(MeterRegistry.class))
            .properties(applicationContext.getBean(ProbeSchedulingProperties.class))
            .clock(applicationContext.getBean(Clock.class))
            .listeners(List.of(keyframePacketScanListener()))
            .build();
    scheduler.start();
    return scheduler;
  }

  private DbSchedulerCustomizer pickingCustomizer() {
    return ProbeSchedulerStarter.fromContext(applicationContext).pickingCustomizer();
  }

  // db-scheduler calls the listener after the scan's completion handler has ended the execution.
  // The scan scheduler also runs bitrate scans, which the test does not count.
  private AbstractSchedulerListener keyframePacketScanListener() {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete executionComplete) {
        if (KeyframePacketScanTask.NAME.equals(
            executionComplete.getExecution().taskInstance.getTaskName())) {
          keyframePacketScansEnded.release();
        }
      }
    };
  }

  private void awaitKeyframePacketScansEnded(int scans) throws InterruptedException {
    assertThat(keyframePacketScansEnded.tryAcquire(scans, TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("%d more keyframe packet scans ended", scans)
        .isTrue();
  }

  @TestConfiguration(proxyBeanMethods = false)
  @Import({UpgradeDatabaseConfiguration.class, UpgradeProgress.class})
  static class UpgradeConfiguration {

    @Bean
    ProbeUpgradeFixture upgradeFixture() throws IOException {
      return ProbeUpgradeFixture.create(List.of(VERSION_8));
    }

    @Bean
    FlywayMigrationStrategy storeVersion8Results(ProbeUpgradeFixture fixture) {
      return flyway -> {
        flyway.migrate();
        fixture.seed(
            flyway.getConfiguration().getDataSource(),
            ProbeVersion9KeyframePacketScanUpgradeIT::seedResult);
      };
    }
  }

  // The version 8 probe result and its keyframe packet scan go through the repository's own
  // writes, so they are stored exactly as a version 8 worker's replies stored them: keyframe
  // positions that a scan read, and no stream end.
  private static void seedResult(
      Connection connection, ProbeUpgradeFixture.UpgradeFile file, SourceFileSnapshot snapshot) {
    var repository =
        new MediaFileContainerInfoRepositoryCustomImpl(DSL.using(connection, SQLDialect.POSTGRES));
    assertThat(
            repository.trySaveProbeRequest(
                file.mediaFileId(), new ProbeInputs(snapshot, file.probeVersion())))
        .isTrue();
    assertThat(
            repository.publish(
                ProbePublication.builder()
                    .mediaFileId(file.mediaFileId())
                    .snapshot(snapshot)
                    .probeVersion(file.probeVersion())
                    .outcome(packetScanRequired().build())
                    .build()))
        .isTrue();
    assertThat(
            repository.publishKeyframePacketScan(
                KeyframePacketScanPublication.builder()
                    .mediaFileId(file.mediaFileId())
                    .snapshot(snapshot)
                    .probeVersion(file.probeVersion())
                    .keyframes(SCANNED_KEYFRAMES)
                    .build()))
        .isTrue();
  }
}
