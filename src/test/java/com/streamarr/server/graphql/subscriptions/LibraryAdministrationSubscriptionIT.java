package com.streamarr.server.graphql.subscriptions;

import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
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
@DisplayName("Library Administration Subscription Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LibraryAdministrationSubscriptionIT extends AbstractIntegrationTest {

  private static final String STATUS_FIELD = "libraryStatusChanged";
  private static final String STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id name status scanStartedOn scanCompletedOn } }";
  private static final String ADDED_SUBSCRIPTION =
      "subscription { libraryAdded { id name status scanStartedOn scanCompletedOn } }";
  private static final String REMOVED_SUBSCRIPTION =
      "subscription { libraryRemoved { libraryId } }";

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MeterRegistry meterRegistry;
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

    assertThat(accepted.path("data").path("scanLibrary").asBoolean()).isTrue();
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

    var accepted =
        http.execute(bearer, "mutation { refreshLibrary(id: \"%s\") }".formatted(library.getId()));

    assertThat(accepted.path("data").path("refreshLibrary").asBoolean()).isTrue();
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
    var socket = libraryAdministrationSocket(adminBearer());
    var otherSession = adminBearer();

    var libraryId = addLibrary(otherSession, "Added Library", directory("added"));

    var added = nextAddedLibrary(socket);
    assertThat(added.path("id").asString()).isEqualTo(libraryId.toString());
    assertThat(added.path("name").asString()).isEqualTo("Added Library");
    assertThat(added.path("status").asString()).isEqualTo("SCANNING");
    awaitStatus(socket, libraryId, "HEALTHY");

    var removed = http.execute(otherSession, removeMutation(libraryId));

    assertThat(removed.path("data").path("removeLibrary").asBoolean()).isTrue();
    assertThat(removedLibraryId(socket)).isEqualTo(libraryId.toString());
  }

  @Test
  @DisplayName("Should deliver only the later addition when a refused addition comes first")
  void shouldDeliverOnlyLaterAdditionWhenRefusedAdditionComesFirst() throws IOException {
    var admin = adminBearer();
    var socket = libraryAdministrationSocket(admin);
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
    var socket = libraryAdministrationSocket(admin);

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
    var socket = libraryAdministrationSocket(admin);
    markStatus(busy, LibraryStatus.SCANNING);

    var refused = http.execute(admin, removeMutation(busy.getId()));
    var removed = http.execute(admin, removeMutation(sentinel.getId()));

    assertThat(refused.path("errors").isEmpty()).isFalse();
    assertThat(libraryRepository.findById(busy.getId())).isPresent();
    assertThat(removed.path("data").path("removeLibrary").asBoolean()).isTrue();
    assertThat(removedLibraryId(socket)).isEqualTo(sentinel.getId().toString());
  }

  @Test
  @DisplayName(
      "Should refuse the subscription with FORBIDDEN when the Account is not a ServerAdmin")
  void shouldRefuseSubscriptionWithForbiddenWhenAccountIsNotServerAdmin() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);

    assertRefusedWithForbidden(authTestSupport.profileBearer(identity));
  }

  @Test
  @DisplayName("Should refuse the subscription with FORBIDDEN when the session is device-bound")
  void shouldRefuseSubscriptionWithForbiddenWhenSessionIsDeviceBound() {
    var identity = authTestSupport.createAdminIdentity();
    identities.add(identity);

    assertRefusedWithForbidden(authTestSupport.deviceBoundProfileBearer(identity));
  }

  @Test
  @DisplayName("Should refuse the subscription with FORBIDDEN when the Account is disabled")
  void shouldRefuseSubscriptionWithForbiddenWhenAccountIsDisabled() {
    var identity = authTestSupport.createAdminIdentity();
    identities.add(identity);
    var bearer = authTestSupport.profileBearer(identity);
    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);

    assertRefusedWithForbidden(bearer);
  }

  private void assertRefusedWithForbidden(String bearer) {
    var socket = connect(bearer);

    socket.subscribe("refused", STATUS_SUBSCRIPTION);

    var refusal = socket.nextMessageFor("refused");
    assertThat(refusal.path("type").asString()).isEqualTo("next");
    assertThat(firstErrorCode(refusal.path("payload"))).isEqualTo("FORBIDDEN");
    assertThat(socket.nextMessageFor("refused").path("type").asString()).isEqualTo("complete");
    assertThat(activeSubscriptions.count(STATUS_FIELD)).isZero();
  }

  private GraphQlTransportWsTestClient statusSubscribedSocket(String bearer) {
    var socket = connect(bearer);
    socket.subscribe("status", STATUS_SUBSCRIPTION);
    activeSubscriptions.awaitCount(STATUS_FIELD, 1);
    return socket;
  }

  private GraphQlTransportWsTestClient libraryAdministrationSocket(String bearer) {
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

  private void markStatus(Library library, LibraryStatus status) {
    library.setStatus(status);
    libraryRepository.saveAndFlush(library);
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
    return "mutation { removeLibrary(id: \"%s\") }".formatted(libraryId);
  }

  private String adminBearer() {
    var identity = authTestSupport.createAdminIdentity();
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

  private static String scanMutation(UUID libraryId) {
    return "mutation { scanLibrary(id: \"%s\") }".formatted(libraryId);
  }
}
