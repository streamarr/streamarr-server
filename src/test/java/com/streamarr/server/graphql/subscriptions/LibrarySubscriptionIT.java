package com.streamarr.server.graphql.subscriptions;

import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.LibraryOperationScheduler;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

@Tag("IntegrationTest")
@DisplayName("Library Subscription Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LibrarySubscriptionIT extends AbstractIntegrationTest {

  private static final String STATUS_FIELD = "libraryStatusChanged";
  private static final String STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id name status scanStartedOn scanCompletedOn } }";
  private static final String AUDITED_STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id status createdOn lastModifiedOn } }";
  private static final String ADDED_SUBSCRIPTION =
      "subscription { libraryAdded { id name status scanStartedOn scanCompletedOn } }";
  private static final String REMOVED_SUBSCRIPTION =
      "subscription { libraryRemoved { libraryId } }";
  private static final String PROFILE_FIELDS_SUBSCRIPTION =
      """
      subscription {
        libraryStatusChanged {
          id
          status
          items(first: 1) { edges { node { __typename ... on Movie { id watchStatus } } } }
          alphabetIndex { letter count }
        }
      }
      """;

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private LibraryOperationScheduler libraryOperationScheduler;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private MovieRepository movieRepository;
  @Autowired private UserAccountRepository userAccountRepository;

  @TempDir private Path libraryDirectory;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private final List<UUID> libraryIds = new ArrayList<>();
  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();
  private ActiveSubscriptions activeSubscriptions;
  private GraphQlHttpTestClient http;

  @BeforeEach
  void openHttpClient() {
    activeSubscriptions = new ActiveSubscriptions(meterRegistry);
    http = new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)));
  }

  @AfterEach
  void releaseResources() {
    sockets.forEach(GraphQlTransportWsTestClient::close);
    http.close();
    activeSubscriptions.awaitNoneActive();
    libraryIds.forEach(libraryRepository::deleteById);
    identities.forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should deliver SCANNING then HEALTHY with a new scanCompletedOn when a ServerAdmin subscribes before a scan")
  void shouldDeliverScanningThenHealthyWithNewScanCompletedOnWhenServerAdminSubscribesBeforeScan() {
    var bearer = adminBearer();
    var library = saveLibrary(libraryDirectory);
    var queried =
        http.execute(bearer, "query { libraries { id status scanCompletedOn } }")
            .path("data")
            .path("libraries");
    assertThat(queried.valueStream())
        .filteredOn(node -> library.getId().toString().equals(node.path("id").asString()))
        .singleElement()
        .satisfies(
            node -> {
              assertThat(node.path("status").asString()).isEqualTo("HEALTHY");
              assertThat(node.path("scanCompletedOn").isNull()).isTrue();
            });
    var socket = statusSubscribedSocket(bearer);

    var accepted = http.execute(bearer, scanMutation(library.getId()));

    var payload = accepted.path("data").path("startLibraryScan");
    assertThat(payload.path("library").path("status").asString()).isEqualTo("SCANNING");
    assertThat(payload.path("userErrors").isEmpty()).isTrue();
    var scanning = nextStatusChange(socket, library.getId());
    assertThat(scanning.path("status").asString()).isEqualTo("SCANNING");
    assertThat(scanning.path("name").asString()).isEqualTo(library.getName());
    assertThat(scanning.path("scanStartedOn").isNull()).isFalse();
    var healthy = nextStatusChange(socket, library.getId());
    assertThat(healthy.path("status").asString()).isEqualTo("HEALTHY");
    assertThat(Instant.parse(healthy.path("scanCompletedOn").asString()))
        .isAfterOrEqualTo(Instant.parse(scanning.path("scanStartedOn").asString()));
  }

  @Test
  @DisplayName("Should deliver the library's audit timestamps when a scan changes its status")
  void shouldDeliverLibrarysAuditTimestampsWhenScanChangesItsStatus() {
    var bearer = adminBearer();
    var library = saveLibrary(libraryDirectory);
    var socket = connect(bearer);
    socket.subscribe("status", AUDITED_STATUS_SUBSCRIPTION);
    activeSubscriptions.awaitCount(STATUS_FIELD, 1);

    // The stopped scheduler keeps the library's row as the scan's start left it.
    libraryOperationScheduler.stop();
    try {
      http.execute(bearer, scanMutation(library.getId()));

      var scanning = nextStatusChange(socket, library.getId());
      var stored = libraryRepository.findById(library.getId()).orElseThrow();
      assertThat(scanning.path("status").asString()).isEqualTo("SCANNING");
      assertThat(Instant.parse(scanning.path("createdOn").asString()))
          .isEqualTo(stored.getCreatedOn());
      assertThat(Instant.parse(scanning.path("lastModifiedOn").asString()))
          .isEqualTo(stored.getLastModifiedOn());
    } finally {
      libraryOperationScheduler.start();
    }

    awaitStatus(socket, library.getId(), "HEALTHY");
  }

  @Test
  @DisplayName(
      "Should resolve the library's items and alphabet index when a ServerAdmin's subscription delivers a status change")
  void
      shouldResolveLibrarysItemsAndAlphabetIndexWhenServerAdminsSubscriptionDeliversStatusChange() {
    var bearer = adminBearer();
    var library = saveLibrary(libraryDirectory);
    var movie = saveMovie(library);
    var socket = connect(bearer);
    socket.subscribe("status", PROFILE_FIELDS_SUBSCRIPTION);
    activeSubscriptions.awaitCount(STATUS_FIELD, 1);

    http.execute(bearer, refreshMutation(library.getId()));

    var change = nextStatusChangeWithoutErrors(socket, library.getId());
    assertThat(change.path("items").path("edges").path(0).path("node").path("id").asString())
        .isEqualTo(movie.getId().toString());
    assertThat(change.path("alphabetIndex").isArray()).isTrue();
    awaitStatus(socket, library.getId(), "HEALTHY");
  }

  @Test
  @DisplayName(
      "Should resolve each subscriber's own watch status when two ServerAdmins receive the same status change")
  void shouldResolveEachSubscribersOwnWatchStatusWhenTwoServerAdminsReceiveSameStatusChange() {
    var watcher = adminBearer();
    var other = adminBearer();
    var library = saveLibrary(libraryDirectory);
    var movie = saveMovie(library);
    markWatched(watcher, movie);
    var watcherSocket = connect(watcher);
    watcherSocket.subscribe("status", PROFILE_FIELDS_SUBSCRIPTION);
    var otherSocket = connect(other);
    otherSocket.subscribe("status", PROFILE_FIELDS_SUBSCRIPTION);
    activeSubscriptions.awaitCount(STATUS_FIELD, 2);

    http.execute(watcher, refreshMutation(library.getId()));

    assertThat(firstItemWatchStatus(nextStatusChangeWithoutErrors(watcherSocket, library.getId())))
        .isEqualTo("WATCHED");
    assertThat(firstItemWatchStatus(nextStatusChangeWithoutErrors(otherSocket, library.getId())))
        .isEqualTo("UNWATCHED");
    awaitStatus(watcherSocket, library.getId(), "HEALTHY");
  }

  @Test
  @DisplayName(
      "Should resolve the current watch status when the subscriber marks the item watched between two status changes")
  void shouldResolveCurrentWatchStatusWhenSubscriberMarksItemWatchedBetweenTwoStatusChanges() {
    var bearer = adminBearer();
    var library = saveLibrary(libraryDirectory);
    var movie = saveMovie(library);
    var socket = connect(bearer);
    socket.subscribe("status", PROFILE_FIELDS_SUBSCRIPTION);
    activeSubscriptions.awaitCount(STATUS_FIELD, 1);
    http.execute(bearer, refreshMutation(library.getId()));
    assertThat(firstItemWatchStatus(nextStatusChangeWithoutErrors(socket, library.getId())))
        .isEqualTo("UNWATCHED");
    awaitStatus(socket, library.getId(), "HEALTHY");
    markWatched(bearer, movie);

    http.execute(bearer, refreshMutation(library.getId()));

    assertThat(firstItemWatchStatus(nextStatusChangeWithoutErrors(socket, library.getId())))
        .isEqualTo("WATCHED");
    awaitStatus(socket, library.getId(), "HEALTHY");
  }

  @Test
  @DisplayName(
      "Should deliver SCANNING, STOPPING and then the status before the scan when a ServerAdmin"
          + " stops a scan")
  void shouldDeliverScanningStoppingAndThenTheStatusBeforeTheScanWhenAServerAdminStopsAScan() {
    var bearer = adminBearer();
    var library = saveLibrary(libraryDirectory);
    var socket = statusSubscribedSocket(bearer);

    // The stopped scheduler keeps the scan stored until the stop is stored.
    libraryOperationScheduler.stop();
    try {
      http.execute(bearer, scanMutation(library.getId()));
      assertThat(nextStatusChange(socket, library.getId()).path("status").asString())
          .isEqualTo("SCANNING");

      var stopped = http.execute(bearer, stopScanMutation(library.getId()));

      var payload = stopped.path("data").path("stopLibraryScan");
      assertThat(payload.path("library").path("status").asString()).isEqualTo("STOPPING");
      assertThat(payload.path("userErrors").isEmpty()).isTrue();
      assertThat(nextStatusChange(socket, library.getId()).path("status").asString())
          .isEqualTo("STOPPING");
    } finally {
      libraryOperationScheduler.start();
    }

    assertThat(nextStatusChange(socket, library.getId()).path("status").asString())
        .isEqualTo("HEALTHY");
  }

  @Test
  @DisplayName("Should deliver UNHEALTHY when a scan fails")
  void shouldDeliverUnhealthyWhenScanFails() {
    var bearer = adminBearer();
    var library = saveLibrary(libraryDirectory.resolve("missing"));
    var socket = statusSubscribedSocket(bearer);

    http.execute(bearer, scanMutation(library.getId()));

    assertThat(nextStatusChange(socket, library.getId()).path("status").asString())
        .isEqualTo("SCANNING");
    var unhealthy = nextStatusChange(socket, library.getId());
    assertThat(unhealthy.path("status").asString()).isEqualTo("UNHEALTHY");
    assertThat(unhealthy.path("scanCompletedOn").isNull()).isFalse();
  }

  @Test
  @DisplayName("Should deliver REFRESHING then HEALTHY when a refresh runs")
  void shouldDeliverRefreshingThenHealthyWhenRefreshRuns() {
    var bearer = adminBearer();
    var library = saveLibrary(libraryDirectory);
    var socket = statusSubscribedSocket(bearer);

    var accepted = http.execute(bearer, refreshMutation(library.getId()));

    var payload = accepted.path("data").path("startLibraryRefresh");
    assertThat(payload.path("library").path("status").asString()).isEqualTo("REFRESHING");
    assertThat(payload.path("userErrors").isEmpty()).isTrue();
    assertThat(nextStatusChange(socket, library.getId()).path("status").asString())
        .isEqualTo("REFRESHING");
    assertThat(nextStatusChange(socket, library.getId()).path("status").asString())
        .isEqualTo("HEALTHY");
  }

  @Test
  @DisplayName(
      "Should deliver the added library and the removal's library id when another session adds and removes a library")
  void shouldDeliverAddedLibraryAndRemovalIdWhenAnotherSessionAddsAndRemovesLibrary()
      throws IOException {
    var socket = allLibrarySubscriptionsSocket(adminBearer());
    var otherSession = adminBearer();

    var libraryId = addLibrary(otherSession, "Added Library", directory("added"));

    var added = nextAddedLibrary(socket);
    assertThat(added.path("id").asString()).isEqualTo(libraryId.toString());
    assertThat(added.path("name").asString()).isEqualTo("Added Library");
    assertThat(added.path("status").asString()).isEqualTo("SCANNING");
    awaitStatus(socket, libraryId, "HEALTHY");

    var removed = http.execute(otherSession, removeMutation(libraryId));

    assertThat(removedLibraryIdOf(removed)).isEqualTo(libraryId.toString());
    assertThat(removedLibraryId(socket)).isEqualTo(libraryId.toString());
  }

  @Test
  @DisplayName("Should deliver only the later addition when a refused addition comes first")
  void shouldDeliverOnlyLaterAdditionWhenRefusedAdditionComesFirst() throws IOException {
    var admin = adminBearer();
    var socket = allLibrarySubscriptionsSocket(admin);
    var member = authTestSupport.createIdentity();
    identities.add(member);

    var refused =
        http.execute(
            authTestSupport.profileBearer(member),
            addLibraryMutation("Refused Library", directory("refused")));
    var sentinelId = addLibrary(admin, "Sentinel Library", directory("sentinel"));

    assertThat(firstErrorCode(refused)).isEqualTo("FORBIDDEN");
    assertThat(nextAddedLibrary(socket).path("id").asString()).isEqualTo(sentinelId.toString());
    awaitStatus(socket, sentinelId, "HEALTHY");
  }

  @Test
  @DisplayName("Should deliver only the later addition when a rolled-back addition comes first")
  void shouldDeliverOnlyLaterAdditionWhenRolledBackAdditionComesFirst() throws IOException {
    var admin = adminBearer();
    var registered = saveLibrary(directory("registered"));
    var socket = allLibrarySubscriptionsSocket(admin);

    var rolledBack =
        http.execute(admin, addLibraryMutation("Duplicate Library", directory("registered")));
    var sentinelId = addLibrary(admin, "Sentinel Library", directory("sentinel"));

    assertThat(
            rolledBack
                .path("data")
                .path("addLibrary")
                .path("userErrors")
                .path(0)
                .path("__typename")
                .asString())
        .isEqualTo("LibraryPathAlreadyRegisteredError");
    assertThat(libraryRepository.findById(registered.getId())).isPresent();
    assertThat(nextAddedLibrary(socket).path("id").asString()).isEqualTo(sentinelId.toString());
    awaitStatus(socket, sentinelId, "HEALTHY");
  }

  @Test
  @DisplayName("Should deliver only the later removal when a refused removal comes first")
  void shouldDeliverOnlyLaterRemovalWhenRefusedRemovalComesFirst() throws IOException {
    var admin = adminBearer();
    var busy = saveLibrary(directory("busy"));
    var sentinel = saveLibrary(directory("sentinel"));
    var socket = allLibrarySubscriptionsSocket(admin);
    // The stopped scheduler keeps the scan stored and the library SCANNING.
    libraryOperationScheduler.stop();
    try {
      http.execute(admin, scanMutation(busy.getId()));

      var refused = http.execute(admin, removeMutation(busy.getId()));
      var removed = http.execute(admin, removeMutation(sentinel.getId()));

      var refusal = refused.path("data").path("removeLibrary").path("userErrors").path(0);
      assertThat(refusal.path("__typename").asString()).isEqualTo("LibraryBusyError");
      assertThat(refusal.path("status").asString()).isEqualTo("SCANNING");
      assertThat(libraryRepository.findById(busy.getId())).isPresent();
      assertThat(removedLibraryIdOf(removed)).isEqualTo(sentinel.getId().toString());
      assertThat(removedLibraryId(socket)).isEqualTo(sentinel.getId().toString());
    } finally {
      libraryOperationScheduler.start();
    }

    awaitStatus(socket, busy.getId(), "HEALTHY");
  }

  @Test
  @DisplayName(
      "Should deliver SCANNING then HEALTHY when a Profile that is not a ServerAdmin subscribes before a scan")
  void shouldDeliverScanningThenHealthyWhenProfileThatIsNotServerAdminSubscribesBeforeScan() {
    var library = saveLibrary(libraryDirectory);
    var socket = statusSubscribedSocket(ordinaryProfileBearer());

    http.execute(adminBearer(), scanMutation(library.getId()));

    assertThat(nextStatusChangeWithoutErrors(socket, library.getId()).path("status").asString())
        .isEqualTo("SCANNING");
    var healthy = nextStatusChangeWithoutErrors(socket, library.getId());
    assertThat(healthy.path("status").asString()).isEqualTo("HEALTHY");
    assertThat(healthy.path("scanCompletedOn").isNull()).isFalse();
  }

  @Test
  @DisplayName(
      "Should deliver the added library and the removal's library id when a Profile that is not a ServerAdmin subscribes")
  void shouldDeliverAddedLibraryAndRemovalIdWhenProfileThatIsNotServerAdminSubscribes()
      throws IOException {
    var socket = allLibrarySubscriptionsSocket(ordinaryProfileBearer());
    var admin = adminBearer();

    var libraryId = addLibrary(admin, "Family Library", directory("family"));

    var added = nextAddedLibrary(socket);
    assertThat(added.path("id").asString()).isEqualTo(libraryId.toString());
    assertThat(added.path("name").asString()).isEqualTo("Family Library");
    awaitStatus(socket, libraryId, "HEALTHY");

    http.execute(admin, removeMutation(libraryId));

    assertThat(removedLibraryId(socket)).isEqualTo(libraryId.toString());
  }

  @Test
  @DisplayName("Should deliver SCANNING then HEALTHY when the session is device-bound")
  void shouldDeliverScanningThenHealthyWhenSessionIsDeviceBound() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    var library = saveLibrary(libraryDirectory);
    var socket = statusSubscribedSocket(authTestSupport.deviceBoundProfileBearer(identity));

    http.execute(adminBearer(), scanMutation(library.getId()));

    assertThat(nextStatusChangeWithoutErrors(socket, library.getId()).path("status").asString())
        .isEqualTo("SCANNING");
    assertThat(nextStatusChangeWithoutErrors(socket, library.getId()).path("status").asString())
        .isEqualTo("HEALTHY");
  }

  @Test
  @DisplayName(
      "Should refuse each library subscription with PROFILE_REQUIRED when no Profile is selected")
  void shouldRefuseEachLibrarySubscriptionWithProfileRequiredWhenNoProfileIsSelected() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);

    assertEachSubscriptionRefusedWith(authTestSupport.accountBearer(identity), "PROFILE_REQUIRED");
  }

  @Test
  @DisplayName(
      "Should refuse each library subscription with FORBIDDEN when the Account is disabled")
  void shouldRefuseEachLibrarySubscriptionWithForbiddenWhenAccountIsDisabled() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    var bearer = authTestSupport.profileBearer(identity);
    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);

    assertEachSubscriptionRefusedWith(bearer, "FORBIDDEN");
  }

  private void assertEachSubscriptionRefusedWith(String bearer, String code) {
    var socket = connect(bearer);
    var subscriptions =
        List.of(
            new RefusedSubscription("status", STATUS_FIELD, STATUS_SUBSCRIPTION),
            new RefusedSubscription("added", "libraryAdded", ADDED_SUBSCRIPTION),
            new RefusedSubscription("removed", "libraryRemoved", REMOVED_SUBSCRIPTION));

    for (var subscription : subscriptions) {
      socket.subscribe(subscription.operationId(), subscription.document());

      var refusal = socket.nextMessageFor(subscription.operationId());
      assertThat(refusal.path("type").asString()).isEqualTo("next");
      assertThat(firstErrorCode(refusal.path("payload"))).isEqualTo(code);
      assertThat(socket.nextMessageFor(subscription.operationId()).path("type").asString())
          .isEqualTo("complete");
      assertThat(activeSubscriptions.count(subscription.field())).isZero();
    }
  }

  private record RefusedSubscription(String operationId, String field, String document) {}

  private GraphQlTransportWsTestClient statusSubscribedSocket(String bearer) {
    var socket = connect(bearer);
    socket.subscribe("status", STATUS_SUBSCRIPTION);
    activeSubscriptions.awaitCount(STATUS_FIELD, 1);
    return socket;
  }

  private GraphQlTransportWsTestClient allLibrarySubscriptionsSocket(String bearer) {
    var socket = connect(bearer);
    socket.subscribe("added", ADDED_SUBSCRIPTION);
    socket.subscribe("removed", REMOVED_SUBSCRIPTION);
    socket.subscribe("status", STATUS_SUBSCRIPTION);
    activeSubscriptions.awaitCount("libraryAdded", 1);
    activeSubscriptions.awaitCount("libraryRemoved", 1);
    activeSubscriptions.awaitCount(STATUS_FIELD, 1);
    return socket;
  }

  private static JsonNode nextAddedLibrary(GraphQlTransportWsTestClient socket) {
    return socket.nextMessageFor("added").path("payload").path("data").path("libraryAdded");
  }

  private static String removedLibraryId(GraphQlTransportWsTestClient socket) {
    return socket
        .nextMessageFor("removed")
        .path("payload")
        .path("data")
        .path("libraryRemoved")
        .path("libraryId")
        .asString();
  }

  private UUID addLibrary(String bearer, String name, Path directory) {
    var response = http.execute(bearer, addLibraryMutation(name, directory));
    var libraryId =
        UUID.fromString(
            response.path("data").path("addLibrary").path("library").path("id").asString());
    libraryIds.add(libraryId);
    return libraryId;
  }

  private Path directory(String name) throws IOException {
    return Files.createDirectories(libraryDirectory.resolve(name));
  }

  private static void awaitStatus(
      GraphQlTransportWsTestClient socket, UUID libraryId, String status) {
    while (!status.equals(nextStatusChange(socket, libraryId).path("status").asString())) {
      // Earlier transitions of the same library are skipped.
    }
  }

  private static String addLibraryMutation(String name, Path directory) {
    return """
        mutation {
          addLibrary(input: {name: "%s", filepath: "%s", type: MOVIE, backend: LOCAL}) {
            library { id name status }
            userErrors { __typename }
          }
        }
        """
        .formatted(name, directory);
  }

  private static String removeMutation(UUID libraryId) {
    return """
        mutation {
          removeLibrary(input: { libraryId: "%s" }) {
            removedLibraryId
            userErrors { __typename ... on LibraryBusyError { status } }
          }
        }
        """
        .formatted(libraryId);
  }

  private static String removedLibraryIdOf(JsonNode response) {
    return response.path("data").path("removeLibrary").path("removedLibraryId").asString();
  }

  private String adminBearer() {
    var identity = authTestSupport.createAdminIdentity();
    identities.add(identity);
    return authTestSupport.profileBearer(identity);
  }

  private String ordinaryProfileBearer() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    return authTestSupport.profileBearer(identity);
  }

  private Library saveLibrary(Path directory) {
    var library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.unsavedLibraryBuilder()
                .name("Subscribed Library")
                .filepathUri(FilepathCodec.encode(directory))
                .status(LibraryStatus.HEALTHY)
                .build());
    libraryIds.add(library.getId());
    return library;
  }

  private GraphQlTransportWsTestClient connect(String bearer) {
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, bearer);
    sockets.add(socket);
    return socket;
  }

  // The next status change of one library on the "status" operation, skipping other libraries.
  private static JsonNode nextStatusChange(GraphQlTransportWsTestClient socket, UUID libraryId) {
    while (true) {
      var change =
          socket.nextMessageFor("status").path("payload").path("data").path("libraryStatusChanged");
      if (libraryId.toString().equals(change.path("id").asString())) {
        return change;
      }
    }
  }

  // The next status change of one library, failing on any delivery that carries errors.
  private static JsonNode nextStatusChangeWithoutErrors(
      GraphQlTransportWsTestClient socket, UUID libraryId) {
    while (true) {
      var payload = socket.nextMessageFor("status").path("payload");
      assertThat(payload.path("errors").isMissingNode()).as("errors in %s", payload).isTrue();
      var change = payload.path("data").path(STATUS_FIELD);
      if (libraryId.toString().equals(change.path("id").asString())) {
        return change;
      }
    }
  }

  private static String firstItemWatchStatus(JsonNode change) {
    return change.path("items").path("edges").path(0).path("node").path("watchStatus").asString();
  }

  private Movie saveMovie(Library library) {
    return movieRepository.saveAndFlush(
        Movie.builder()
            .title("Subscribed Movie")
            .titleSort("Subscribed Movie")
            .library(library)
            .build());
  }

  private void markWatched(String bearer, Movie movie) {
    var marked =
        http.execute(
            bearer,
            "mutation { markWatched(input: {itemId: \"%s\"}) { userErrors { __typename } } }"
                .formatted(movie.getId()));
    assertThat(marked.path("data").path("markWatched").path("userErrors").isEmpty()).isTrue();
  }

  private static String scanMutation(UUID libraryId) {
    return """
        mutation {
          startLibraryScan(input: { libraryId: "%s" }) {
            library { status }
            userErrors { __typename }
          }
        }
        """
        .formatted(libraryId);
  }

  private static String stopScanMutation(UUID libraryId) {
    return """
        mutation {
          stopLibraryScan(input: { libraryId: "%s" }) {
            library { status }
            userErrors { __typename }
          }
        }
        """
        .formatted(libraryId);
  }

  private static String refreshMutation(UUID libraryId) {
    return """
        mutation {
          startLibraryRefresh(input: { libraryId: "%s" }) {
            library { status }
            userErrors { __typename }
          }
        }
        """
        .formatted(libraryId);
  }
}
