package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.streamarr.server.AbstractIntegrationTest;
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
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.util.FileSystemUtils;

@Tag("IntegrationTest")
@DisplayName("Probe Version 4 Upgrade Integration Tests")
@Import(ProbeVersion4UpgradeIT.UpgradeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProbeVersion4UpgradeIT extends AbstractIntegrationTest {

  private static final FakeFfprobeService PROBE = new FakeFfprobeService();

  @Autowired private UpgradeFixture fixture;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Scheduler scheduler;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private StreamingService streaming;
  @Autowired private HlsPlaylistService playlists;

  @TestBean FfprobeService ffprobeService;
  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean SegmentStore segmentStore;
  @TestBean PlaybackAuthorityGate authorityGate;

  static FfprobeService ffprobeService() {
    PROBE.answerWith(retainedProbe());
    return PROBE;
  }

  private static ProbeOutcome.Success retainedProbe() {
    var probe =
        ProbeFixture.completeProbe(
            defaultProbeBuilder()
                .duration(Duration.ofSeconds(12))
                .framerate(OptionalDouble.of(24))
                .keyframes(
                    ProbeFixture.keyframesEvery(Duration.ofSeconds(6), Duration.ofSeconds(12)))
                .build());
    var sourceVideo = probe.streams().getFirst();
    var video =
        StreamInfo.builder()
            .index(sourceVideo.index())
            .codecType(sourceVideo.codecType())
            .codec(sourceVideo.codec())
            .width(sourceVideo.width())
            .height(sourceVideo.height())
            .framerate(sourceVideo.framerate())
            .dynamicRange(sourceVideo.dynamicRange())
            .bitDepth(sourceVideo.bitDepth())
            .chromaSubsampling(sourceVideo.chromaSubsampling())
            .codecString(sourceVideo.codecString())
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
        ProbeFixture.audioStreamBuilder(sourceAudio.codec().orElseThrow())
            .index(sourceAudio.index())
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

  static TranscodeExecutor transcodeExecutor() {
    return new FakeTranscodeExecutor();
  }

  static SegmentStore segmentStore() {
    return new FakeSegmentStore();
  }

  static PlaybackAuthorityGate authorityGate() {
    return (_, _) -> true;
  }

  @Test
  @DisplayName("Should re-probe and play every old result when the server starts after upgrading")
  void shouldReprobeAndPlayEveryOldResultWhenTheServerStartsAfterUpgrading() {
    var mediaFileIds = fixture.files().stream().map(UpgradeFile::mediaFileId).toList();
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
                                  inputs -> assertThat(inputs.probeVersion()).isEqualTo(4));
                        }));
    assertThat(jdbc.queryForList("SELECT kind::text FROM library_operation", String.class))
        .containsExactly("SCAN", "SCAN");
    assertThat(jdbc.queryForList("SELECT created_by FROM library_operation", UUID.class))
        .containsExactly(SystemActor.ID, SystemActor.ID);
    assertThat(PROBE.probeCount()).isZero();

    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();
    for (var file : fixture.files()) {
      assertThat(
              streaming.createSession(
                  createStreamSessionCommand(file.mediaFileId(), UUID.randomUUID(), options)))
          .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.ProbeNotReady()));
    }

    scheduler.start();
    try {
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(outcomes.findProbeStates(mediaFileIds))
                      .allSatisfy(
                          state ->
                              assertThat(state.stored())
                                  .hasValueSatisfying(
                                      stored -> {
                                        assertThat(stored.inputs().probeVersion())
                                            .isEqualTo(ProbeVersion.CURRENT);
                                        assertThat(stored.error()).isEmpty();
                                      })));
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
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(jdbc.queryForList("SELECT status::text FROM library", String.class))
                      .containsExactly("HEALTHY", "HEALTHY"));
    } finally {
      scheduler.stop();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class UpgradeConfiguration {

    @Bean
    UpgradeDatabase upgradeDatabase(JdbcConnectionDetails connectionDetails) throws SQLException {
      return UpgradeDatabase.create(connectionDetails);
    }

    @Bean
    @FlywayDataSource
    DataSource dataSource(UpgradeDatabase database) {
      return database.dataSource();
    }

    @Bean
    UpgradeFixture upgradeFixture() throws IOException {
      return UpgradeFixture.create();
    }

    @Bean
    FlywayMigrationStrategy upgradeFromStoredResults(UpgradeFixture fixture) {
      return flyway -> {
        Flyway.configure().configuration(flyway.getConfiguration()).target("96").load().migrate();
        fixture.seed(flyway.getConfiguration().getDataSource());
        flyway.migrate();
        var migratedDatabase = new JdbcTemplate(flyway.getConfiguration().getDataSource());
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

  private record UpgradeFile(UUID mediaFileId, int probeVersion, Path source) {}

  private record UpgradeFixture(Path root, List<UpgradeFile> files) implements AutoCloseable {

    private static UpgradeFixture create() throws IOException {
      var root = Files.createTempDirectory("probe-version-4-upgrade-");
      var files = new ArrayList<UpgradeFile>();
      for (var version : List.of(1, 3)) {
        var library = Files.createDirectory(root.resolve("version-" + version));
        var source = Files.writeString(library.resolve("Movie (2024).mkv"), "media");
        files.add(new UpgradeFile(UUID.randomUUID(), version, source));
      }

      return new UpgradeFixture(root, List.copyOf(files));
    }

    private void seed(DataSource dataSource) {
      try (var connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try (var actor =
            connection.prepareStatement("SELECT set_config('streamarr.actor_id', ?, true)")) {
          actor.setString(1, SystemActor.ID.toString());
          actor.execute();
        }

        for (var file : files) {
          seedFile(connection, file);
        }

        connection.commit();
      } catch (SQLException | IOException e) {
        throw new AssertionError("Could not store the pre-upgrade probe results", e);
      }

      assertThat(
              new JdbcTemplate(dataSource)
                  .queryForList(
                      "SELECT probe_version FROM media_file_container_info", Integer.class))
          .containsExactlyInAnyOrder(1, 3);
    }

    private static void seedFile(Connection connection, UpgradeFile file)
        throws SQLException, IOException {
      var libraryId = UUID.randomUUID();
      try (var insert =
          connection.prepareStatement(
              """
          INSERT INTO library (id, filepath_uri, name, status, backend, type)
          VALUES (?, ?, 'Upgrade library', 'HEALTHY', 'LOCAL', 'MOVIE')
          """)) {
        insert.setObject(1, libraryId);
        insert.setString(2, FilepathCodec.encode(file.source().getParent()));
        insert.executeUpdate();
      }

      var snapshot =
          new SourceFileSnapshot(
              Files.size(file.source()), Files.getLastModifiedTime(file.source()).toInstant());
      try (var insert =
          connection.prepareStatement(
              """
          INSERT INTO media_file (id, library_id, filepath_uri, filename, size, status)
          VALUES (?, ?, ?, ?, ?, 'MATCHED')
          """)) {
        insert.setObject(1, file.mediaFileId());
        insert.setObject(2, libraryId);
        insert.setString(3, FilepathCodec.encode(file.source()));
        insert.setString(4, file.source().getFileName().toString());
        insert.setLong(5, snapshot.size());
        insert.executeUpdate();
      }

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

    @Override
    public void close() throws IOException {
      FileSystemUtils.deleteRecursively(root);
    }
  }

  // A separate database gives this startup the real V096 schema without changing the shared
  // container's other test databases or relying on hand-built copies of historical tables.
  private record UpgradeDatabase(
      JdbcConnectionDetails connectionDetails, String name, HikariDataSource dataSource)
      implements AutoCloseable {

    private static UpgradeDatabase create(JdbcConnectionDetails connectionDetails)
        throws SQLException {
      var name = "probe_upgrade_" + UUID.randomUUID().toString().replace("-", "");
      try (var connection = connect(connectionDetails);
          var statement = connection.createStatement()) {
        statement.execute("CREATE DATABASE " + name);
      }

      var config = new HikariConfig();
      var url = connectionDetails.getJdbcUrl();
      var query = url.indexOf('?');
      var suffix = query < 0 ? "" : url.substring(query);
      config.setJdbcUrl(url.substring(0, url.lastIndexOf('/') + 1) + name + suffix);
      config.setUsername(connectionDetails.getUsername());
      config.setPassword(connectionDetails.getPassword());
      config.setMaximumPoolSize(10);
      config.setMinimumIdle(0);
      return new UpgradeDatabase(connectionDetails, name, new HikariDataSource(config));
    }

    private static Connection connect(JdbcConnectionDetails details) throws SQLException {
      return DriverManager.getConnection(
          details.getJdbcUrl(), details.getUsername(), details.getPassword());
    }

    @Override
    public void close() throws SQLException {
      dataSource.close();
      try (var connection = connect(connectionDetails);
          var statement = connection.createStatement()) {
        statement.execute("DROP DATABASE " + name + " WITH (FORCE)");
      }
    }
  }
}
