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
  private static final Instant FIRST_SEASON_STARTED_AT = Instant.parse("2026-04-01T20:00:00Z");
  private static final Instant FIRST_SEASON_LATEST_AT = Instant.parse("2026-04-02T20:00:00Z");
  private static final Instant SECOND_SEASON_LATEST_AT = Instant.parse("2026-04-09T20:00:00Z");
  private static final String LAST_WATCHED_AT =
      "selectedProfileState { watchState { lastWatchedAt } }";
  private static final String MOVIE_LAST_WATCHED_AT =
      "$.data.movie.selectedProfileState.watchState.lastWatchedAt";

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

    query(identity, "{ movie(id: \\\"" + movie.getId() + "\\\") { " + LAST_WATCHED_AT + " } }")
        .andExpect(jsonPath(MOVIE_LAST_WATCHED_AT).value(FINISHED_AT.toString()));
  }

  @Test
  @DisplayName("Should return the episode time for the episode, its season and its series")
  void shouldReturnTheEpisodeTimeForTheEpisodeItsSeasonAndItsSeries() throws Exception {
    identity = authTestSupport.createIdentity();
    var episode = createEpisode();
    saveProgress(identity, episode, PAUSED_AT);

    query(
            identity,
            "{ series(id: \\\""
                + episode.getSeason().getSeries().getId()
                + "\\\") { %1$s seasons { %1$s episodes { %1$s } } } }".formatted(LAST_WATCHED_AT))
        .andExpect(
            jsonPath("$.data.series.selectedProfileState.watchState.lastWatchedAt")
                .value(PAUSED_AT.toString()))
        .andExpect(
            jsonPath("$.data.series.seasons[0].selectedProfileState.watchState.lastWatchedAt")
                .value(PAUSED_AT.toString()))
        .andExpect(
            jsonPath(
                    "$.data.series.seasons[0].episodes[0].selectedProfileState.watchState"
                        + ".lastWatchedAt")
                .value(PAUSED_AT.toString()));
  }

  @Test
  @DisplayName(
      "Should return each season's latest episode time and the series' overall latest time when"
          + " the seasons were watched at different times")
  void shouldReturnEachSeasonsLatestTimeAndTheSeriesLatestTimeWhenSeasonsWereWatchedApart()
      throws Exception {
    identity = authTestSupport.createIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series = createSeries(library);
    var seasonOne = createSeason(series, 1);
    var seasonTwo = createSeason(series, 2);
    saveProgress(identity, createEpisode(seasonOne, 1), FIRST_SEASON_STARTED_AT);
    saveCompletion(identity, createEpisode(seasonOne, 2), FIRST_SEASON_LATEST_AT);
    saveProgress(identity, createEpisode(seasonTwo, 1), SECOND_SEASON_LATEST_AT);

    query(
            identity,
            "{ series(id: \\\""
                + series.getId()
                + "\\\") { %1$s seasons { seasonNumber %1$s } } }".formatted(LAST_WATCHED_AT))
        .andExpect(
            jsonPath("$.data.series.selectedProfileState.watchState.lastWatchedAt")
                .value(SECOND_SEASON_LATEST_AT.toString()))
        .andExpect(jsonPath("$.data.series.seasons[0].seasonNumber").value(1))
        .andExpect(
            jsonPath("$.data.series.seasons[0].selectedProfileState.watchState.lastWatchedAt")
                .value(FIRST_SEASON_LATEST_AT.toString()))
        .andExpect(jsonPath("$.data.series.seasons[1].seasonNumber").value(2))
        .andExpect(
            jsonPath("$.data.series.seasons[1].selectedProfileState.watchState.lastWatchedAt")
                .value(SECOND_SEASON_LATEST_AT.toString()));
  }

  @Test
  @DisplayName("Should return null when the Profile has not watched the movie")
  void shouldReturnNullWhenTheProfileHasNotWatchedTheMovie() throws Exception {
    identity = authTestSupport.createIdentity();
    var movie = createMovie();

    query(identity, "{ movie(id: \\\"" + movie.getId() + "\\\") { " + LAST_WATCHED_AT + " } }")
        .andExpect(jsonPath(MOVIE_LAST_WATCHED_AT).isEmpty());
  }

  @Test
  @DisplayName("Should return null when only another Profile watched the movie")
  void shouldReturnNullWhenOnlyAnotherProfileWatchedTheMovie() throws Exception {
    identity = authTestSupport.createIdentity();
    otherIdentity = authTestSupport.createIdentity();
    var movie = createMovie();
    saveCompletion(otherIdentity, movie, FINISHED_AT);

    query(identity, "{ movie(id: \\\"" + movie.getId() + "\\\") { " + LAST_WATCHED_AT + " } }")
        .andExpect(jsonPath(MOVIE_LAST_WATCHED_AT).isEmpty());
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
    return createEpisode(createSeason(createSeries(library), 1), 1);
  }

  private Series createSeries(Library library) {
    return seriesRepository.saveAndFlush(
        Series.builder()
            .title("Last Watched Series")
            .titleSort("Last Watched Series")
            .library(library)
            .build());
  }

  private Season createSeason(Series series, int seasonNumber) {
    return seasonRepository.saveAndFlush(
        Season.builder()
            .seasonNumber(seasonNumber)
            .series(series)
            .library(series.getLibrary())
            .build());
  }

  private Episode createEpisode(Season season, int episodeNumber) {
    return episodeRepository.saveAndFlush(
        Episode.builder()
            .episodeNumber(episodeNumber)
            .title("Episode " + episodeNumber)
            .season(season)
            .library(season.getLibrary())
            .files(Set.of(buildFile(season.getLibrary())))
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
