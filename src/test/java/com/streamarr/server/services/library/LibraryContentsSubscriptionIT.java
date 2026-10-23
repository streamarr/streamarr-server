package com.streamarr.server.services.library;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.MetadataFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.ArtworkRequestService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeasonWithEpisodesRequest;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import com.streamarr.server.support.LibraryOperationDriver;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("IntegrationTest")
class LibraryContentsSubscriptionIT extends AbstractScanningIntegrationTest {

  @LocalServerPort private int port;
  @TempDir Path directory;
  @Autowired private AuthTestSupport auth;
  @Autowired private UserAccountRepository accounts;
  @Autowired private LibraryRepository libraries;
  @Autowired private LibraryOperationDriver operations;
  @Autowired private MeterRegistry metrics;
  @Autowired private MovieRepository movies;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasons;
  @Autowired private MovieService movieService;
  @Autowired private SeriesService seriesService;
  @Autowired private ArtworkRequestService artwork;
  @Autowired private TransactionTemplate transactions;
  @Autowired private LibraryAdministrationService administration;
  @Autowired private OrphanedMediaFileCleanupService fileCleanup;
  @Autowired private WatchStatusService watchStatus;
  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();

  @AfterEach
  void releaseResources() {
    sockets.forEach(GraphQlTransportWsTestClient::close);
    new ActiveSubscriptions(metrics).awaitNoneActive();
    identities.forEach(auth::deleteIdentity);
  }

  @Test
  @DisplayName("Should deliver an addition to a non-admin Profile when a scan adds a movie")
  void shouldDeliverAnAdditionToANonAdminProfileWhenAScanAddsAMovie() throws Exception {
    Files.createFile(directory.resolve("Arrival (2016).mkv"));
    var library =
        libraries.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder()
                .filepathUri(FilepathCodec.encode(directory))
                .build());
    stubMovie();
    startProbeScheduler();
    var socket = subscribeToAdditions(library);

    operations.scanToEnd(library.getId());

    var message = socket.nextMessageFor("additions");
    assertThat(message.path("type").asString()).isEqualTo("next");
    assertThat(message.path("payload").has("errors")).as(message.toPrettyString()).isFalse();
    var change = message.at("/payload/data/libraryItemAdded");
    assertThat(change.path("libraryId").asString()).isEqualTo(library.getId().toString());
    assertThat(change.at("/item/title").asString()).isEqualTo("Arrival");
    assertThat(change.at("/item/library/id").asString()).isEqualTo(library.getId().toString());
    assertThat(change.at("/item/unmatched").asBoolean()).isFalse();
    socket.subscribe(
        "files",
        """
        subscription { libraryItemUpdated(libraryId: "%s") {
          item { ... on Movie { files { id } } }
        } }
        """
            .formatted(library.getId()));
    new ActiveSubscriptions(metrics).awaitCount("libraryItemUpdated", 1);
    Files.createFile(directory.resolve("Arrival (2016) [1080p].mkv"));

    operations.scanToEnd(library.getId());

    assertThat(socket.nextMessageFor("files").at("/payload/data/libraryItemUpdated/item/files"))
        .hasSize(2);
  }

  @Test
  @DisplayName(
      "Should deliver only committed changes from the subscribed library when metadata changes")
  void shouldDeliverOnlyCommittedChangesFromTheSubscribedLibraryWhenMetadataChanges() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movies.saveAndFlush(
            Movie.builder().title("Before").titleSort("Before").library(library).build());
    var otherLibrary = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var otherMovie =
        movies.saveAndFlush(
            Movie.builder().title("Other").titleSort("Other").library(otherLibrary).build());
    var sentinel =
        movies.saveAndFlush(
            Movie.builder().title("Sentinel").titleSort("Sentinel").library(library).build());
    var identity = auth.createIdentity();
    identities.add(identity);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
    sockets.add(socket);
    socket.subscribe(
        "updates",
        """
        subscription { libraryItemUpdated(libraryId: "%s") {
          itemId item { ... on Movie { title summary } }
        } }
        """
            .formatted(library.getId()));
    new ActiveSubscriptions(metrics).awaitCount("libraryItemUpdated", 1);

    refresh(otherMovie, "Other changed");
    transactions.executeWithoutResult(
        status -> {
          refresh(movie, "Rolled back");
          assertThat(socket.receivedMessages())
              .noneMatch(message -> message.path("id").asString().equals("updates"));
          status.setRollbackOnly();
        });
    refresh(movies.findById(movie.getId()).orElseThrow(), "Committed");
    refresh(sentinel, "Sentinel committed");

    var message = socket.nextMessageFor("updates");
    assertThat(message.path("type").asString()).isEqualTo("next");
    assertThat(message.at("/payload/data/libraryItemUpdated/itemId").asString())
        .isEqualTo(movie.getId().toString());
    assertThat(message.at("/payload/data/libraryItemUpdated/item/title").asString())
        .isEqualTo("Committed");
    assertThat(message.at("/payload/data/libraryItemUpdated/item/summary").asString())
        .isEqualTo("Changed summary");
    assertThat(
            socket
                .nextMessageFor("updates")
                .at("/payload/data/libraryItemUpdated/itemId")
                .asString())
        .isEqualTo(sentinel.getId().toString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should discard indirect changes when their transaction rolls back")
  void shouldDiscardIndirectChangesWhenTheirTransactionRollsBack(boolean watchStateChange)
      throws Exception {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var keptFile = Files.createFile(directory.resolve("kept.mkv"));
    var movie =
        movies.saveAndFlush(
            Movie.builder()
                .title("Rolled back")
                .titleSort("Rolled back")
                .library(library)
                .files(
                    Set.of(
                        MediaFile.builder()
                            .libraryId(library.getId())
                            .filename("kept.mkv")
                            .filepathUri(FilepathCodec.encode(keptFile))
                            .status(MediaFileStatus.MATCHED)
                            .build(),
                        MediaFile.builder()
                            .libraryId(library.getId())
                            .filename("missing.mkv")
                            .filepathUri(FilepathCodec.encode(directory.resolve("missing.mkv")))
                            .status(MediaFileStatus.MATCHED)
                            .build()))
                .build());
    var sentinel =
        movies.saveAndFlush(
            Movie.builder().title("Sentinel").titleSort("Sentinel").library(library).build());
    var identity = auth.createIdentity();
    identities.add(identity);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
    sockets.add(socket);
    socket.subscribe(
        "indirect",
        "subscription { libraryItemUpdated(libraryId: \"%s\") { itemId } }"
            .formatted(library.getId()));
    new ActiveSubscriptions(metrics).awaitCount("libraryItemUpdated", 1);
    Runnable change =
        watchStateChange
            ? () -> watchStatus.markWatched(identity.profile().getId(), movie.getId())
            : () -> fileCleanup.cleanupOrphanedFiles(library);

    transactions.executeWithoutResult(
        status -> {
          change.run();
          status.setRollbackOnly();
        });
    refresh(sentinel, "Committed sentinel");

    assertThat(
            socket
                .nextMessageFor("indirect")
                .at("/payload/data/libraryItemUpdated/itemId")
                .asString())
        .isEqualTo(sentinel.getId().toString());
  }

  @Test
  @DisplayName("Should send a removal without an update when orphan cleanup deletes a movie")
  void shouldSendARemovalWithoutAnUpdateWhenOrphanCleanupDeletesAMovie() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var removed =
        movies.saveAndFlush(
            Movie.builder()
                .title("Removed")
                .titleSort("Removed")
                .library(library)
                .files(
                    Set.of(
                        MediaFile.builder()
                            .libraryId(library.getId())
                            .filename("missing.mkv")
                            .filepathUri(FilepathCodec.encode(directory.resolve("missing.mkv")))
                            .status(MediaFileStatus.MATCHED)
                            .build()))
                .build());
    var sentinel =
        movies.saveAndFlush(
            Movie.builder().title("Sentinel").titleSort("Sentinel").library(library).build());
    var identity = auth.createIdentity();
    identities.add(identity);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
    sockets.add(socket);
    socket.subscribe(
        "cleanup-updates",
        "subscription { libraryItemUpdated(libraryId: \"%s\") { itemId } }"
            .formatted(library.getId()));
    socket.subscribe(
        "cleanup-removals",
        "subscription { libraryItemRemoved(libraryId: \"%s\") { itemId } }"
            .formatted(library.getId()));
    new ActiveSubscriptions(metrics).awaitCount("libraryItemUpdated", 1);
    new ActiveSubscriptions(metrics).awaitCount("libraryItemRemoved", 1);

    transactions.executeWithoutResult(_ -> fileCleanup.cleanupOrphanedFiles(library));
    refresh(sentinel, "After cleanup");

    assertThat(
            socket
                .nextMessageFor("cleanup-removals")
                .at("/payload/data/libraryItemRemoved/itemId")
                .asString())
        .isEqualTo(removed.getId().toString());
    assertThat(
            socket
                .nextMessageFor("cleanup-updates")
                .at("/payload/data/libraryItemUpdated/itemId")
                .asString())
        .isEqualTo(sentinel.getId().toString());
  }

  private void refresh(Movie movie, String title) {
    try (var run = artwork.openRun("Subscription metadata test", ImageRefreshMode.PRESERVE)) {
      movieService.refreshMovieMetadata(
          movie,
          MetadataFixture.<Movie>metadataResultBuilder()
              .entity(
                  Movie.builder().title(title).titleSort(title).summary("Changed summary").build())
              .build(),
          run);
    }
  }

  @Test
  @DisplayName("Should deliver a removal when a scan confirms the movie's only file is missing")
  void shouldDeliverARemovalWhenAScanConfirmsTheMoviesOnlyFileIsMissing() throws Exception {
    Files.createFile(directory.resolve("library.txt"));
    var library =
        libraries.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder()
                .filepathUri(FilepathCodec.encode(directory))
                .build());
    var movie =
        movies.saveAndFlush(
            Movie.builder()
                .title("Missing")
                .titleSort("Missing")
                .library(library)
                .files(
                    Set.of(
                        MediaFile.builder()
                            .libraryId(library.getId())
                            .filename("Missing.mkv")
                            .filepathUri(FilepathCodec.encode(directory.resolve("Missing.mkv")))
                            .status(MediaFileStatus.MATCHED)
                            .build()))
                .build());
    var identity = auth.createIdentity();
    identities.add(identity);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
    sockets.add(socket);
    socket.subscribe(
        "removals",
        """
        subscription { libraryItemRemoved(libraryId: "%s") { itemId libraryId kind } }
        """
            .formatted(library.getId()));
    new ActiveSubscriptions(metrics).awaitCount("libraryItemRemoved", 1);

    operations.scanToEnd(library.getId());

    var message = socket.nextMessageFor("removals");
    assertThat(message.path("type").asString()).isEqualTo("next");
    var receipt = message.at("/payload/data/libraryItemRemoved");
    assertThat(receipt.path("itemId").asString()).isEqualTo(movie.getId().toString());
    assertThat(receipt.path("libraryId").asString()).isEqualTo(library.getId().toString());
    assertThat(receipt.path("kind").asString()).isEqualTo("MOVIE");
    assertThat(movies.findById(movie.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should deliver the counts when a scan adds a movie and complete on library removal")
  void shouldDeliverTheCountsWhenAScanAddsAMovieAndCompleteOnLibraryRemoval() throws Exception {
    Files.createFile(directory.resolve("Arrival (2016).mkv"));
    var library =
        libraries.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder()
                .filepathUri(FilepathCodec.encode(directory))
                .build());
    stubMovie();
    startProbeScheduler();
    var identity = auth.createIdentity();
    identities.add(identity);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
    sockets.add(socket);
    socket.subscribe(
        "counts",
        """
        subscription { libraryCountsChanged(libraryId: "%s") {
          id itemCount alphabetIndex { letter count }
        } }
        """
            .formatted(library.getId()));
    new ActiveSubscriptions(metrics).awaitCount("libraryCountsChanged", 1);

    operations.scanToEnd(library.getId());

    var message = socket.nextMessageFor("counts");
    assertThat(message.path("type").asString()).isEqualTo("next");
    var aggregate = message.at("/payload/data/libraryCountsChanged");
    assertThat(aggregate.path("id").asString()).isEqualTo(library.getId().toString());
    assertThat(aggregate.path("itemCount").asInt()).isOne();
    assertThat(aggregate.path("alphabetIndex"))
        .extracting(
            bucket -> bucket.path("letter").asString(), bucket -> bucket.path("count").asInt())
        .containsExactly(tuple("A", 1));
    var administrator = auth.createAdminIdentity();
    identities.add(administrator);
    assertThat(administration.removeLibrary(auth.identityOf(administrator), library.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(typesUntilCompletion(socket, "counts")).last().isEqualTo("complete");
  }

  @ParameterizedTest
  @EnumSource(
      value = MediaType.class,
      names = {"MOVIE", "SERIES"})
  @DisplayName(
      "Should complete every subscription and a late registration when its library is removed")
  void shouldCompleteEverySubscriptionWhenItsLibraryIsRemovedAndCompleteALateRegistration(
      MediaType type) {
    var library =
        libraries.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder().type(type).build());
    for (var index = 0; index < 65; index++) {
      var title = "Item " + index;
      if (type == MediaType.MOVIE) {
        movies.saveAndFlush(Movie.builder().title(title).titleSort(title).library(library).build());
        continue;
      }

      seriesRepository.saveAndFlush(
          Series.builder().title(title).titleSort(title).library(library).build());
    }

    var identity = auth.createIdentity();
    identities.add(identity);
    var administrator = auth.createAdminIdentity();
    identities.add(administrator);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
    sockets.add(socket);
    var fields =
        Map.of(
            "libraryItemAdded",
            "itemId",
            "libraryItemUpdated",
            "itemId",
            "libraryItemRemoved",
            "itemId",
            "libraryCountsChanged",
            "id");
    fields.forEach(
        (field, selection) -> {
          socket.subscribe(
              field,
              "subscription { %s(libraryId: \"%s\") { %s } }"
                  .formatted(field, library.getId(), selection));
          new ActiveSubscriptions(metrics).awaitCount(field, 1);
        });

    assertThat(administration.removeLibrary(auth.identityOf(administrator), library.getId()))
        .isInstanceOf(Outcome.Accepted.class);

    fields
        .keySet()
        .forEach(
            field ->
                assertThat(socket.nextMessageFor(field).path("type").asString())
                    .isEqualTo("complete"));
    new ActiveSubscriptions(metrics).awaitNoneActive();
    socket.subscribe(
        "late",
        "subscription { libraryItemAdded(libraryId: \"%s\") { itemId } }"
            .formatted(library.getId()));
    assertThat(socket.nextMessageFor("late").path("type").asString()).isEqualTo("complete");
  }

  @Test
  @DisplayName(
      "Should deliver each series change when a series is created, changed through its children and removed")
  void shouldDeliverEachSeriesChangeWhenASeriesIsCreatedChangedAndRemoved() {
    var library =
        libraries.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder().type(MediaType.SERIES).build());
    var identity = auth.createIdentity();
    identities.add(identity);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
    sockets.add(socket);
    for (var field : List.of("libraryItemAdded", "libraryItemUpdated", "libraryItemRemoved")) {
      var selection =
          field.equals("libraryItemRemoved")
              ? "itemId kind"
              : "itemId kind item { ... on Series { title seasons { seasonNumber episodes { title } } } }";
      socket.subscribe(
          field,
          "subscription { %s(libraryId: \"%s\") { %s } }"
              .formatted(field, library.getId(), selection));
      new ActiveSubscriptions(metrics).awaitCount(field, 1);
    }

    Series series;
    try (var run = artwork.openRun("Series subscription", ImageRefreshMode.PRESERVE)) {
      series =
          seriesService.createSeriesWithAssociations(
              MetadataFixture.<Series>metadataResultBuilder()
                  .entity(
                      Series.builder().title("Series").titleSort("Series").library(library).build())
                  .build(),
              run);
      var added = socket.nextMessageFor("libraryItemAdded");
      assertThat(added.at("/payload/data/libraryItemAdded/item/title").asString())
          .isEqualTo("Series");
      seriesService.createSeasonWithEpisodes(
          SeasonWithEpisodesRequest.builder()
              .series(series)
              .library(library)
              .artworkRun(run)
              .details(
                  SeasonDetails.builder()
                      .name("Season one")
                      .seasonNumber(1)
                      .imageSources(List.of())
                      .episodes(
                          List.of(
                              SeasonDetails.EpisodeDetails.builder()
                                  .episodeNumber(1)
                                  .name("Pilot")
                                  .imageSources(List.of())
                                  .build()))
                      .build())
              .build());
      var updated = socket.nextMessageFor("libraryItemUpdated");
      assertThat(updated.at("/payload/data/libraryItemUpdated/itemId").asString())
          .isEqualTo(series.getId().toString());
      assertThat(
              updated
                  .at("/payload/data/libraryItemUpdated/item/seasons/0/episodes/0/title")
                  .asString())
          .isEqualTo("Pilot");
    }

    seriesService.deleteSeriesById(series.getId());
    var removed = socket.nextMessageFor("libraryItemRemoved");
    assertThat(removed.at("/payload/data/libraryItemRemoved/itemId").asString())
        .isEqualTo(series.getId().toString());
    assertThat(removed.at("/payload/data/libraryItemRemoved/kind").asString()).isEqualTo("SERIES");
  }

  @Test
  @DisplayName("Should deliver new artwork when its scan step commits")
  void shouldDeliverNewArtworkWhenItsScanStepCommits() throws Exception {
    Files.createFile(directory.resolve("Arrival (2016).mkv"));
    var library =
        libraries.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder()
                .filepathUri(FilepathCodec.encode(directory))
                .build());
    stubMovie();
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/329865"))
            .willReturn(
                okJson(
                    """
        {"id":329865,"title":"Arrival","original_title":"Arrival",
         "release_date":"2016-11-10","runtime":116,"poster_path":"/arrival.jpg",
         "genres":[],"production_companies":[],"credits":{"cast":[],"crew":[]},
         "release_dates":{"results":[]}}
        """)));
    wireMock.stubFor(
        get(urlPathEqualTo("/arrival.jpg"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/jpeg")
                    .withBody(createTestImage(600, 900))));
    var identity = auth.createIdentity();
    identities.add(identity);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
    sockets.add(socket);
    socket.subscribe(
        "artwork",
        """
        subscription { libraryItemUpdated(libraryId: "%s") {
          item { ... on Movie { title images(type: POSTER) { variants { id url } } } }
        } }
        """
            .formatted(library.getId()));
    new ActiveSubscriptions(metrics).awaitCount("libraryItemUpdated", 1);
    startProbeScheduler();

    operations.scanToEnd(library.getId());

    var message = socket.nextMessageFor("artwork");
    assertThat(message.path("type").asString()).isEqualTo("next");
    assertThat(message.path("payload").has("errors")).as(message.toPrettyString()).isFalse();
    var movie = message.at("/payload/data/libraryItemUpdated/item");
    assertThat(movie.path("title").asString()).isEqualTo("Arrival");
    assertThat(movie.at("/images/0/variants/0/url").asString()).startsWith("/api/images/");
  }

  @Test
  @DisplayName(
      "Should deliver watch-state changes only to the affected Profile when a Profile watches")
  void shouldDeliverWatchStateChangesOnlyToTheAffectedProfileWhenAProfileWatches() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movies.saveAndFlush(
            Movie.builder().title("Mine").titleSort("Mine").library(library).build());
    var sentinel =
        movies.saveAndFlush(
            Movie.builder().title("Other").titleSort("Other").library(library).build());
    var first = auth.createIdentity();
    var second = auth.createIdentity();
    identities.addAll(List.of(first, second));
    var subscribers = new ArrayList<GraphQlTransportWsTestClient>();
    for (var identity : List.of(first, second)) {
      var socket =
          GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
      sockets.add(socket);
      subscribers.add(socket);
      socket.subscribe(
          "watch",
          """
          subscription { libraryItemUpdated(libraryId: "%s") {
            itemId item { ... on Movie { watchStatus lastWatchedAt } }
          } }
          """
              .formatted(library.getId()));
      new ActiveSubscriptions(metrics).awaitCount("libraryItemUpdated", subscribers.size());
    }

    try (var http =
        new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)))) {
      for (var entry : Map.of(first, movie, second, sentinel).entrySet()) {
        var result =
            http.execute(
                auth.profileBearer(entry.getKey()),
                "mutation { markWatched(input: {itemId: \"%s\"}) { userErrors { __typename } } }"
                    .formatted(entry.getValue().getId()));
        assertThat(result.at("/data/markWatched/userErrors").isEmpty())
            .as(result.toPrettyString())
            .isTrue();
      }
    }

    var own = subscribers.getFirst().nextMessageFor("watch").at("/payload/data/libraryItemUpdated");
    var other =
        subscribers.getLast().nextMessageFor("watch").at("/payload/data/libraryItemUpdated");
    assertThat(own.path("itemId").asString()).isEqualTo(movie.getId().toString());
    assertThat(other.path("itemId").asString()).isEqualTo(sentinel.getId().toString());
    assertThat(own.at("/item/watchStatus").asString()).isEqualTo("WATCHED");
    assertThat(own.at("/item/lastWatchedAt").isNull()).isFalse();
  }

  @Test
  @DisplayName("Should update the series when scanning adds and removes an episode file")
  void shouldUpdateTheSeriesWhenScanningAddsAndRemovesAnEpisodeFile() throws Exception {
    var seasonDirectory = directory.resolve("Example/Season 01");
    Files.createDirectories(seasonDirectory);
    var file = Files.createFile(seasonDirectory.resolve("Example.S01E01.mkv"));
    Files.createFile(directory.resolve("library.txt"));
    var library =
        libraries.saveAndFlush(
            LibraryFixtureCreator.buildFakeSeriesLibrary().toBuilder()
                .filepathUri(FilepathCodec.encode(directory))
                .build());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Example")
                .titleSort("Example")
                .library(library)
                .externalIds(
                    Set.of(
                        ExternalIdentifier.builder()
                            .externalSourceType(ExternalSourceType.TMDB)
                            .externalId("123")
                            .build()))
                .build());
    seasons.saveAndFlush(Season.builder().seasonNumber(1).series(series).library(library).build());
    wireMock.stubFor(
        get(urlPathEqualTo("/search/tv"))
            .willReturn(
                okJson(
                    """
        {"page":1,"results":[{"id":123,"name":"Example","original_name":"Example",
         "first_air_date":"2020-01-01","popularity":100,"vote_count":1000,"vote_average":8}],
         "total_results":1,"total_pages":1}
        """)));
    var identity = auth.createIdentity();
    identities.add(identity);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
    sockets.add(socket);
    socket.subscribe(
        "episodes",
        """
        subscription { libraryItemUpdated(libraryId: "%s") {
          itemId item { ... on Series { seasons { episodes { id files { id } } } } }
        } }
        """
            .formatted(library.getId()));
    new ActiveSubscriptions(metrics).awaitCount("libraryItemUpdated", 1);
    startProbeScheduler();

    operations.scanToEnd(library.getId());

    var created = socket.nextMessageFor("episodes");
    assertThat(created.at("/payload/data/libraryItemUpdated/itemId").asString())
        .isEqualTo(series.getId().toString());
    var added = socket.nextMessageFor("episodes");
    assertThat(added.at("/payload/data/libraryItemUpdated/itemId").asString())
        .isEqualTo(series.getId().toString());
    assertThat(added.at("/payload/data/libraryItemUpdated/item/seasons/0/episodes/0/files"))
        .hasSize(1);

    Files.delete(file);
    operations.scanToEnd(library.getId());

    var removed = socket.nextMessageFor("episodes");
    assertThat(removed.at("/payload/data/libraryItemUpdated/itemId").asString())
        .isEqualTo(series.getId().toString());
    assertThat(removed.at("/payload/data/libraryItemUpdated/item/seasons/0/episodes/0/files"))
        .isEmpty();
  }

  @Test
  @DisplayName("Should deliver the series when its or its episodes' metadata is refreshed")
  void shouldDeliverTheSeriesWhenItsOrItsEpisodesMetadataIsRefreshed() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder().title("Before").titleSort("Before").library(library).build());
    var identity = auth.createIdentity();
    identities.add(identity);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
    sockets.add(socket);
    socket.subscribe(
        "refresh",
        """
        subscription { libraryItemUpdated(libraryId: "%s") {
          item { ... on Series { title seasons { episodes { title } } } }
        } }
        """
            .formatted(library.getId()));
    new ActiveSubscriptions(metrics).awaitCount("libraryItemUpdated", 1);
    try (var run = artwork.openRun("Series refresh subscription", ImageRefreshMode.PRESERVE)) {
      seriesService.refreshSeriesMetadata(
          series,
          MetadataFixture.<Series>metadataResultBuilder()
              .entity(Series.builder().title("Refreshed").titleSort("Refreshed").build())
              .build(),
          run);
      assertThat(
              socket
                  .nextMessageFor("refresh")
                  .at("/payload/data/libraryItemUpdated/item/title")
                  .asString())
          .isEqualTo("Refreshed");
      seriesService.refreshSeasonWithEpisodes(
          SeasonWithEpisodesRequest.builder()
              .series(series)
              .library(library)
              .artworkRun(run)
              .details(
                  SeasonDetails.builder()
                      .seasonNumber(1)
                      .name("Season")
                      .imageSources(List.of())
                      .episodes(
                          List.of(
                              SeasonDetails.EpisodeDetails.builder()
                                  .episodeNumber(1)
                                  .name("Refreshed pilot")
                                  .imageSources(List.of())
                                  .build()))
                      .build())
              .build());
      assertThat(
              socket
                  .nextMessageFor("refresh")
                  .at("/payload/data/libraryItemUpdated/item/seasons/0/episodes/0/title")
                  .asString())
          .isEqualTo("Refreshed pilot");
    }
  }

  @Test
  @DisplayName("Should refuse every library subscription when the selected Account is disabled")
  void shouldRefuseEveryLibrarySubscriptionWhenSelectedAccountIsDisabled() {
    var identity = auth.createIdentity();
    identities.add(identity);
    var bearer = auth.profileBearer(identity);
    var account = accounts.findById(identity.account().getId()).orElseThrow();
    account.setEnabled(false);
    accounts.saveAndFlush(account);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, bearer);
    sockets.add(socket);
    for (var field :
        List.of(
            "libraryItemAdded",
            "libraryItemUpdated",
            "libraryItemRemoved",
            "libraryCountsChanged")) {
      var selection = field.equals("libraryCountsChanged") ? "id" : "itemId";
      socket.subscribe(
          field,
          "subscription { %s(libraryId: \"%s\") { %s } }"
              .formatted(field, UUID.randomUUID(), selection));
      var refusal = socket.nextMessageFor(field);
      assertThat(refusal.at("/payload/errors/0/extensions/code").asString())
          .as(refusal.toPrettyString())
          .isEqualTo("FORBIDDEN");
      assertThat(socket.nextMessageFor(field).path("type").asString()).isEqualTo("complete");
    }

    new ActiveSubscriptions(metrics).awaitNoneActive();
  }

  @Test
  @DisplayName(
      "Should refuse every field when the token has no selected Profile, as library queries do")
  void shouldRefuseEveryFieldWhenTheTokenHasNoSelectedProfile() {
    var identity = auth.createIdentity();
    identities.add(identity);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.accountBearer(identity));
    sockets.add(socket);
    for (var field :
        List.of(
            "libraryItemAdded",
            "libraryItemUpdated",
            "libraryItemRemoved",
            "libraryCountsChanged")) {
      var selection = field.equals("libraryCountsChanged") ? "id" : "itemId";
      socket.subscribe(
          field,
          "subscription { %s(libraryId: \"unreadable\") { %s } }".formatted(field, selection));
      var refusal = socket.nextMessageFor(field);
      assertThat(refusal.at("/payload/errors/0/extensions/code").asString())
          .isEqualTo("PROFILE_REQUIRED");
      assertThat(socket.nextMessageFor(field).path("type").asString()).isEqualTo("complete");
    }

    new ActiveSubscriptions(metrics).awaitNoneActive();
  }

  // Pushes that a scan's later item updates scheduled can arrive before the completion.
  private static List<String> typesUntilCompletion(
      GraphQlTransportWsTestClient socket, String operationId) {
    var types = new ArrayList<String>();
    for (var index = 0; index < 10; index++) {
      var type = socket.nextMessageFor(operationId).path("type").asString();
      types.add(type);
      if (!type.equals("next")) {
        return types;
      }
    }

    return types;
  }

  private GraphQlTransportWsTestClient subscribeToAdditions(Library library) {
    var identity = auth.createIdentity();
    identities.add(identity);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, auth.profileBearer(identity));
    sockets.add(socket);
    socket.subscribe(
        "additions",
        """
        subscription {
          libraryItemAdded(libraryId: "%s") {
            libraryId
            item { ... on Movie { id title unmatched library { id } } }
          }
        }
        """
            .formatted(library.getId()));
    new ActiveSubscriptions(metrics).awaitCount("libraryItemAdded", 1);
    return socket;
  }

  private void stubMovie() {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .willReturn(
                okJson(
                    """
                {"page":1,"results":[{"id":329865,"title":"Arrival",
                  "original_title":"Arrival","release_date":"2016-11-10",
                  "popularity":100,"vote_count":1000,"vote_average":8}],
                 "total_results":1,"total_pages":1}
                """)));
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/329865"))
            .willReturn(
                okJson(
                    """
                {"id":329865,"title":"Arrival","original_title":"Arrival",
                 "release_date":"2016-11-10","overview":"First contact.","runtime":116,
                 "genres":[],"production_companies":[],
                 "credits":{"id":329865,"cast":[],"crew":[]},"release_dates":{"results":[]}}
                """)));
  }
}
