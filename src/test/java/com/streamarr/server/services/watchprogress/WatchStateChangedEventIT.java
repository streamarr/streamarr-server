package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.watchprogress.events.WatchStateChangedEvent;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.security.WithProfileContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Watch-state writes against real PostgreSQL publish one change per committed write, and a rolled
 * back write publishes nothing.
 */
@Tag("IntegrationTest")
@DisplayName("Watch State Changed Event Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@WithProfileContext
@Import(WatchStateChangedEventIT.CommittedWatchStateChanges.class)
class WatchStateChangedEventIT extends AbstractIntegrationTest {

  private static final int PAUSED_POSITION_SECONDS = 40 * 60;
  private static final int FINISHED_POSITION_SECONDS = 7000;

  @Autowired private SessionProgressService sessionProgressService;
  @Autowired private WatchStatusService watchStatusService;
  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;
  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private CommittedWatchStateChanges committed;

  private AuthTestSupport.TestIdentity identity;
  private UUID profileId;
  private Library library;

  @BeforeAll
  void setup() {
    identity = authTestSupport.createIdentity();
    profileId = identity.profile().getId();
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
  }

  @AfterEach
  void clearCommittedChanges() {
    committed.events.clear();
  }

  @AfterAll
  void deleteIdentitySeed() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should publish one committed change when a stop marks the movie watched")
  void shouldPublishOneCommittedChangeWhenAStopMarksTheMovieWatched() {
    var movie = createMovieWithFile();
    var session = openSession(movie);
    report(session, PAUSED_POSITION_SECONDS, PlaybackState.PAUSED);
    committed.events.clear();

    report(session, FINISHED_POSITION_SECONDS, PlaybackState.STOPPED);

    assertThat(committed.events)
        .containsExactly(new WatchStateChangedEvent(profileId, Set.of(movie.getId())));
  }

  @Test
  @DisplayName("Should publish nothing when a paused report repeats the stored position")
  void shouldPublishNothingWhenAPausedReportRepeatsTheStoredPosition() {
    var movie = createMovieWithFile();
    var session = openSession(movie);
    report(session, PAUSED_POSITION_SECONDS, PlaybackState.PAUSED);
    committed.events.clear();

    report(session, PAUSED_POSITION_SECONDS, PlaybackState.PAUSED);

    assertThat(committed.events).isEmpty();
  }

  @Test
  @DisplayName("Should publish nothing when the transaction rolls back after marking watched")
  void shouldPublishNothingWhenTheTransactionRollsBackAfterMarkingWatched() {
    var movie = createMovieWithFile();

    transactionTemplate.executeWithoutResult(
        status -> {
          watchStatusService.markWatched(
              profileId, movie.getId(), CollectableScope.MOVIE_OR_EPISODE, 0);
          status.setRollbackOnly();
        });

    assertThat(committed.events).isEmpty();
    assertThat(
            watchHistoryRepository.findByProfileIdAndCollectableIdIn(
                profileId, List.of(movie.getId())))
        .isEmpty();
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

  @TestConfiguration(proxyBeanMethods = false)
  static class CommittedWatchStateChanges {

    final List<WatchStateChangedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onCommitted(WatchStateChangedEvent event) {
      events.add(event);
    }
  }
}
