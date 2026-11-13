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
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.SortValue;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.HibernateWorkCounter;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Continue Watching Resolver Integration Tests")
class ContinueWatchingResolverIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private PositionCursorCodec cursors;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private HibernateWorkCounter hibernateWork;

  private static final String SEASONS_AND_SERIES_QUERY =
      "{ continueWatching(first: 12) { edges { node { ... on Episode { id season"
          + " { seasonNumber series { title } } } } } } }";

  private static final String SERIES_LIBRARIES_QUERY =
      "{ continueWatching(first: 12) { edges { node { ... on Episode { id season"
          + " { series { library { name } } } } } } } }";

  private static final SortValue WATCHED_AT =
      new SortValue.OfInstant(Instant.parse("2026-10-19T15:07:00Z"));

  private AuthTestSupport.TestIdentity identity;

  @AfterEach
  void deleteIdentity() {
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @ParameterizedTest
  @MethodSource("positionsOutsideContinueWatching")
  @DisplayName("Should reject a cursor when its position cannot belong to Continue Watching")
  void shouldRejectACursorWhenItsPositionCannotBelongToContinueWatching(SortValue value)
      throws Exception {
    identity = authTestSupport.createIdentity();
    var list = new ListIdentity("continue-watching", identity.profile().getId());

    assertContinuingIsInvalid(cursorAt(list, value));
  }

  static Stream<SortValue> positionsOutsideContinueWatching() {
    return Stream.of(
        new SortValue.Absent(),
        new SortValue.OfText("2026-10-19T15:07:00Z"),
        new SortValue.OfNumber(1));
  }

  @Test
  @DisplayName("Should reject a cursor when it continues another list")
  void shouldRejectACursorWhenItContinuesAnotherList() throws Exception {
    identity = authTestSupport.createIdentity();
    var list = new ListIdentity("library-items", identity.profile().getId());

    assertContinuingIsInvalid(cursorAt(list, WATCHED_AT));
  }

  @Test
  @DisplayName("Should reject a cursor when it continues another Profile's list")
  void shouldRejectACursorWhenItContinuesAnotherProfilesList() throws Exception {
    identity = authTestSupport.createIdentity();
    var list = new ListIdentity("continue-watching", UUID.randomUUID());

    assertContinuingIsInvalid(cursorAt(list, WATCHED_AT));
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
  @DisplayName(
      "Should resolve each episode's own season and series when the list spans seasons and series")
  void shouldResolveEachEpisodesOwnSeasonAndSeriesWhenTheListSpansSeasonsAndSeries()
      throws Exception {
    identity = authTestSupport.createIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var drama = createSeries(library, "Drama");
    var comedy = createSeries(library, "Comedy");
    var expected =
        Map.of(
            createEpisodeWithProgress(createSeason(drama, 1)).getId().toString(),
            "Drama 1",
            createEpisodeWithProgress(createSeason(drama, 2)).getId().toString(),
            "Drama 2",
            createEpisodeWithProgress(createSeason(comedy, 1)).getId().toString(),
            "Comedy 1");

    var edges = execute(SEASONS_AND_SERIES_QUERY).at("/data/continueWatching/edges");

    var resolved =
        edges
            .valueStream()
            .map(edge -> edge.path("node"))
            .collect(
                Collectors.toMap(
                    node -> node.path("id").asString(),
                    node ->
                        node.at("/season/series/title").asString()
                            + " "
                            + node.at("/season/seasonNumber").asInt()));
    assertThat(resolved).isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "Should read seasons and series in the same number of statements when the list spans more"
          + " seasons and series")
  void shouldReadSeasonsAndSeriesInTheSameNumberOfStatementsWhenTheListSpansMoreSeasonsAndSeries()
      throws Exception {
    identity = authTestSupport.createIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var drama = createSeries(library, "Drama");
    createEpisodeWithProgress(createSeason(drama, 1));
    // The first request fills caches that later requests skip.
    execute(SEASONS_AND_SERIES_QUERY);
    var forOneEpisode = statementsPreparedBy(SEASONS_AND_SERIES_QUERY);

    createEpisodeWithProgress(createSeason(drama, 2));
    createEpisodeWithProgress(createSeason(createSeries(library, "Comedy"), 1));

    assertThat(statementsPreparedBy(SEASONS_AND_SERIES_QUERY)).isEqualTo(forOneEpisode);
  }

  @Test
  @DisplayName("Should load no library when continue watching selects no library")
  void shouldLoadNoLibraryWhenContinueWatchingSelectsNoLibrary() throws Exception {
    identity = authTestSupport.createIdentity();
    createEpisodeWithProgress();

    var librariesLoaded =
        hibernateWork
            .countAs(identity, () -> execute(SEASONS_AND_SERIES_QUERY))
            .loaded(Library.class);

    assertThat(librariesLoaded).as("libraries loaded while Continue Watching resolves").isZero();
  }

  @Test
  @DisplayName(
      "Should read series libraries in the same number of statements when the list spans more"
          + " libraries")
  void shouldReadSeriesLibrariesInTheSameNumberOfStatementsWhenTheListSpansMoreLibraries()
      throws Exception {
    identity = authTestSupport.createIdentity();
    createEpisodeWithProgress();
    // The first request fills caches that later requests skip.
    execute(SERIES_LIBRARIES_QUERY);
    var forOneLibrary = statementsPreparedBy(SERIES_LIBRARIES_QUERY);

    createEpisodeWithProgress();
    createEpisodeWithProgress();

    assertThat(statementsPreparedBy(SERIES_LIBRARIES_QUERY)).isEqualTo(forOneLibrary);
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
    Map.of(
            earlier, "2026-10-19T10:00:00.123100Z",
            later, "2026-10-19T10:00:00.123900Z",
            tied, "2026-10-19T10:00:00.123900Z")
        .forEach(
            (episode, at) ->
                auditColumns.overwriteLastModifiedOn(
                    SESSION_PROGRESS,
                    SESSION_PROGRESS.MEDIA_FILE_ID.eq(episode.getFiles().iterator().next().getId()),
                    Instant.parse(at)));

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

  private String cursorAt(ListIdentity list, SortValue value) {
    return cursors.encode(list, new Position(value, UUID.randomUUID())).getValue();
  }

  private void assertContinuingIsInvalid(String cursor) throws Exception {
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

  private long statementsPreparedBy(String query) throws Exception {
    return hibernateWork.countAs(identity, () -> execute(query)).preparedStatements();
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
    var series = createSeries(library, "Test Series");
    return createEpisodeWithProgress(createSeason(series, 1));
  }

  private Episode createEpisodeWithProgress(Season season) {
    var library = season.getLibrary();
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

  private Series createSeries(Library library, String title) {
    return seriesRepository.saveAndFlush(
        Series.builder().title(title).titleSort(title).library(library).build());
  }

  private Season createSeason(Series series, int seasonNumber) {
    return seasonRepository.saveAndFlush(
        Season.builder()
            .title("Season " + seasonNumber)
            .seasonNumber(seasonNumber)
            .series(series)
            .library(series.getLibrary())
            .build());
  }
}
