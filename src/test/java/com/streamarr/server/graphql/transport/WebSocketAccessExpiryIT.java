package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.context.ApplicationEventPublisher;

/**
 * A socket lives no longer than the access token that opened it. At expiry the server closes it
 * with the private code 4100 and sends no {@code complete} first, so clients restore their
 * operations on a new socket with a fresh token.
 */
@Tag("IntegrationTest")
@DisplayName("WebSocket Access Expiry Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketAccessExpiryIT extends AbstractIntegrationTest {

  private static final String STATUS_FIELD = "libraryStatusChanged";
  private static final String STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id administration { status } } }";

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;

  @TempDir private Path libraryDirectory;

  private final List<UUID> libraryIds = new ArrayList<>();
  private AuthTestSupport.TestIdentity admin;
  private ActiveSubscriptions activeSubscriptions;

  @BeforeEach
  void createAdmin() {
    admin = authTestSupport.createAdminIdentity();
    activeSubscriptions = new ActiveSubscriptions(meterRegistry);
  }

  @AfterEach
  void deleteAdmin() {
    activeSubscriptions.awaitNoneActive();
    libraryIds.forEach(libraryRepository::deleteById);
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName("Should close with 4100 and no complete frame when the opening access token expires")
  void shouldCloseWith4100AndNoCompleteFrameWhenOpeningAccessTokenExpires() {
    var expiresAt = Instant.now().plusSeconds(3).truncatedTo(ChronoUnit.SECONDS);
    var bearer = authTestSupport.profileBearerExpiringAt(admin, expiresAt);

    try (var socket = GraphQlTransportWsTestClient.connectWithBearer(port, bearer)) {
      socket.subscribe("status", STATUS_SUBSCRIPTION);
      activeSubscriptions.awaitCount(STATUS_FIELD, 1);

      var closure = socket.awaitClosure();

      assertThat(closure.statusCode()).isEqualTo(4100);
      assertThat(closure.reason()).isEqualTo("Access token expired");
      assertThat(Instant.now()).isAfterOrEqualTo(expiresAt);
      assertThat(socket.receivedMessages())
          .noneMatch(message -> "complete".equals(message.path("type").asString()));
      activeSubscriptions.awaitCount(STATUS_FIELD, 0);
    }
  }

  @Test
  @DisplayName(
      "Should deliver status changes on a new socket when the client reconnects with a fresh"
          + " token after 4100")
  void shouldDeliverStatusChangesOnNewSocketWhenClientReconnectsWithFreshTokenAfter4100() {
    var library = saveLibrary();
    var expiresAt = Instant.now().plusSeconds(3).truncatedTo(ChronoUnit.SECONDS);
    try (var expiring =
        GraphQlTransportWsTestClient.connectWithBearer(
            port, authTestSupport.profileBearerExpiringAt(admin, expiresAt))) {
      expiring.subscribe("status", STATUS_SUBSCRIPTION);
      assertThat(expiring.awaitClosure().statusCode()).isEqualTo(4100);
    }

    activeSubscriptions.awaitCount(STATUS_FIELD, 0);
    try (var renewed =
        GraphQlTransportWsTestClient.connectWithBearer(
            port, authTestSupport.profileBearer(admin))) {
      renewed.subscribe("status", STATUS_SUBSCRIPTION);
      activeSubscriptions.awaitCount(STATUS_FIELD, 1);

      eventPublisher.publishEvent(new LibraryStatusChangedEvent(library.getId()));

      var change = renewed.nextMessageFor("status").path("payload").path("data").path(STATUS_FIELD);
      assertThat(change.path("id").asString()).isEqualTo(library.getId().toString());
      assertThat(change.path("administration").path("status").asString()).isEqualTo("HEALTHY");
    }
  }

  private Library saveLibrary() {
    var library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.unsavedLibraryBuilder()
                .filepathUri(FilepathCodec.encode(libraryDirectory))
                .status(LibraryStatus.HEALTHY)
                .build());
    libraryIds.add(library.getId());
    return library;
  }
}
