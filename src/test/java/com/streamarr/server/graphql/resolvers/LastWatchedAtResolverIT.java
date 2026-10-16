package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag("IntegrationTest")
@DisplayName("Last Watched At Resolver Integration Tests")
class LastWatchedAtResolverIT extends AbstractIntegrationTest {

  private static final Instant PAUSED_AT = Instant.parse("2026-05-01T20:00:00Z");
  private static final Instant FINISHED_AT = Instant.parse("2026-05-01T21:30:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private AuditColumnFixtures auditColumns;

  private AuthTestSupport.TestIdentity identity;
  private AuthTestSupport.TestIdentity otherIdentity;

  @AfterEach
  void deleteIdentities() {
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }

    if (otherIdentity != null) {
      authTestSupport.deleteIdentity(otherIdentity);
    }
  }

  @Test
  @DisplayName("Should return the completion time when the Profile finished the movie")
  void shouldReturnTheCompletionTimeWhenTheProfileFinishedTheMovie() throws Exception {
    identity = authTestSupport.createIdentity();
    var movie = createMovie();
    saveProgress(identity, movie, PAUSED_AT);
    saveCompletion(identity, movie, FINISHED_AT);

    query(identity, "{ movie(id: \\\"" + movie.getId() + "\\\") { lastWatchedAt } }")
        .andExpect(jsonPath("$.data.movie.lastWatchedAt").value(FINISHED_AT.toString()));
  }

  @Test
  @DisplayName("Should return the episode time for the episode and its series")
  void shouldReturnTheEpisodeTimeForTheEpisodeAndItsSeries() throws Exception {
    identity = authTestSupport.createIdentity();
    var episode = createEpisode();
    saveProgress(identity, episode, PAUSED_AT);

    query(
            identity,
            "{ series(id: \\\""
                + episode.getSeason().getSeries().getId()
                + "\\\") { lastWatchedAt seasons { episodes { lastWatchedAt } } } }")
        .andExpect(jsonPath("$.data.series.lastWatchedAt").value(PAUSED_AT.toString()))
        .andExpect(
            jsonPath("$.data.series.seasons[0].episodes[0].lastWatchedAt")
                .value(PAUSED_AT.toString()));
  }

  @Test
  @DisplayName("Should return null when the Profile has not watched the movie")
  void shouldReturnNullWhenTheProfileHasNotWatchedTheMovie() throws Exception {
    identity = authTestSupport.createIdentity();
    var movie = createMovie();

    query(identity, "{ movie(id: \\\"" + movie.getId() + "\\\") { lastWatchedAt } }")
        .andExpect(jsonPath("$.data.movie.lastWatchedAt").isEmpty());
  }

  @Test
  @DisplayName("Should return null when only another Profile watched the movie")
  void shouldReturnNullWhenOnlyAnotherProfileWatchedTheMovie() throws Exception {
    identity = authTestSupport.createIdentity();
    otherIdentity = authTestSupport.createIdentity();
    var movie = createMovie();
    saveCompletion(otherIdentity, movie, FINISHED_AT);

    query(identity, "{ movie(id: \\\"" + movie.getId() + "\\\") { lastWatchedAt } }")
        .andExpect(jsonPath("$.data.movie.lastWatchedAt").isEmpty());
  }

  private ResultActions query(AuthTestSupport.TestIdentity caller, String query) throws Exception {
    return mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\": \"" + query + "\"}")
                .with(bearer(authTestSupport.profileBearer(caller))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  private void saveProgress(
      AuthTestSupport.TestIdentity owner, BaseCollectable<?> item, Instant lastModifiedOn) {
    var sessionId = UUID.randomUUID();
    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(sessionId)
            .profileId(owner.profile().getId())
            .mediaFileId(item.getFiles().iterator().next().getId())
            .positionSeconds(900)
            .percentComplete(25.0)
            .durationSeconds(3600)
            .build());
    auditColumns.overwriteLastModifiedOn(
        SESSION_PROGRESS, SESSION_PROGRESS.SESSION_ID.eq(sessionId), lastModifiedOn);
  }

  private void saveCompletion(
      AuthTestSupport.TestIdentity owner, BaseCollectable<?> item, Instant watchedAt) {
    watchHistoryRepository.saveAndFlush(
        WatchHistory.builder()
            .profileId(owner.profile().getId())
            .collectableId(item.getId())
            .watchedAt(watchedAt)
            .durationSeconds(3600)
            .build());
  }

  private Movie createMovie() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return movieRepository.saveAndFlush(
        Movie.builder()
            .title("Last Watched Movie")
            .titleSort("Last Watched Movie")
            .files(Set.of(buildFile(library)))
            .library(library)
            .build());
  }

  private Episode createEpisode() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Last Watched Series")
                .titleSort("Last Watched Series")
                .library(library)
                .build());
    var season =
        seasonRepository.saveAndFlush(
            Season.builder().seasonNumber(1).series(series).library(library).build());
    return episodeRepository.saveAndFlush(
        Episode.builder()
            .episodeNumber(1)
            .title("Pilot")
            .season(season)
            .library(library)
            .files(Set.of(buildFile(library)))
            .build());
  }

  private static MediaFile buildFile(Library library) {
    return MediaFile.builder()
        .libraryId(library.getId())
        .status(MediaFileStatus.MATCHED)
        .filename("last-watched-" + UUID.randomUUID() + ".mkv")
        .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
        .build();
  }
}
