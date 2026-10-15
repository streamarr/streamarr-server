package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.security.WithProfileContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Watch State Across Devices Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@WithProfileContext
class WatchStateAcrossDevicesIT extends AbstractIntegrationTest {

  private static final int PAUSED_POSITION_SECONDS = 40 * 60;
  private static final int FINISHED_POSITION_SECONDS = 7000;
  private static final int CONTINUE_WATCHING_LIMIT = 50;

  @Autowired private SessionProgressService sessionProgressService;
  @Autowired private WatchStatusService watchStatusService;
  @Autowired private ContinueWatchingService continueWatchingService;
  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;
  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private AuthTestSupport authTestSupport;

  private AuthTestSupport.TestIdentity identity;
  private UUID profileId;
  private Library library;

  @BeforeAll
  void setup() {
    identity = authTestSupport.createIdentity();
    profileId = identity.profile().getId();
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
  }

  @AfterAll
  void deleteIdentitySeed() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName(
      "Should keep the movie watched when a paused device reports its old position after another"
          + " device finished the movie")
  void shouldKeepMovieWatchedWhenPausedDeviceReportsOldPositionAfterAnotherDeviceFinishedIt() {
    var movie = createMovieWithFile();
    var deviceA = openSession(movie);
    var deviceB = openSession(movie);

    report(deviceA, PAUSED_POSITION_SECONDS, PlaybackState.PAUSED);
    report(deviceB, FINISHED_POSITION_SECONDS, PlaybackState.STOPPED);
    report(deviceA, PAUSED_POSITION_SECONDS, PlaybackState.PAUSED);

    assertThat(watchStatusOf(movie)).isEqualTo(WatchStatus.WATCHED);
    assertThat(watchProgressOf(movie)).isNull();
    assertThat(continueWatchingIds()).doesNotContain(movie.getId());
  }

  @Test
  @DisplayName(
      "Should show a rewatch in progress when the paused device plays forward after another device"
          + " finished the movie")
  void shouldShowRewatchInProgressWhenPausedDevicePlaysForwardAfterAnotherDeviceFinishedIt() {
    var movie = createMovieWithFile();
    var deviceA = openSession(movie);
    var deviceB = openSession(movie);

    report(deviceA, PAUSED_POSITION_SECONDS, PlaybackState.PAUSED);
    report(deviceB, FINISHED_POSITION_SECONDS, PlaybackState.STOPPED);
    report(deviceA, PAUSED_POSITION_SECONDS, PlaybackState.PAUSED);
    report(deviceA, PAUSED_POSITION_SECONDS + 10, PlaybackState.PLAYING);

    assertThat(watchStatusOf(movie)).isEqualTo(WatchStatus.IN_PROGRESS);
    assertThat(watchProgressOf(movie))
        .extracting(WatchProgressDto::positionSeconds)
        .isEqualTo(PAUSED_POSITION_SECONDS + 10);
    assertThat(continueWatchingIds()).contains(movie.getId());
  }

  private Movie createMovieWithFile() {
    var file =
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("movie-" + UUID.randomUUID() + ".mkv")
            .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
            .build();

    return movieRepository.saveAndFlush(
        Movie.builder()
            .title("Movie " + UUID.randomUUID())
            .files(Set.of(file))
            .library(library)
            .build());
  }

  private StreamSession openSession(Movie movie) {
    var session =
        defaultSessionBuilder()
            .mediaFileId(movie.getFiles().iterator().next().getId())
            .authority(playbackAuthorityFor(profileId))
            .build();
    runtimeRegistry.save(session);
    return session;
  }

  private void report(StreamSession session, int positionSeconds, PlaybackState state) {
    sessionProgressService.reportStreamSessionTimeline(
        profileId, session.getSessionId(), positionSeconds, state);
  }

  private WatchStatus watchStatusOf(Movie movie) {
    return watchStatusService
        .getWatchStatusForDirectMedia(profileId, List.of(movie.getId()))
        .get(movie.getId());
  }

  private WatchProgressDto watchProgressOf(Movie movie) {
    return watchStatusService
        .getProgressForDirectMedia(profileId, List.of(movie.getId()))
        .get(movie.getId());
  }

  private List<UUID> continueWatchingIds() {
    return continueWatchingService.getContinueWatching(profileId, CONTINUE_WATCHING_LIMIT).stream()
        .map(BaseCollectable::getId)
        .toList();
  }
}
