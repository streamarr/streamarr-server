package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

  private static final String STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id name status scanStartedOn scanCompletedOn } }";

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private UserAccountRepository userAccountRepository;

  @TempDir private Path libraryDirectory;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private final List<UUID> libraryIds = new ArrayList<>();
  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();
  private GraphQlHttpTestClient http;

  @BeforeEach
  void openHttpClient() {
    http = new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)));
  }

  @AfterEach
  void releaseResources() {
    sockets.forEach(GraphQlTransportWsTestClient::close);
    http.close();
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
    var socket = connect(bearer);
    socket.subscribe("status", STATUS_SUBSCRIPTION);
    awaitActiveSubscriptions("libraryStatusChanged", 1);

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
    var socket = subscribedSocket(bearer, "status", STATUS_SUBSCRIPTION, "libraryStatusChanged");

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
    var socket = subscribedSocket(bearer, "status", STATUS_SUBSCRIPTION, "libraryStatusChanged");

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
    assertThat(
            refusal
                .path("payload")
                .path("errors")
                .path(0)
                .path("extensions")
                .path("code")
                .asString())
        .isEqualTo("FORBIDDEN");
    assertThat(socket.nextMessageFor("refused").path("type").asString()).isEqualTo("complete");
    assertThat(activeSubscriptions("libraryStatusChanged")).isZero();
  }

  private GraphQlTransportWsTestClient subscribedSocket(
      String bearer, String operationId, String document, String field) {
    var socket = connect(bearer);
    socket.subscribe(operationId, document);
    awaitActiveSubscriptions(field, 1);
    return socket;
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
    var socket =
        GraphQlTransportWsTestClient.connect(
            GraphQlTransportWsTestClient.Handshake.builder()
                .endpoint(URI.create("ws://localhost:%d/graphql".formatted(port)))
                .bearerToken(Optional.of(bearer))
                .build());
    sockets.add(socket);
    return socket.initialize();
  }

  // Registration has no readiness message (ADR 0042), so the test waits on the public gauge.
  private void awaitActiveSubscriptions(String field, int expected) {
    await().atMost(Duration.ofSeconds(10)).until(() -> activeSubscriptions(field) == expected);
  }

  private double activeSubscriptions(String field) {
    var gauge =
        meterRegistry.find("streamarr.graphql.subscriptions.active").tag("field", field).gauge();
    if (gauge == null) {
      return 0;
    }

    return gauge.value();
  }

  private static JsonNode nextStatusChange(GraphQlTransportWsTestClient socket, UUID libraryId) {
    return socket
        .nextMessageMatching(
            message ->
                "next".equals(message.path("type").asString())
                    && libraryId
                        .toString()
                        .equals(
                            message
                                .path("payload")
                                .path("data")
                                .path("libraryStatusChanged")
                                .path("id")
                                .asString()))
        .path("payload")
        .path("data")
        .path("libraryStatusChanged");
  }

  private static String scanMutation(UUID libraryId) {
    return "mutation { scanLibrary(id: \"%s\") }".formatted(libraryId);
  }
}
