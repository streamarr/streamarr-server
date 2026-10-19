package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.MetadataFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.ArtworkRequestService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.events.library.LibraryItemChangedEvent;
import com.streamarr.server.services.library.LibraryContentsService;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("IntegrationTest")
class LibraryItemPlacementIT extends AbstractIntegrationTest {
  @LocalServerPort private int port;
  @Autowired private AuthTestSupport auth;
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasons;
  @Autowired private EpisodeRepository episodes;
  @Autowired private MovieService movieService;
  @Autowired private ArtworkRequestService artwork;
  @Autowired private MeterRegistry metrics;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private WatchHistoryRepository history;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private TransactionTemplate transactions;
  @Autowired private LibraryContentsService contents;

  private static final Instant WHOLE_MINUTE = Instant.parse("2026-10-19T15:07:00Z");

  @ParameterizedTest
  @CsvSource({
    "MOVIE,ADDED,ASC", "MOVIE,ADDED,DESC",
    "MOVIE,LAST_WATCHED,ASC", "MOVIE,LAST_WATCHED,DESC",
    "SERIES,ADDED,ASC", "SERIES,ADDED,DESC",
    "SERIES,LAST_WATCHED,ASC", "SERIES,LAST_WATCHED,DESC"
  })
  @DisplayName("Should preserve query and event placement when a timestamp falls on a whole minute")
  void shouldPreserveQueryAndEventPlacementWhenTimestampFallsOnWholeMinute(
      MediaType kind, OrderMediaBy by, SortDirection direction) {
    var identity = auth.createIdentity();
    var bearer = auth.profileBearer(identity);
    var change = timestampItem(kind, identity.profile().getId());
    var arguments =
        "sort: {by: %s, direction: %s}, filter: {unmatched: true}".formatted(by, direction);
    try (var http =
            new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)));
        var socket = GraphQlTransportWsTestClient.connectWithBearer(port, bearer)) {
      var query =
          http.execute(
              bearer,
              """
          { library(id: "%s") { items(first: 10, %s) {
            edges { orderKey node { ... on Movie { id } ... on Series { id } } }
          } } }
          """
                  .formatted(change.libraryId(), arguments));
      assertThat(query.has("errors")).as(query.toPrettyString()).isFalse();
      assertThat(query.at("/data/library/items/edges").size()).isEqualTo(1);
      socket.subscribe(
          "minute",
          """
          subscription { libraryItemUpdated(libraryId: "%s") {
            itemId edge(%s) { cursor orderKey node { ... on Movie { id } ... on Series { id } } }
          } }
          """
              .formatted(change.libraryId(), arguments));
      new ActiveSubscriptions(metrics).awaitCount("libraryItemUpdated", 1);
      transactions.executeWithoutResult(_ -> events.publishEvent(change));
      var message = socket.nextMessageFor("minute");
      assertThat(message.at("/payload").has("errors")).as(message.toPrettyString()).isFalse();
      assertThat(message.at("/payload/data/libraryItemUpdated/itemId").asString())
          .isEqualTo(change.itemId().toString());
      assertThat(message.at("/payload/data/libraryItemUpdated/edge/node/id").asString())
          .isEqualTo(change.itemId().toString());
      assertThat(message.at("/payload/data/libraryItemUpdated/edge/orderKey").asString())
          .isEqualTo(query.at("/data/library/items/edges/0/orderKey").asString());
    } finally {
      new ActiveSubscriptions(metrics).awaitNoneActive();
      auth.deleteIdentity(identity);
    }
  }

  @ParameterizedTest
  @CsvSource({"MOVIE,ADDED", "MOVIE,LAST_WATCHED", "SERIES,ADDED", "SERIES,LAST_WATCHED"})
  @DisplayName("Should expose Instants when locating timestamp-sorted Library items")
  void shouldExposeInstantsWhenLocatingTimestampSortedLibraryItems(
      MediaType kind, OrderMediaBy by) {
    var identity = auth.createIdentity();
    try {
      var change = timestampItem(kind, identity.profile().getId());
      var filter =
          MediaFilter.builder()
              .libraryId(change.libraryId())
              .profileId(identity.profile().getId())
              .sortBy(by)
              .build();
      assertThat(contents.findMatchingItem(change, filter).orElseThrow().sortValue())
          .isEqualTo(WHOLE_MINUTE);
    } finally {
      auth.deleteIdentity(identity);
    }
  }

  private LibraryItemChangedEvent timestampItem(MediaType kind, UUID profileId) {
    var library =
        libraries.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder().type(kind).build());
    BaseCollectable<?> item =
        switch (kind) {
          case MOVIE ->
              movies.saveAndFlush(Movie.builder().title("Whole minute").library(library).build());
          case SERIES ->
              seriesRepository.saveAndFlush(
                  Series.builder().title("Whole minute").library(library).build());
          default -> throw new IllegalArgumentException("Expected a Library item");
        };
    var watchedId =
        switch (item) {
          case Movie movie -> movie.getId();
          case Series series ->
              episodes
                  .saveAndFlush(
                      Episode.builder()
                          .title("Episode")
                          .episodeNumber(1)
                          .library(library)
                          .season(
                              seasons.saveAndFlush(
                                  Season.builder()
                                      .seasonNumber(1)
                                      .series(series)
                                      .library(library)
                                      .build()))
                          .build())
                  .getId();
          default -> throw new IllegalArgumentException("Expected a Library item");
        };
    history.saveAndFlush(
        WatchHistory.builder()
            .profileId(profileId)
            .collectableId(watchedId)
            .watchedAt(WHOLE_MINUTE)
            .build());
    auditColumns.overwriteCreatedOn(
        BASE_COLLECTABLE, BASE_COLLECTABLE.ID.eq(item.getId()), WHOLE_MINUTE);
    return LibraryItemChangedEvent.builder()
        .change(LibraryItemChangedEvent.Change.UPDATED)
        .libraryId(library.getId())
        .itemId(item.getId())
        .kind(kind)
        .build();
  }

  @Test
  @DisplayName("Should share query edges while retaining an item excluded from another view")
  void shouldSupplyTheSameOrderKeyAsAQueryAndACursorUsableForTheNextPage() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movies.saveAndFlush(Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    var changed =
        movies.saveAndFlush(
            Movie.builder().title("Charlie").titleSort("Charlie").library(library).build());
    var last =
        movies.saveAndFlush(
            Movie.builder().title("Delta").titleSort("Delta").library(library).build());
    var identity = auth.createIdentity();
    var bearer = auth.profileBearer(identity);
    try (var http =
            new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)));
        var socket = GraphQlTransportWsTestClient.connectWithBearer(port, bearer)) {
      var before =
          http.execute(
              bearer,
              """
          { library(id: "%s") { items(first: 1, sort: {by: TITLE, direction: ASC}, filter: {unmatched: true}) {
            edges { cursor orderKey node { ... on Movie { id } } }
          } } }
          """
                  .formatted(library.getId()));
      assertThat(before.has("errors")).as(before.toPrettyString()).isFalse();
      var firstKey = before.at("/data/library/items/edges/0/orderKey").asString();
      socket.subscribe(
          "placement",
          """
          subscription { libraryItemUpdated(libraryId: "%s") {
            itemId item { ... on Movie { title } }
            edge(sort: {by: TITLE, direction: ASC}, filter: {unmatched: true}) {
              cursor orderKey node { ... on Movie { id title } }
            }
            excluded: edge(filter: {years: [1900]}) { cursor orderKey node { ... on Movie { id title } } }
          } }
          """
              .formatted(library.getId()));
      new ActiveSubscriptions(metrics).awaitCount("libraryItemUpdated", 1);
      try (var run = artwork.openRun("Placement test", ImageRefreshMode.PRESERVE)) {
        movieService.refreshMovieMetadata(
            changed,
            MetadataFixture.<Movie>metadataResultBuilder()
                .entity(Movie.builder().title("Bravo").titleSort("Bravo").build())
                .build(),
            run);
      }

      var message = socket.nextMessageFor("placement");
      assertThat(message.path("payload").has("errors")).as(message.toPrettyString()).isFalse();
      var update = message.at("/payload/data/libraryItemUpdated");
      var edge = update.path("edge");
      assertThat(edge.at("/node/id").asString()).isEqualTo(changed.getId().toString());
      assertThat(edge.at("/node/title").asString()).isEqualTo("Bravo");
      assertThat(update.at("/item/title").asString()).isEqualTo("Bravo");
      assertThat(edge.path("orderKey").asString()).isGreaterThan(firstKey);
      assertThat(update.path("excluded").isNull()).isTrue();
      var after =
          http.execute(
              bearer,
              """
          { library(id: "%s") { items(first: 1, after: "%s", sort: {by: TITLE, direction: ASC}, filter: {unmatched: true}) {
            edges { cursor orderKey node { ... on Movie { id } } }
          } } }
          """
                  .formatted(library.getId(), edge.path("cursor").asString()));
      assertThat(after.has("errors")).as(after.toPrettyString()).isFalse();
      assertThat(after.at("/data/library/items/edges/0/node/id").asString())
          .isEqualTo(last.getId().toString());
      assertThat(after.at("/data/library/items/edges/0/orderKey").asString())
          .isGreaterThan(edge.path("orderKey").asString());
      var all =
          http.execute(
              bearer,
              """
          { library(id: "%s") { items(first: 10, sort: {by: TITLE, direction: ASC}, filter: {unmatched: true}) {
            edges { orderKey node { ... on Movie { id } } }
          } } }
          """
                  .formatted(library.getId()));
      assertThat(all.at("/data/library/items/edges/1/orderKey").asString())
          .isEqualTo(edge.path("orderKey").asString());
    } finally {
      new ActiveSubscriptions(metrics).awaitNoneActive();
      auth.deleteIdentity(identity);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "TITLE,ASC",
    "TITLE,DESC",
    "ADDED,ASC",
    "ADDED,DESC",
    "RELEASE_DATE,ASC",
    "RELEASE_DATE,DESC",
    "RUNTIME,ASC",
    "RUNTIME,DESC",
    "LAST_WATCHED,ASC",
    "LAST_WATCHED,DESC"
  })
  void shouldKeepOrderKeysAndCursorsConsistentForEverySort(
      OrderMediaBy by, SortDirection direction) {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var created = new ArrayList<Movie>();
    var titles = new String[] {"a", "A", "aé", "ä", null};
    for (int i = 0; i < titles.length; i++) {
      created.add(
          movies.saveAndFlush(
              Movie.builder()
                  .title(titles[i])
                  .titleSort(titles[i])
                  .runtime(i % 2 == 0 ? null : 90)
                  .releaseDate(i % 2 == 0 ? null : LocalDate.of(2020, 1, 1))
                  .library(library)
                  .build()));
    }

    var identity = auth.createIdentity();
    var bearer = auth.profileBearer(identity);
    try (var http =
        new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)))) {
      for (var movie : List.of(created.getFirst(), created.get(2))) {
        var marked =
            http.execute(
                bearer,
                "mutation { markWatched(input: {itemId: \"%s\"}) { userErrors { __typename } } }"
                    .formatted(movie.getId()));
        assertThat(marked.at("/data/markWatched/userErrors").isEmpty())
            .as(marked.toPrettyString())
            .isTrue();
      }

      var query =
          """
          { library(id: "%s") { items(%%s, sort: {by: %s, direction: %s}) {
            edges { cursor orderKey node { ... on Movie { id } } }
            pageInfo { startCursor endCursor hasNextPage hasPreviousPage }
          } } }
          """
              .formatted(library.getId(), by.name(), direction.name());
      var full = http.execute(bearer, query.formatted("first: 10"));
      assertThat(full.has("errors")).as(full.toPrettyString()).isFalse();
      assertPageCursorsMatchEdges(full.at("/data/library/items"));
      var edges = full.at("/data/library/items/edges");
      var keys = edges.valueStream().map(edge -> edge.path("orderKey").asString()).toList();
      assertThat(keys).hasSize(5).doesNotHaveDuplicates().isSorted();
      var ids = edges.valueStream().map(edge -> edge.at("/node/id").asString()).toList();
      for (int i = 0; i < edges.size(); i++) {
        var cursor = edges.get(i).path("cursor").asString();
        var next =
            http.execute(bearer, query.formatted("first: 10, after: \"%s\"".formatted(cursor)));
        assertThat(next.has("errors")).as(next.toPrettyString()).isFalse();
        assertPageCursorsMatchEdges(next.at("/data/library/items"));
        assertThat(next.at("/data/library/items/pageInfo/hasNextPage").asBoolean()).isFalse();
        assertThat(
                next.at("/data/library/items/edges")
                    .valueStream()
                    .map(edge -> edge.at("/node/id").asString())
                    .toList())
            .as("items after %s under %s %s", i, by, direction)
            .isEqualTo(ids.subList(i + 1, ids.size()));
        var previous =
            http.execute(bearer, query.formatted("last: 10, before: \"%s\"".formatted(cursor)));
        assertThat(previous.has("errors")).as(previous.toPrettyString()).isFalse();
        assertPageCursorsMatchEdges(previous.at("/data/library/items"));
        assertThat(previous.at("/data/library/items/pageInfo/hasPreviousPage").asBoolean())
            .isFalse();
        assertThat(
                previous
                    .at("/data/library/items/edges")
                    .valueStream()
                    .map(edge -> edge.at("/node/id").asString())
                    .toList())
            .as("items before %s under %s %s", i, by, direction)
            .isEqualTo(ids.subList(0, i));
      }
    } finally {
      auth.deleteIdentity(identity);
    }
  }

  private void assertPageCursorsMatchEdges(JsonNode connection) {
    var edges = connection.path("edges");
    var pageInfo = connection.path("pageInfo");
    if (edges.isEmpty()) {
      assertThat(pageInfo.path("startCursor").isNull()).isTrue();
      assertThat(pageInfo.path("endCursor").isNull()).isTrue();
      return;
    }

    assertThat(pageInfo.path("startCursor")).isEqualTo(edges.get(0).path("cursor"));
    assertThat(pageInfo.path("endCursor")).isEqualTo(edges.get(edges.size() - 1).path("cursor"));
  }

  @Test
  void shouldPlaceTheParentSeriesFromTheSelectedProfilesEpisodeWatchState() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder().title("Series").titleSort("Series").library(library).build());
    var season =
        seasons.saveAndFlush(
            Season.builder().seasonNumber(1).series(series).library(library).build());
    var episode =
        episodes.saveAndFlush(
            Episode.builder()
                .episodeNumber(1)
                .title("Pilot")
                .season(season)
                .library(library)
                .files(
                    Set.of(
                        MediaFile.builder()
                            .libraryId(library.getId())
                            .status(MediaFileStatus.MATCHED)
                            .filename("pilot.mkv")
                            .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
                            .build()))
                .build());
    var identity = auth.createIdentity();
    var bearer = auth.profileBearer(identity);
    try (var http =
            new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)));
        var socket = GraphQlTransportWsTestClient.connectWithBearer(port, bearer)) {
      socket.subscribe(
          "series",
          """
          subscription { libraryItemUpdated(libraryId: "%s") {
            itemId kind item { ... on Series { watchStatus lastWatchedAt } }
            edge(sort: {by: LAST_WATCHED, direction: DESC}, filter: {watchStatus: WATCHED}) {
              cursor orderKey node { ... on Series { id } }
            }
            excluded: edge(filter: {watchStatus: UNWATCHED}) { cursor }
          } }
          """
              .formatted(library.getId()));
      new ActiveSubscriptions(metrics).awaitCount("libraryItemUpdated", 1);
      var marked =
          http.execute(
              bearer,
              "mutation { markWatched(input: {itemId: \"%s\"}) { userErrors { __typename } } }"
                  .formatted(episode.getId()));
      assertThat(marked.at("/data/markWatched/userErrors").isEmpty())
          .as(marked.toPrettyString())
          .isTrue();
      var message = socket.nextMessageFor("series");
      assertThat(message.path("payload").has("errors")).as(message.toPrettyString()).isFalse();
      var change = message.at("/payload/data/libraryItemUpdated");
      assertThat(change.path("itemId").asString()).isEqualTo(series.getId().toString());
      assertThat(change.path("kind").asString()).isEqualTo("SERIES");
      assertThat(change.at("/edge/node/id").asString()).isEqualTo(series.getId().toString());
      assertThat(change.path("excluded").isNull()).isTrue();
      assertThat(change.at("/item/watchStatus").asString()).isEqualTo("WATCHED");
      var query =
          http.execute(
              bearer,
              """
          { library(id: "%s") { items(first: 10, sort: {by: LAST_WATCHED, direction: DESC}, filter: {watchStatus: WATCHED}) {
            edges { orderKey node { ... on Series { id } } }
          } } }
          """
                  .formatted(library.getId()));
      assertThat(query.has("errors")).as(query.toPrettyString()).isFalse();
      assertThat(query.at("/data/library/items/edges/0/node/id").asString())
          .isEqualTo(series.getId().toString());
      assertThat(query.at("/data/library/items/edges/0/orderKey").asString())
          .isEqualTo(change.at("/edge/orderKey").asString());
    } finally {
      new ActiveSubscriptions(metrics).awaitNoneActive();
      auth.deleteIdentity(identity);
    }
  }
}
