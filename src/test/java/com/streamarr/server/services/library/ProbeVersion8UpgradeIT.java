package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepositoryCustomImpl;
import com.streamarr.server.services.streaming.FfprobeService;
import java.io.IOException;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;

@Tag("IntegrationTest")
@DisplayName("Probe Version 8 Upgrade Integration Tests")
@Import(ProbeVersion8UpgradeIT.UpgradeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProbeVersion8UpgradeIT extends AbstractProbeUpgradeIntegrationTest {

  private static final int VERSION_7 =
      ProbeVersion.FIRST_TO_REPORT_MAXIMUM_FRAME_RATE_AND_RPU_FACTS - 1;
  // Version 8 adds the chosen video stream's maximum frame rate. A version 7 result has none.
  private static final int VERSION_8 =
      ProbeVersion.FIRST_TO_REPORT_MAXIMUM_FRAME_RATE_AND_RPU_FACTS;
  private static final Fraction VERSION_8_MAXIMUM_FRAME_RATE = new Fraction(60, 1);

  private static final FakeFfprobeService PROBE = new FakeFfprobeService();
  private static final CountDownLatch RELEASE_LATER_PROBES = new CountDownLatch(1);
  private static final AtomicInteger STARTED_PROBES = new AtomicInteger();

  @TestBean FfprobeService ffprobeService;

  // The first probe stores its result at once and every later probe waits, so the test observes
  // one file on its version 8 result while the other still has only its version 7 result.
  static FfprobeService ffprobeService() {
    PROBE.answerWith(probeOutcome(Optional.of(VERSION_8_MAXIMUM_FRAME_RATE)));
    PROBE.runDuringProbe(
        () -> {
          if (STARTED_PROBES.incrementAndGet() > 1) {
            awaitRelease();
          }
        });
    return PROBE;
  }

  private static void awaitRelease() {
    try {
      assertThat(RELEASE_LATER_PROBES.await(30, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private static ProbeOutcome.Success probeOutcome(Optional<Fraction> maximumFrameRate) {
    var probe = completeProbe();
    var video =
        retainedVideo(probe.streams().getFirst()).maximumFrameRate(maximumFrameRate).build();
    return ProbeOutcome.Success.builder()
        .container(probe.container())
        .streams(List.of(video))
        .keyframes(probe.keyframes())
        .chosenVideoStream(probe.chosenVideoStream())
        .streamTitlesOmitted(Optional.of(false))
        .build();
  }

  @Test
  @DisplayName(
      "Should play each file from its version 7 result until its version 8 result is stored when the server starts after upgrading")
  void
      shouldPlayEachFileFromItsVersion7ResultUntilItsVersion8ResultIsStoredWhenTheServerStartsAfterUpgrading()
          throws InterruptedException {
    var mediaFileIds = fixture.mediaFileIds();
    // Nothing signals that the startup scan has requested its probes, so the test polls for them.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(outcomes.findProbeStates(mediaFileIds))
                    .hasSize(2)
                    .allSatisfy(
                        state -> {
                          assertThat(requestedVersion(state)).contains(VERSION_8);
                          assertThat(storedVersion(state)).contains(VERSION_7);
                        }));
    assertThat(jdbc.queryForList("SELECT kind::text FROM library_operation", String.class))
        .containsExactly("SCAN", "SCAN");
    assertThat(jdbc.queryForList("SELECT created_by FROM library_operation", UUID.class))
        .containsExactly(SystemActor.ID, SystemActor.ID);
    assertThat(PROBE.probeCount()).isZero();
    mediaFileIds.forEach(
        mediaFileId ->
            assertThat(playedMaximumFrameRate(mediaFileId)).as("the version 7 result").isEmpty());

    var scheduler = startProbeScheduler();
    try {
      progress.awaitProbeAttemptsEnded(1);
      var states = outcomes.findProbeStates(mediaFileIds);
      var reprobed = fileStoredAt(states, VERSION_8);
      var waiting = fileStoredAt(states, VERSION_7);
      assertThat(playedMaximumFrameRate(reprobed))
          .as("the re-probed file's version 8 result")
          .contains(VERSION_8_MAXIMUM_FRAME_RATE);
      assertThat(playedMaximumFrameRate(waiting))
          .as("the waiting file's version 7 result")
          .isEmpty();

      RELEASE_LATER_PROBES.countDown();
      progress.awaitProbeAttemptsEnded(1);
      assertThat(outcomes.findProbeStates(mediaFileIds))
          .allSatisfy(state -> assertThat(storedVersion(state)).contains(VERSION_8));
      mediaFileIds.forEach(
          mediaFileId ->
              assertThat(playedMaximumFrameRate(mediaFileId))
                  .contains(VERSION_8_MAXIMUM_FRAME_RATE));
      assertThat(PROBE.probeCount()).isEqualTo(2);
      progress.awaitScansEnded(mediaFileIds.size());
      assertThat(jdbc.queryForList("SELECT status::text FROM library", String.class))
          .containsExactly("HEALTHY", "HEALTHY");
    } finally {
      RELEASE_LATER_PROBES.countDown();
      scheduler.stop();
    }
  }

  private static Optional<Integer> requestedVersion(ProbeState state) {
    return state.requested().map(ProbeInputs::probeVersion);
  }

  private static Optional<Integer> storedVersion(ProbeState state) {
    return state.stored().map(stored -> stored.inputs().probeVersion());
  }

  // Either file's probe can run first, so the test finds each file by the version it stores.
  private static UUID fileStoredAt(List<ProbeState> states, int probeVersion) {
    var files =
        states.stream()
            .filter(state -> storedVersion(state).equals(Optional.of(probeVersion)))
            .map(ProbeState::mediaFileId)
            .toList();
    assertThat(files).as("the files stored at probe version %d", probeVersion).hasSize(1);
    return files.getFirst();
  }

  private Optional<Fraction> playedMaximumFrameRate(UUID mediaFileId) {
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).build();
    var session =
        accepted(
            streaming.createSession(
                createStreamSessionCommand(mediaFileId, UUID.randomUUID(), options)));
    try {
      return session.getMediaProbe().streams().stream()
          .filter(stream -> "video".equals(stream.codecType()))
          .findFirst()
          .flatMap(StreamInfo::maximumFrameRate);
    } finally {
      streaming.destroySession(session.getSessionId());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @Import({UpgradeDatabaseConfiguration.class, UpgradeProgress.class})
  static class UpgradeConfiguration {

    @Bean
    ProbeUpgradeFixture upgradeFixture() throws IOException {
      return ProbeUpgradeFixture.create(List.of(VERSION_7, VERSION_7));
    }

    @Bean
    FlywayMigrationStrategy storeVersion7Results(ProbeUpgradeFixture fixture) {
      return flyway -> {
        flyway.migrate();
        fixture.seed(flyway.getConfiguration().getDataSource(), ProbeVersion8UpgradeIT::seedResult);
      };
    }
  }

  // The version 7 results go through the repository's own request and publication writes, so they
  // are stored exactly as a version 7 probe stored them.
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
                    .outcome(probeOutcome(Optional.empty()))
                    .build()))
        .isTrue();
  }
}
