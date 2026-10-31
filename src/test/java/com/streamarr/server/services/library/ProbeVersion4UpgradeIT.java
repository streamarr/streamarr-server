package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.domain.streaming.ChromaticityCoordinates;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MasteringDisplayColorVolume;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;

@Tag("IntegrationTest")
@DisplayName("Probe Version 4 Upgrade Integration Tests")
@Import(ProbeVersion4UpgradeIT.UpgradeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProbeVersion4UpgradeIT extends AbstractProbeUpgradeIntegrationTest {

  private static final FakeFfprobeService PROBE = new FakeFfprobeService();

  @Autowired private HlsPlaylistService playlists;

  @TestBean FfprobeService ffprobeService;

  static FfprobeService ffprobeService() {
    PROBE.answerWith(retainedProbe());
    return PROBE;
  }

  private static ProbeOutcome.Success retainedProbe() {
    var probe = completeProbe();
    var video =
        retainedVideo(probe.streams().getFirst())
            .endTime(Optional.of(Duration.ofSeconds(12, 123_456_789)))
            .masteringDisplay(
                Optional.of(
                    MasteringDisplayColorVolume.builder()
                        .red(new ChromaticityCoordinates(35_400, 14_600))
                        .green(new ChromaticityCoordinates(8_500, 39_850))
                        .blue(new ChromaticityCoordinates(6_550, 2_300))
                        .whitePoint(new ChromaticityCoordinates(15_635, 16_450))
                        .maxLuminance(10_000_000L)
                        .minLuminance(1L)
                        .build()))
            .contentLightLevel(Optional.of(new ContentLightLevel(1_000, 400)))
            .build();
    var sourceAudio = probe.streams().get(1);
    var audio =
        StreamInfo.builder()
            .index(sourceAudio.index())
            .codecType(sourceAudio.codecType())
            .codec(sourceAudio.codec())
            .codecString(sourceAudio.codecString())
            .audioProfile(sourceAudio.audioProfile())
            .sampleRate(sourceAudio.sampleRate())
            .channels(sourceAudio.channels())
            .bitrate(sourceAudio.bitrate())
            .endTime(Optional.of(Duration.ofSeconds(11, 999_999_999)))
            .title(Optional.of("English – 音声"))
            .handlerName(Optional.of(""))
            .hearingImpaired(Optional.of(true))
            .build();
    return ProbeOutcome.Success.builder()
        .container(probe.container())
        .streams(List.of(video, audio))
        .keyframes(probe.keyframes())
        .chosenVideoStream(probe.chosenVideoStream())
        .streamTitlesOmitted(Optional.of(false))
        .build();
  }

  @Test
  @DisplayName("Should re-probe and play every old result when the server starts after upgrading")
  void shouldReprobeAndPlayEveryOldResultWhenTheServerStartsAfterUpgrading()
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
                          assertThat(state.stored()).isEmpty();
                          assertThat(state.requested())
                              .hasValueSatisfying(
                                  inputs ->
                                      assertThat(inputs.probeVersion())
                                          .isEqualTo(ProbeVersion.CURRENT));
                        }));
    assertThat(jdbc.queryForList("SELECT kind::text FROM library_operation", String.class))
        .containsExactly("SCAN", "SCAN");
    assertThat(jdbc.queryForList("SELECT created_by FROM library_operation", UUID.class))
        .containsExactly(SystemActor.ID, SystemActor.ID);
    assertThat(PROBE.probeCount()).isZero();

    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).build();
    for (var file : fixture.files()) {
      assertThat(
              streaming.createSession(
                  createStreamSessionCommand(file.mediaFileId(), UUID.randomUUID(), options)))
          .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.ProbeNotReady()));
    }

    var scheduler = startProbeScheduler();
    try {
      progress.awaitProbeAttemptsEnded(mediaFileIds.size());
      assertThat(outcomes.findProbeStates(mediaFileIds))
          .allSatisfy(
              state ->
                  assertThat(state.stored())
                      .hasValueSatisfying(
                          stored -> {
                            assertThat(stored.inputs().probeVersion())
                                .isEqualTo(ProbeVersion.CURRENT);
                            assertThat(stored.error()).isEmpty();
                          }));
      for (var file : fixture.files()) {
        var stored = outcomes.findByMediaFileId(file.mediaFileId()).orElseThrow();
        assertThat(
                stored.getOutcome(
                    KeyframeKnowledge.NotReported::new, ChapterKnowledge.NotReported::new))
            .isInstanceOfSatisfying(
                ProbeOutcome.Success.class,
                success -> {
                  assertThat(success.streams()).isEqualTo(retainedProbe().streams());
                  assertThat(success.streamTitlesOmitted()).contains(false);
                });
        var session =
            accepted(
                streaming.createSession(
                    createStreamSessionCommand(file.mediaFileId(), UUID.randomUUID(), options)));
        try {
          assertThat(session.getMediaProbe().videoPlaybackFacts())
              .hasValueSatisfying(
                  facts ->
                      assertThat(facts.frameRateRule().outputFrameRate())
                          .contains(new Fraction(24, 1)));
          assertThat(playlists.generateMultivariantPlaylist(session, "token"))
              .contains("CODECS=\"avc1.640028,mp4a.40.2\"");
        } finally {
          streaming.destroySession(session.getSessionId());
        }
      }

      assertThat(PROBE.probeCount()).isEqualTo(2);
      progress.awaitScansEnded(mediaFileIds.size());
      assertThat(jdbc.queryForList("SELECT status::text FROM library", String.class))
          .containsExactly("HEALTHY", "HEALTHY");
    } finally {
      scheduler.stop();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @Import({UpgradeDatabaseConfiguration.class, UpgradeProgress.class})
  static class UpgradeConfiguration {

    @Bean
    ProbeUpgradeFixture upgradeFixture() throws IOException {
      return ProbeUpgradeFixture.create(List.of(1, 3));
    }

    @Bean
    FlywayMigrationStrategy upgradeFromStoredResults(ProbeUpgradeFixture fixture) {
      return flyway -> {
        Flyway.configure().configuration(flyway.getConfiguration()).target("96").load().migrate();
        var dataSource = flyway.getConfiguration().getDataSource();
        fixture.seed(dataSource, ProbeVersion4UpgradeIT::seedResult);
        assertThat(
                new JdbcTemplate(dataSource)
                    .queryForList(
                        "SELECT probe_version FROM media_file_container_info", Integer.class))
            .containsExactlyInAnyOrder(1, 3);
        flyway.migrate();
        var migratedDatabase = new JdbcTemplate(dataSource);
        assertThat(
                migratedDatabase.queryForList(
                    "SELECT probe_version FROM media_file_container_info"))
            .isEmpty();
        assertThat(migratedDatabase.queryForList("SELECT stream_index FROM media_file_stream_info"))
            .isEmpty();
        assertThat(
                migratedDatabase.queryForList(
                    "SELECT probe_version FROM media_file_probe_task_request", Integer.class))
            .containsExactlyInAnyOrder(1, 3);
      };
    }
  }

  private static void seedResult(
      Connection connection, ProbeUpgradeFixture.UpgradeFile file, SourceFileSnapshot snapshot)
      throws SQLException {
    try (var insert =
        connection.prepareStatement(
            """
        INSERT INTO media_file_container_info
          (media_file_id, source_size, source_modified_epoch_second, source_modified_nanos,
           probe_version, format, duration_seconds, duration_nanos, total_bitrate)
        VALUES (?, ?, ?, ?, ?, 'matroska', 12, 0, 5000000)
        """)) {
      insert.setObject(1, file.mediaFileId());
      insert.setLong(2, snapshot.size());
      insert.setLong(3, snapshot.modifiedAt().getEpochSecond());
      insert.setInt(4, snapshot.modifiedAt().getNano());
      insert.setInt(5, file.probeVersion());
      insert.executeUpdate();
    }

    try (var insert =
        connection.prepareStatement(
            """
        INSERT INTO media_file_stream_info (media_file_id, stream_index, codec_type, codec)
        VALUES (?, 0, 'video', 'h264')
        """)) {
      insert.setObject(1, file.mediaFileId());
      insert.executeUpdate();
    }
  }
}
