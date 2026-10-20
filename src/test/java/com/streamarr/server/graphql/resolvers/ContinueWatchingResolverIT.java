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
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Continue Watching Resolver Integration Tests")
class ContinueWatchingResolverIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private CursorUtil cursors;
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

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"not-a-timestamp", "2026-10-19T15:07Z"})
  @DisplayName("Should reject a malformed Continue Watching timestamp as an invalid cursor")
  void shouldRejectMalformedContinueWatchingTimestampAsInvalidCursor(String timestamp)
      throws Exception {
    identity = authTestSupport.createIdentity();
    var options =
        MediaPaginationOptions.builder()
            .mediaFilter(
                MediaFilter.builder()
                    .profileId(identity.profile().getId())
                    .sortBy(OrderMediaBy.LAST_WATCHED)
                    .sortDirection(SortDirection.DESC)
                    .build())
            .build();
    var cursor = cursors.encodeMediaCursor(options, UUID.randomUUID(), timestamp).getValue();
    var body =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "query",
                    "query($after: String) { continueWatching(first: 1, after: $after) { edges { cursor } } }",
                    "variables",
                    Map.of("after", cursor)));
    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_CURSOR"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should reject a foreign list or Profile when continuing Continue Watching")
  void shouldRejectForeignListOrProfileWhenContinuingContinueWatching(boolean otherProfile)
      throws Exception {
    identity = authTestSupport.createIdentity();
    var filter =
        MediaFilter.builder()
            .profileId(otherProfile ? UUID.randomUUID() : identity.profile().getId())
            .libraryId(otherProfile ? null : UUID.randomUUID())
            .sortBy(OrderMediaBy.LAST_WATCHED)
            .sortDirection(SortDirection.DESC)
            .build();
    var cursor =
        cursors
            .encodeMediaCursor(
                MediaPaginationOptions.builder().mediaFilter(filter).build(),
                UUID.randomUUID(),
                "2026-10-19T15:07:00Z")
            .getValue();
    var body =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "query",
                    "query($after: String) { continueWatching(first: 1, after: $after) { edges { cursor } } }",
                    "variables",
                    Map.of("after", cursor)));
    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_CURSOR"));
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
                    {"query": "{ continueWatching(first: 1) { edges { node { ... on Episode { title season \
                    { seasonNumber series { title } } } } } } }"}""")
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.continueWatching.edges[0].node.title").value(episode.getTitle()))
        .andExpect(jsonPath("$.data.continueWatching.edges[0].node.season.seasonNumber").value(1))
        .andExpect(
            jsonPath("$.data.continueWatching.edges[0].node.season.series.title")
                .value("Test Series"));
  }

  @Test
  @DisplayName("Should return a bounded connection with a cursor and order key for each item")
  void shouldReturnABoundedConnectionWithCursorAndOrderKeyForEachItem() throws Exception {
    identity = authTestSupport.createIdentity();
    createEpisodeWithProgress();
    createEpisodeWithProgress();

    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"query": "{ continueWatching(first: 1) { edges { cursor orderKey node { ... on Episode { id } } } pageInfo { hasNextPage hasPreviousPage endCursor } } }"}""")
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.continueWatching.edges.length()").value(1))
        .andExpect(jsonPath("$.data.continueWatching.edges[0].cursor").isNotEmpty())
        .andExpect(jsonPath("$.data.continueWatching.edges[0].orderKey").isNotEmpty())
        .andExpect(jsonPath("$.data.continueWatching.edges[0].node.id").isNotEmpty())
        .andExpect(jsonPath("$.data.continueWatching.pageInfo.hasNextPage").value(true))
        .andExpect(jsonPath("$.data.continueWatching.pageInfo.hasPreviousPage").value(false));
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
            {"query": "{ continueWatching(first: 12) { edges { orderKey node { ... on Episode { id } } } } }"}""")
                    .with(bearer(authTestSupport.profileBearer(identity))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    var entries = new ObjectMapper().readTree(response).at("/data/continueWatching/edges");
    assertThat(entries.get(2).at("/node/id").asString()).isEqualTo(earlier.getId().toString());
    var keys = entries.valueStream().map(entry -> entry.path("orderKey").asString()).toList();
    assertThat(keys).hasSize(3).doesNotHaveDuplicates().isSorted();
  }

  @Test
  @DisplayName(
      "Should continue across tied timestamps after the cursor item leaves Continue Watching")
  void shouldContinueAcrossTiedTimestampsAfterCursorItemLeavesContinueWatching() throws Exception {
    identity = authTestSupport.createIdentity();
    var items =
        List.of(
            createEpisodeWithProgress(), createEpisodeWithProgress(), createEpisodeWithProgress());
    for (var item : items) {
      auditColumns.overwriteLastModifiedOn(
          SESSION_PROGRESS,
          SESSION_PROGRESS.MEDIA_FILE_ID.eq(item.getFiles().iterator().next().getId()),
          Instant.parse("2026-10-19T15:07:00Z"));
    }

    var expected =
        items.stream()
            .map(item -> item.getId().toString())
            .sorted(Comparator.reverseOrder())
            .toList();
    var first =
        execute(
            "{ continueWatching(first: 1) { edges { cursor node { ... on Episode { id } } } pageInfo { hasNextPage } } }");
    assertThat(first.at("/data/continueWatching/edges/0/node/id").asString())
        .isEqualTo(expected.getFirst());
    assertThat(first.at("/data/continueWatching/pageInfo/hasNextPage").asBoolean()).isTrue();
    var cursor = first.at("/data/continueWatching/edges/0/cursor").asString();
    execute(
        "mutation { markWatched(input: { itemId: \"%s\" }) { userErrors { __typename } } }"
            .formatted(expected.getFirst()));
    var next =
        execute(
            """
        { continueWatching(first: 1, after: "%s") {
          edges { cursor node { ... on Episode { id } } }
          pageInfo { hasNextPage hasPreviousPage }
        } }
        """
                .formatted(cursor));
    assertThat(next.at("/data/continueWatching/edges/0/node/id").asString())
        .isEqualTo(expected.get(1));
    assertThat(next.at("/data/continueWatching/pageInfo/hasNextPage").asBoolean()).isTrue();
    assertThat(next.at("/data/continueWatching/pageInfo/hasPreviousPage").asBoolean()).isFalse();
  }

  @Test
  @DisplayName(
      "Should select cursor and order key through one fragment when edges come from any list")
  void shouldSelectCursorAndOrderKeyThroughOneFragmentWhenEdgesComeFromAnyList() throws Exception {
    identity = authTestSupport.createIdentity();
    var episode = createEpisodeWithProgress();

    var result =
        execute(
            """
            { continueWatching(first: 1) { edges { ...ordered } }
              library(id: "%s") { items(first: 1) { edges { ...ordered } } } }
            fragment ordered on OrderedEdge { cursor orderKey }
            """
                .formatted(episode.getLibrary().getId()));

    for (var edge :
        List.of(
            result.at("/data/continueWatching/edges/0"),
            result.at("/data/library/items/edges/0"))) {
      assertThat(edge.path("cursor").asString()).isNotEmpty();
      assertThat(edge.path("orderKey").asString()).isNotEmpty();
    }
  }

  private JsonNode execute(String query) throws Exception {
    var mapper = new ObjectMapper();
    var result =
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("query", query)))
                    .with(bearer(authTestSupport.profileBearer(identity))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(result);
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
