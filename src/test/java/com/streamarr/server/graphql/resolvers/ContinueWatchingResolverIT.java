package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Continue Watching Resolver Integration Tests")
class ContinueWatchingResolverIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private AuditColumnFixtures auditColumns;

  private AuthTestSupport.TestIdentity identity;

  @AfterEach
  void deleteIdentity() {
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should resolve episode season details when continue watching returns an episode")
  void shouldResolveEpisodeSeasonDetailsWhenContinueWatchingReturnsAnEpisode() throws Exception {
    identity = authTestSupport.createIdentity();
    var episode = createEpisodeWithProgress();

    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"query": "{ continueWatching(first: 1) { ... on Episode { title season \
                    { seasonNumber series { title } } } } }"}""")
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.continueWatching[0].title").value(episode.getTitle()))
        .andExpect(jsonPath("$.data.continueWatching[0].season.seasonNumber").value(1))
        .andExpect(jsonPath("$.data.continueWatching[0].season.series.title").value("Test Series"));
  }

  @Test
  @DisplayName("Should include placement when an episode belongs to Continue Watching")
  void shouldIncludePlacementWhenAnEpisodeBelongsToContinueWatching() throws Exception {
    identity = authTestSupport.createIdentity();
    createEpisodeWithProgress();

    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"query": "{ continueWatching(first: 12) { ... on Episode { \
                    continueWatchingPosition { matchesFilter orderKey } } } }"}""")
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.continueWatching[0].continueWatchingPosition.matchesFilter")
                .value(true))
        .andExpect(
            jsonPath("$.data.continueWatching[0].continueWatchingPosition.orderKey").isNotEmpty());
  }

  @Test
  @DisplayName(
      "Should return ascending keys when database ordering includes microseconds and tied instants")
  void shouldReturnAscendingKeysWhenDatabaseOrderingIncludesMicrosecondsAndTiedInstants()
      throws Exception {
    identity = authTestSupport.createIdentity();
    var earlier = createEpisodeWithProgress();
    var later = createEpisodeWithProgress();
    var tied = createEpisodeWithProgress();
    for (var episode : List.of(earlier, later, tied)) {
      var at = episode == earlier ? "2026-10-19T10:00:00.123100Z" : "2026-10-19T10:00:00.123900Z";
      auditColumns.overwriteLastModifiedOn(
          SESSION_PROGRESS,
          SESSION_PROGRESS.MEDIA_FILE_ID.eq(episode.getFiles().iterator().next().getId()),
          Instant.parse(at));
    }

    var response =
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
            {"query": "{ continueWatching(first: 12) { ... on Episode { id \
            continueWatchingPosition { matchesFilter orderKey } } } }"}""")
                    .with(bearer(authTestSupport.profileBearer(identity))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    var entries = new ObjectMapper().readTree(response).path("data").path("continueWatching");
    assertThat(entries.get(2).path("id").asString()).isEqualTo(earlier.getId().toString());
    var keys =
        entries
            .valueStream()
            .map(entry -> entry.path("continueWatchingPosition").path("orderKey").asString())
            .toList();
    assertThat(keys).hasSize(3).doesNotHaveDuplicates().isSorted();
  }

  private Episode createEpisodeWithProgress() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series = createSeries(library);
    var season = createSeason(library, series);

    var file =
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("pilot.mkv")
            .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
            .build();

    var episode =
        episodeRepository.saveAndFlush(
            Episode.builder()
                .episodeNumber(1)
                .title("Pilot")
                .season(season)
                .library(library)
                .files(Set.of(file))
                .build());

    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .profileId(identity.profile().getId())
            .mediaFileId(episode.getFiles().iterator().next().getId())
            .positionSeconds(900)
            .percentComplete(25.0)
            .durationSeconds(3600)
            .build());

    return episode;
  }

  private Series createSeries(Library library) {
    return seriesRepository.saveAndFlush(
        Series.builder().title("Test Series").titleSort("Test Series").library(library).build());
  }

  private Season createSeason(Library library, Series series) {
    return seasonRepository.saveAndFlush(
        Season.builder().title("Season 1").seasonNumber(1).series(series).library(library).build());
  }
}
