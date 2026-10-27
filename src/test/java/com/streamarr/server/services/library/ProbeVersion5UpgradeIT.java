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
import com.streamarr.server.domain.streaming.ColorPrimaries;
import com.streamarr.server.domain.streaming.ColorRange;
import com.streamarr.server.domain.streaming.MatrixCoefficients;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TransferCharacteristics;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.UpgradeDatabase;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepositoryCustomImpl;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
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
@DisplayName("Probe Version 5 Upgrade Integration Tests")
@Import(ProbeVersion5UpgradeIT.UpgradeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProbeVersion5UpgradeIT extends AbstractIntegrationTest {

  private static final int VERSION_4 = ProbeVersion.FIRST_TO_REPORT_PLAYBACK_FACTS;
  private static final int VERSION_5 = ProbeVersion.FIRST_TO_REPORT_FRAME_FACTS;
  private static final VideoStreamProperties VERSION_5_PROPERTIES =
      VideoStreamProperties.builder()
          .codecProfile(Optional.of(VideoCodecProfile.H264_HIGH))
          .codecLevel(OptionalInt.of(40))
          .colorPrimaries(Optional.of(ColorPrimaries.BT709))
          .transferCharacteristics(Optional.of(TransferCharacteristics.BT709))
          .matrixCoefficients(Optional.of(MatrixCoefficients.BT709))
          .colorRange(Optional.of(ColorRange.LIMITED))
          .firstFramesRead(true)
          .build();

  private static final FakeFfprobeService PROBE = new FakeFfprobeService();
  private static final CountDownLatch RELEASE_LATER_PROBES = new CountDownLatch(1);
  private static final AtomicInteger STARTED_PROBES = new AtomicInteger();

  @Autowired private UpgradeFixture fixture;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Scheduler scheduler;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private StreamingService streaming;

  @TestBean FfprobeService ffprobeService;
  @TestBean ProbeCapacitySource probeCapacitySource;
  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean SegmentStore segmentStore;
  @TestBean PlaybackAuthorityGate authorityGate;

  // The first probe stores its result at once and every later probe waits, so the test observes
  // one file on its version 5 result while the other still has only its version 4 result.
  static FfprobeService ffprobeService() {
    PROBE.answerWith(probeOutcome(Optional.of(VERSION_5_PROPERTIES)));
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

  private static ProbeOutcome.Success probeOutcome(
      Optional<VideoStreamProperties> videoProperties) {
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
            .videoProperties(videoProperties)
            .build();
    var streams = new ArrayList<>(probe.streams());
    streams.set(0, video);
    return ProbeOutcome.Success.builder()
        .container(probe.container())
        .streams(List.copyOf(streams))
        .keyframes(probe.keyframes())
        .chosenVideoStream(probe.chosenVideoStream())
        .streamTitlesOmitted(Optional.of(false))
        .build();
  }

  @Test
  @DisplayName(
      "Should re-probe every version 4 result and play each file from it until the file's version 5 result is stored")
  void shouldReprobeEveryVersion4ResultAndPlayEachFileFromItUntilTheFilesVersion5ResultIsStored() {
    var mediaFileIds = fixture.mediaFileIds();
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(outcomes.findProbeStates(mediaFileIds))
                    .hasSize(2)
                    .allSatisfy(
                        state -> {
                          assertThat(requestedVersion(state)).contains(VERSION_5);
                          assertThat(storedVersion(state)).contains(VERSION_4);
                        }));
    assertThat(jdbc.queryForList("SELECT kind::text FROM library_operation", String.class))
        .containsExactly("SCAN", "SCAN");
    assertThat(jdbc.queryForList("SELECT created_by FROM library_operation", UUID.class))
        .containsExactly(SystemActor.ID, SystemActor.ID);
    assertThat(PROBE.probeCount()).isZero();
    mediaFileIds.forEach(
        mediaFileId ->
            assertThat(playedVideoProperties(mediaFileId)).as("the version 4 result").isEmpty());

    scheduler.start();
    try {
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(outcomes.findProbeStates(mediaFileIds))
                      .filteredOn(state -> storedVersion(state).equals(Optional.of(VERSION_5)))
                      .hasSize(1));
      for (var state : outcomes.findProbeStates(mediaFileIds)) {
        var expected = Optional.<VideoStreamProperties>empty();
        if (storedVersion(state).equals(Optional.of(VERSION_5))) {
          expected = Optional.of(VERSION_5_PROPERTIES);
        }

        assertThat(playedVideoProperties(state.mediaFileId())).isEqualTo(expected);
      }

      RELEASE_LATER_PROBES.countDown();
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(outcomes.findProbeStates(mediaFileIds))
                      .allSatisfy(state -> assertThat(storedVersion(state)).contains(VERSION_5)));
      mediaFileIds.forEach(
          mediaFileId ->
              assertThat(playedVideoProperties(mediaFileId)).contains(VERSION_5_PROPERTIES));
      assertThat(PROBE.probeCount()).isEqualTo(2);
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(jdbc.queryForList("SELECT status::text FROM library", String.class))
                      .containsExactly("HEALTHY", "HEALTHY"));
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

  private Optional<VideoStreamProperties> playedVideoProperties(UUID mediaFileId) {
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();
    var session =
        accepted(
            streaming.createSession(
                createStreamSessionCommand(mediaFileId, UUID.randomUUID(), options)));
    try {
      return session
          .getMediaProbe()
          .videoPlaybackFacts()
          .flatMap(VideoPlaybackFacts::videoProperties);
    } finally {
      streaming.destroySession(session.getSessionId());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class UpgradeConfiguration {

    // A database of its own keeps the other test classes' files out of the startup re-scan.
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
    FlywayMigrationStrategy storeVersion4Results(UpgradeFixture fixture) {
      return flyway -> {
        flyway.migrate();
        fixture.seed(flyway.getConfiguration().getDataSource());
      };
    }
  }

  private record UpgradeFile(UUID mediaFileId, Path source) {}

  private record UpgradeFixture(Path root, List<UpgradeFile> files) implements AutoCloseable {

    private static UpgradeFixture create() throws IOException {
      var root = Files.createTempDirectory("probe-version-5-upgrade-");
      var files = new ArrayList<UpgradeFile>();
      for (var name : List.of("first", "second")) {
        var library = Files.createDirectory(root.resolve(name));
        var source = Files.writeString(library.resolve("Movie (2024).mkv"), "media");
        files.add(new UpgradeFile(UUID.randomUUID(), source));
      }

      return new UpgradeFixture(root, List.copyOf(files));
    }

    private List<UUID> mediaFileIds() {
      return files.stream().map(UpgradeFile::mediaFileId).toList();
    }

    // The version 4 results go through the repository's own request and publication writes, so
    // they are stored exactly as a version 4 probe stored them.
    private void seed(DataSource dataSource) {
      try (var connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try (var actor =
            connection.prepareStatement("SELECT set_config('streamarr.actor_id', ?, true)")) {
          actor.setString(1, SystemActor.ID.toString());
          actor.execute();
        }

        var repository =
            new MediaFileContainerInfoRepositoryCustomImpl(
                DSL.using(connection, SQLDialect.POSTGRES));
        for (var file : files) {
          seedFile(connection, repository, file);
        }

        connection.commit();
      } catch (SQLException | IOException e) {
        throw new AssertionError("Could not store the version 4 probe results", e);
      }
    }

    private static void seedFile(
        Connection connection,
        MediaFileContainerInfoRepositoryCustomImpl repository,
        UpgradeFile file)
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

      assertThat(
              repository.trySaveProbeRequest(
                  file.mediaFileId(), new ProbeInputs(snapshot, VERSION_4)))
          .isTrue();
      assertThat(
              repository.publish(
                  ProbePublication.builder()
                      .mediaFileId(file.mediaFileId())
                      .snapshot(snapshot)
                      .probeVersion(VERSION_4)
                      .outcome(probeOutcome(Optional.empty()))
                      .build()))
          .isTrue();
    }

    @Override
    public void close() throws IOException {
      FileSystemUtils.deleteRecursively(root);
    }
  }
}
