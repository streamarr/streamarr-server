package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.watchprogress.events.WatchStateChangedEvent;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import graphql.schema.DataFetchingEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * A field that fails to resolve in one delivered change reaches the client with errors, and the
 * operation keeps delivering later changes.
 */
@Tag("IntegrationTest")
@DisplayName("Subscription Field Error Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SubscriptionFieldErrorIT.UnresolvableFieldResolvers.class)
class SubscriptionFieldErrorIT extends AbstractIntegrationTest {

  private static final String STATUS_FIELD = "libraryStatusChanged";
  private static final String STATUS_OPERATION = "status";
  private static final String WATCH_STATE_FIELD = "watchStateChanged";
  private static final String WATCH_STATE_OPERATION = "watch-state";
  private static final String UNRESOLVABLE_LIBRARY_NAME = "Unresolvable Library";
  private static final String UNRESOLVABLE_SEASON_TITLE = "Unresolvable Season";

  @LocalServerPort private int port;

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private MeterRegistry meterRegistry;

  @TempDir private Path libraryDirectory;

  private final List<UUID> libraryIds = new ArrayList<>();
  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();
  private ActiveSubscriptions activeSubscriptions;

  @BeforeEach
  void countActiveSubscriptions() {
    activeSubscriptions = new ActiveSubscriptions(meterRegistry);
  }

  @AfterEach
  void releaseResources() {
    sockets.forEach(GraphQlTransportWsTestClient::close);
    activeSubscriptions.awaitNoneActive();
    libraryIds.forEach(libraryRepository::deleteById);
    identities.forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should deliver null with errors and keep the operation open when a library's non-null field fails to resolve")
  void shouldDeliverNullWithErrorsAndKeepOperationOpenWhenLibrarysNonNullFieldFailsToResolve() {
    var socket =
        subscribe(
            authTestSupport.createAdminIdentity(),
            STATUS_OPERATION,
            "subscription { libraryStatusChanged { id name filepathUri status } }");
    activeSubscriptions.awaitCount(STATUS_FIELD, 1);
    var resolvable = saveLibrary("Resolvable Library");
    var unresolvable = saveLibrary(UNRESOLVABLE_LIBRARY_NAME);

    publishStatusChange(resolvable);
    assertThat(nextStatusChange(socket, resolvable.getId()).path("filepathUri").asString())
        .isEqualTo(resolvable.getFilepathUri());

    publishStatusChange(unresolvable);

    var failed = nextMessageWithoutLibrary(socket);
    assertThat(failed.path("type").asString()).isEqualTo("next");
    var payload = failed.path("payload");
    assertThat(payload.path("data").has(STATUS_FIELD)).isTrue();
    assertThat(payload.path("data").path(STATUS_FIELD).isNull()).isTrue();
    assertThat(payload.path("errors").path(0).path("path").valueStream())
        .map(JsonNode::asString)
        .containsExactly(STATUS_FIELD, "filepathUri");

    publishStatusChange(resolvable);

    assertThat(nextStatusChange(socket, resolvable.getId()).path("filepathUri").asString())
        .isEqualTo(resolvable.getFilepathUri());
  }

  @Test
  @DisplayName(
      "Should deliver the change with the failed item null and the other items intact, then the"
          + " next change, when a non-null field of one watch-state item fails to resolve")
  void shouldDeliverChangeWithFailedItemNullAndOtherItemsIntactWhenNonNullFieldOfOneItemFails() {
    var identity = authTestSupport.createIdentity();
    var socket =
        subscribe(
            identity,
            WATCH_STATE_OPERATION,
            """
            subscription {
              watchStateChanged {
                items {
                  ... on Movie { id }
                  ... on Episode { id }
                  ... on Season { id seasonNumber }
                  ... on Series { id }
                }
              }
            }
            """);
    activeSubscriptions.awaitCount(WATCH_STATE_FIELD, 1);
    var episode = saveEpisodeOfUnresolvableSeason();
    var sentinel = saveMovie();

    publishWatchStateChange(identity, episode.getId());

    var payload = socket.nextMessageFor(WATCH_STATE_OPERATION).path("payload");
    var items = payload.path("data").path(WATCH_STATE_FIELD).path("items").valueStream().toList();
    assertThat(items).hasSize(3).filteredOn(JsonNode::isNull).hasSize(1);
    assertThat(items)
        .filteredOn(item -> !item.isNull())
        .extracting(item -> item.path("id").asString())
        .containsExactlyInAnyOrder(
            episode.getId().toString(), episode.getSeason().getSeries().getId().toString());
    var failedIndex =
        IntStream.range(0, items.size())
            .filter(index -> items.get(index).isNull())
            .findFirst()
            .orElseThrow();
    assertThat(payload.path("errors").valueStream())
        .extracting(error -> error.path("path").toString())
        .isNotEmpty()
        .containsOnly(
            "[\"%s\",\"items\",%d,\"seasonNumber\"]".formatted(WATCH_STATE_FIELD, failedIndex));

    publishWatchStateChange(identity, sentinel.getId());

    var next = socket.nextMessageFor(WATCH_STATE_OPERATION).path("payload");
    assertThat(next.path("errors").isMissingNode()).isTrue();
    assertThat(next.path("data").path(WATCH_STATE_FIELD).path("items").valueStream())
        .extracting(item -> item.path("id").asString())
        .containsExactly(sentinel.getId().toString());
  }

  private GraphQlTransportWsTestClient subscribe(
      AuthTestSupport.TestIdentity identity, String operationId, String document) {
    identities.add(identity);
    var socket =
        GraphQlTransportWsTestClient.connectWithBearer(
            port, authTestSupport.profileBearer(identity));
    sockets.add(socket);
    socket.subscribe(operationId, document);
    return socket;
  }

  private Library saveLibrary(String name) {
    var library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.unsavedLibraryBuilder()
                .name(name)
                .filepathUri(FilepathCodec.encode(libraryDirectory.resolve(name)))
                .status(LibraryStatus.HEALTHY)
                .build());
    libraryIds.add(library.getId());
    return library;
  }

  private Episode saveEpisodeOfUnresolvableSeason() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder().title("Failing Series").library(library).build());
    var season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title(UNRESOLVABLE_SEASON_TITLE)
                .seasonNumber(1)
                .series(series)
                .library(library)
                .build());
    return episodeRepository.saveAndFlush(
        Episode.builder().episodeNumber(1).title("Pilot").season(season).library(library).build());
  }

  private Movie saveMovie() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return movieRepository.saveAndFlush(
        Movie.builder().title("Sentinel Movie").library(library).build());
  }

  private void publishStatusChange(Library library) {
    eventPublisher.publishEvent(new LibraryStatusChangedEvent(library.getId()));
  }

  private void publishWatchStateChange(AuthTestSupport.TestIdentity identity, UUID itemId) {
    eventPublisher.publishEvent(
        new WatchStateChangedEvent(identity.profile().getId(), Set.of(itemId)));
  }

  // The next status change of one library, skipping changes to other libraries.
  private static JsonNode nextStatusChange(GraphQlTransportWsTestClient socket, UUID libraryId) {
    while (true) {
      var change =
          socket.nextMessageFor(STATUS_OPERATION).path("payload").path("data").path(STATUS_FIELD);
      if (libraryId.toString().equals(change.path("id").asString())) {
        return change;
      }
    }
  }

  // The next message that delivers no library, skipping changes to other libraries.
  private static JsonNode nextMessageWithoutLibrary(GraphQlTransportWsTestClient socket) {
    while (true) {
      var message = socket.nextMessageFor(STATUS_OPERATION);
      if (!message.path("payload").path("data").path(STATUS_FIELD).isObject()) {
        return message;
      }
    }
  }

  /**
   * Fails the non-null filepathUri of the library named {@value #UNRESOLVABLE_LIBRARY_NAME} and the
   * non-null seasonNumber of the season titled {@value #UNRESOLVABLE_SEASON_TITLE}.
   */
  @DgsComponent
  static class UnresolvableFieldResolvers {

    @DgsData(parentType = "Library", field = "filepathUri")
    public String filepathUri(DataFetchingEnvironment dfe) {
      Library library = dfe.getSource();
      if (UNRESOLVABLE_LIBRARY_NAME.equals(library.getName())) {
        throw new UnresolvableFieldException("filepathUri");
      }

      return library.getFilepathUri();
    }

    @DgsData(parentType = "Season", field = "seasonNumber")
    public int seasonNumber(DataFetchingEnvironment dfe) {
      Season season = dfe.getSource();
      if (UNRESOLVABLE_SEASON_TITLE.equals(season.getTitle())) {
        throw new UnresolvableFieldException("seasonNumber");
      }

      return season.getSeasonNumber();
    }
  }

  private static final class UnresolvableFieldException extends RuntimeException {

    private UnresolvableFieldException(String field) {
      super(field + " cannot be resolved for this test source");
    }
  }
}
