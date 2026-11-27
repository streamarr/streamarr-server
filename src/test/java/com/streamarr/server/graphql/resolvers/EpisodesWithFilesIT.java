package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag("IntegrationTest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EpisodesWithFilesIT extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport auth;
  @Autowired private LibraryRepository libraries;
  @Autowired private SeriesRepository series;
  @Autowired private SeasonRepository seasons;
  @Autowired private EpisodeRepository episodes;
  @Autowired private MediaFileRepository files;

  private AuthTestSupport.TestIdentity identity;

  @BeforeAll
  void createIdentity() {
    identity = auth.createIdentity();
  }

  @AfterAll
  void deleteIdentity() {
    auth.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should list only episodes with files and seasons containing them")
  void shouldListOnlyEpisodesWithFilesAndSeasonsContainingThem() throws Exception {
    var fixture = createSeries();
    addFile(fixture.available());

    query(
            """
            { series(id: "%s") { seasons { id episodes { id files { id } } } }
              season(id: "%s") { episodes { id } } }
            """
                .formatted(fixture.series().getId(), fixture.season().getId()))
        .andExpect(
            jsonPath("$.data.series.seasons[*].id", contains(fixture.season().getId().toString())))
        .andExpect(
            jsonPath(
                "$.data.series.seasons[0].episodes[*].id",
                contains(fixture.available().getId().toString())))
        .andExpect(jsonPath("$.data.series.seasons[0].episodes[0].files.length()").value(2))
        .andExpect(
            jsonPath(
                "$.data.season.episodes[*].id", contains(fixture.available().getId().toString())));
  }

  @Test
  @DisplayName("Should complete a season and show when every episode with files is watched")
  void shouldCompleteASeasonAndShowWhenEveryEpisodeWithFilesIsWatched() throws Exception {
    var fixture = createSeries();
    mark("markWatched", fixture.available().getId());

    query(
            """
        { season(id: "%s") { selectedProfileState { watchState { watchStatus } } }
          series(id: "%s") { selectedProfileState { watchState { watchStatus } } }
          library(id: "%s") { items(first: 10, filter: {watchStatus: WATCHED}) {
            edges { node { ... on Series { id } } } } } }
        """
                .formatted(
                    fixture.season().getId(), fixture.series().getId(), fixture.library().getId()))
        .andExpect(
            jsonPath("$.data.season.selectedProfileState.watchState.watchStatus").value("WATCHED"))
        .andExpect(
            jsonPath("$.data.series.selectedProfileState.watchState.watchStatus").value("WATCHED"))
        .andExpect(
            jsonPath(
                "$.data.library.items.edges[*].node.id",
                contains(fixture.series().getId().toString())));
  }

  @ParameterizedTest
  @ValueSource(strings = {"season", "series"})
  @DisplayName("Should mark only episodes with files when marking a season or show")
  void shouldMarkOnlyEpisodesWithFilesWhenMarkingASeasonOrShow(String kind) throws Exception {
    var fixture = createSeries();
    var targetId = kind.equals("season") ? fixture.season().getId() : fixture.series().getId();
    mark("markWatched", targetId);
    expectEpisodes(fixture, "WATCHED", "UNWATCHED");

    mark("markWatched", fixture.missing().getId());
    mark("markUnwatched", targetId);
    expectEpisodes(fixture, "UNWATCHED", "WATCHED");
  }

  @Test
  @DisplayName("Should ignore hidden episode history in aggregate state and library filters")
  void shouldIgnoreHiddenEpisodeHistoryInAggregateStateAndLibraryFilters() throws Exception {
    var fixture = createSeries();
    mark("markWatched", fixture.missing().getId());

    query(
            """
        { season(id: "%s") { selectedProfileState { watchState { watchStatus lastWatchedAt } } }
          series(id: "%s") { selectedProfileState { watchState { watchStatus lastWatchedAt } } }
          library(id: "%s") { items(first: 10, filter: {watchStatus: UNWATCHED}) {
            edges { node { ... on Series { id } } } } } }
        """
                .formatted(
                    fixture.season().getId(), fixture.series().getId(), fixture.library().getId()))
        .andExpect(
            jsonPath("$.data.season.selectedProfileState.watchState.lastWatchedAt").isEmpty())
        .andExpect(
            jsonPath("$.data.series.selectedProfileState.watchState.lastWatchedAt").isEmpty())
        .andExpect(
            jsonPath("$.data.season.selectedProfileState.watchState.watchStatus")
                .value("UNWATCHED"))
        .andExpect(
            jsonPath("$.data.series.selectedProfileState.watchState.watchStatus")
                .value("UNWATCHED"))
        .andExpect(
            jsonPath(
                "$.data.library.items.edges[*].node.id",
                contains(fixture.series().getId().toString())));
  }

  @Test
  @DisplayName("Should remove an episode from lists and aggregates after its last file is removed")
  void shouldRemoveAnEpisodeFromListsAndAggregatesAfterItsLastFileIsRemoved() throws Exception {
    var fixture = createSeries();
    mark("markWatched", fixture.available().getId());
    files.deleteAll(files.findByMediaId(fixture.available().getId()));
    files.flush();

    query(
            """
        { season(id: "%s") { episodes { id } selectedProfileState { watchState { watchStatus lastWatchedAt watchProgress { positionSeconds } } } }
          series(id: "%s") { seasons { id } selectedProfileState { watchState { watchStatus lastWatchedAt } } } }
        """
                .formatted(fixture.season().getId(), fixture.series().getId()))
        .andExpect(jsonPath("$.data.season.episodes").isEmpty())
        .andExpect(jsonPath("$.data.series.seasons").isEmpty())
        .andExpect(
            jsonPath("$.data.season.selectedProfileState.watchState.watchStatus")
                .value("UNWATCHED"))
        .andExpect(
            jsonPath("$.data.season.selectedProfileState.watchState.lastWatchedAt").isEmpty())
        .andExpect(
            jsonPath("$.data.season.selectedProfileState.watchState.watchProgress").isEmpty())
        .andExpect(
            jsonPath("$.data.series.selectedProfileState.watchState.watchStatus")
                .value("UNWATCHED"))
        .andExpect(
            jsonPath("$.data.series.selectedProfileState.watchState.lastWatchedAt").isEmpty());

    mark("markUnwatched", fixture.series().getId());
    expectEpisodes(fixture, "WATCHED", "UNWATCHED");
    addFile(fixture.available());
    query(
            """
        { season(id: "%s") { episodes { id } selectedProfileState { watchState { watchStatus } } } }
        """
                .formatted(fixture.season().getId()))
        .andExpect(
            jsonPath(
                "$.data.season.episodes[*].id", contains(fixture.available().getId().toString())))
        .andExpect(
            jsonPath("$.data.season.selectedProfileState.watchState.watchStatus").value("WATCHED"));
  }

  private void expectEpisodes(Fixture fixture, String availableStatus, String missingStatus)
      throws Exception {
    query(
            """
        { available: episode(id: "%s") { selectedProfileState { watchState { watchStatus } } }
          missing: episode(id: "%s") { selectedProfileState { watchState { watchStatus } } } }
        """
                .formatted(fixture.available().getId(), fixture.missing().getId()))
        .andExpect(
            jsonPath("$.data.available.selectedProfileState.watchState.watchStatus")
                .value(availableStatus))
        .andExpect(
            jsonPath("$.data.missing.selectedProfileState.watchState.watchStatus")
                .value(missingStatus));
  }

  private void mark(String mutation, UUID id) throws Exception {
    query(
            "mutation { %s(input: {itemId: \"%s\"}) { userErrors { __typename } } }"
                .formatted(mutation, id))
        .andExpect(jsonPath("$.data.%s.userErrors", mutation).isEmpty());
  }

  private ResultActions query(String document) throws Exception {
    return mockMvc
        .perform(graphqlRequest(auth.profileBearer(identity), document))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  private Fixture createSeries() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var show =
        series.saveAndFlush(
            Series.builder()
                .title("Partial show")
                .titleSort("Partial show")
                .library(library)
                .build());
    var season =
        seasons.saveAndFlush(
            Season.builder().library(library).series(show).seasonNumber(1).build());
    var empty =
        seasons.saveAndFlush(
            Season.builder().library(library).series(show).seasonNumber(2).build());
    var available =
        episodes.saveAndFlush(
            Episode.builder()
                .library(library)
                .season(season)
                .episodeNumber(1)
                .title("Available")
                .build());
    var missing =
        episodes.saveAndFlush(
            Episode.builder()
                .library(library)
                .season(season)
                .episodeNumber(2)
                .title("Without files")
                .build());
    episodes.saveAndFlush(
        Episode.builder()
            .library(library)
            .season(empty)
            .episodeNumber(1)
            .title("Empty season")
            .build());
    addFile(available);
    return new Fixture(library, show, season, available, missing);
  }

  private void addFile(Episode episode) {
    var name = UUID.randomUUID() + ".mkv";
    files.saveAndFlush(
        MediaFile.builder()
            .mediaId(episode.getId())
            .libraryId(episode.getSeason().getSeries().getLibrary().getId())
            .filename(name)
            .filepathUri("file:///media/" + name)
            .status(MediaFileStatus.MATCHED)
            .size(1024)
            .build());
  }

  private record Fixture(
      Library library, Series series, Season season, Episode available, Episode missing) {}
}
