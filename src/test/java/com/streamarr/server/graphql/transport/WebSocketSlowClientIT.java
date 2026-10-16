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
import com.streamarr.server.support.GraphQlHttpTestClient;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.file.Path;
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
import tools.jackson.databind.JsonNode;

/**
 * A socket holds a bounded number of unsent bytes. When a client stops reading and the server's
 * frames for it pile up past that bound, the server closes the socket with 4101 instead of holding
 * more.
 */
@Tag("IntegrationTest")
@DisplayName("WebSocket Slow Client Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketSlowClientIT extends AbstractIntegrationTest {

  // Operators chart this name, so the test spells it out: renaming the metric fails it.
  private static final String FELL_BEHIND_METRIC = "streamarr.graphql.websocket.client_fell_behind";
  private static final int MAX_FLOODED_OPERATIONS = 10_000;
  // The server repeats an operation's id in both frames that refuse it, so a long id fills the
  // socket buffers and the send buffer with about 50 times fewer refused operations. The server
  // executes each one, so the flood then ends well within the client's send bound on a loaded
  // machine. The id stays under Tomcat's 8 KiB text message limit.
  private static final String LONG_ID_SUFFIX = "-" + "x".repeat(6 * 1024);
  private static final int LARGE_NAME_LENGTH = 64 * 1024;
  private static final int MAX_LARGE_CHANGES = 2_000;
  private static final String STATUS_FIELD = "libraryStatusChanged";
  private static final String STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id name status } }";

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;

  @TempDir private Path libraryDirectory;

  private final List<UUID> libraryIds = new ArrayList<>();
  private AuthTestSupport.TestIdentity admin;

  @BeforeEach
  void createAdmin() {
    admin = authTestSupport.createAdminIdentity();
  }

  @AfterEach
  void deleteAdmin() {
    new ActiveSubscriptions(meterRegistry).awaitNoneActive();
    libraryIds.forEach(libraryRepository::deleteById);
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName(
      "Should close with 4101 when a client that stops reading keeps sending refused operations")
  void shouldCloseWith4101WhenClientThatStopsReadingKeepsSendingRefusedOperations() {
    try (var socket =
        GraphQlTransportWsTestClient.connectWithBearer(
            port, authTestSupport.profileBearer(admin))) {
      var closuresBefore = fellBehindClosures();
      socket.pauseReading();

      // Each refused query costs the server two frames: next with the error, then complete.
      for (var operation = 0;
          operation < MAX_FLOODED_OPERATIONS && fellBehindClosures() == closuresBefore;
          operation++) {
        socket.subscribe("refused-" + operation + LONG_ID_SUFFIX, "query { __typename }");
      }

      socket.resumeReading();
      var closure = socket.awaitClosure();
      assertThat(closure.statusCode()).isEqualTo(4101);
      assertThat(closure.reason()).isEqualTo("Client fell behind");
      assertThat(fellBehindClosures()).isEqualTo(closuresBefore + 1);
    }
  }

  @Test
  @DisplayName(
      "Should keep publishing and finish a scan when one subscriber stops reading, then let it"
          + " recover through a new socket and a query")
  void shouldKeepPublishingAndFinishScanWhenOneSubscriberStopsReading() {
    var bearer = authTestSupport.profileBearer(admin);
    var large = saveLibrary("L".repeat(LARGE_NAME_LENGTH), libraryDirectory.resolve("large"));
    var scanned = saveLibrary("Scanned Library", libraryDirectory);
    var activeSubscriptions = new ActiveSubscriptions(meterRegistry);
    try (var stalled = GraphQlTransportWsTestClient.connectWithBearer(port, bearer);
        var keepingUp = GraphQlTransportWsTestClient.connectWithBearer(port, bearer);
        var http = new GraphQlHttpTestClient(httpEndpoint())) {
      stalled.subscribe("status", STATUS_SUBSCRIPTION);
      keepingUp.subscribe("status", STATUS_SUBSCRIPTION);
      activeSubscriptions.awaitCount(STATUS_FIELD, 2);
      var closuresBefore = fellBehindClosures();
      stalled.pauseReading();

      var published = 0;
      while (fellBehindClosures() == closuresBefore && published < MAX_LARGE_CHANGES) {
        eventPublisher.publishEvent(new LibraryStatusChangedEvent(large.getId()));
        published++;
        assertThat(nextStatusChange(keepingUp, large.getId()).path("name").asString())
            .hasSize(LARGE_NAME_LENGTH);
      }

      http.execute(
          bearer,
          "mutation { startLibraryScan(input: { libraryId: \"%s\" }) { library { id } } }"
              .formatted(scanned.getId()));

      assertThat(fellBehindClosures()).isEqualTo(closuresBefore + 1);
      assertThat(nextStatusChange(keepingUp, scanned.getId()).path("status").asString())
          .isEqualTo("SCANNING");
      assertThat(nextStatusChange(keepingUp, scanned.getId()).path("status").asString())
          .isEqualTo("HEALTHY");
      stalled.resumeReading();
      assertThat(stalled.awaitClosure().statusCode()).isEqualTo(4101);

      try (var recovered = GraphQlTransportWsTestClient.connectWithBearer(port, bearer)) {
        recovered.subscribe("status", STATUS_SUBSCRIPTION);
        var current =
            http.execute(bearer, "query { libraries { id status scanCompletedOn } }")
                .path("data")
                .path("libraries");
        assertThat(current.valueStream())
            .filteredOn(node -> scanned.getId().toString().equals(node.path("id").asString()))
            .singleElement()
            .satisfies(
                node -> {
                  assertThat(node.path("status").asString()).isEqualTo("HEALTHY");
                  assertThat(node.path("scanCompletedOn").isNull()).isFalse();
                });
      }
    }
  }

  private Library saveLibrary(String name, Path directory) {
    var library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.unsavedLibraryBuilder()
                .name(name)
                .filepathUri(FilepathCodec.encode(directory))
                .status(LibraryStatus.HEALTHY)
                .build());
    libraryIds.add(library.getId());
    return library;
  }

  private URI httpEndpoint() {
    return URI.create("http://localhost:%d/graphql".formatted(port));
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

  private double fellBehindClosures() {
    var counter = meterRegistry.find(FELL_BEHIND_METRIC).counter();
    if (counter == null) {
      return 0;
    }

    return counter.count();
  }
}
