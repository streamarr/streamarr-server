package com.streamarr.server.graphql.transport;

import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

/**
 * Subscriptions run only over the graphql-transport-ws WebSocket; queries and mutations run only
 * over HTTP. Each wrong-transport request is refused before any data fetcher runs.
 */
@Tag("IntegrationTest")
@DisplayName("GraphQL Operation Transport Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GraphQlOperationTransportIT extends AbstractIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id status } }";

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MeterRegistry meterRegistry;

  private final List<UUID> libraryIds = new ArrayList<>();
  private AuthTestSupport.TestIdentity admin;
  private GraphQlHttpTestClient http;

  @BeforeEach
  void createAdmin() {
    admin = authTestSupport.createAdminIdentity();
    http = new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)));
  }

  @AfterEach
  void releaseResources() {
    http.close();
    libraryIds.forEach(libraryRepository::deleteById);
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName(
      "Should refuse a subscription with WEBSOCKET_REQUIRED when it is sent over HTTP JSON")
  void shouldRefuseSubscriptionWithWebsocketRequiredWhenSentOverHttpJson() {
    var response = http.execute(authTestSupport.profileBearer(admin), STATUS_SUBSCRIPTION);

    assertThat(firstErrorCode(response)).isEqualTo("WEBSOCKET_REQUIRED");
    assertThat(response.path("data").isMissingNode() || response.path("data").isNull()).isTrue();
    assertThat(new ActiveSubscriptions(meterRegistry).count("libraryStatusChanged")).isZero();
  }

  @Test
  @DisplayName("Should refuse an event-stream request with WEBSOCKET_REQUIRED before executing it")
  void shouldRefuseEventStreamRequestWithWebsocketRequiredBeforeExecutingIt() {
    var library = saveLibrary();

    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:%d/graphql".formatted(port)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + authTestSupport.profileBearer(admin))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(Map.of("query", removeMutation(library)))))
                .build());

    assertThat(response.statusCode()).isEqualTo(406);
    assertThat(firstErrorCode(JSON.readTree(response.body()))).isEqualTo("WEBSOCKET_REQUIRED");
    assertThat(libraryRepository.existsById(library.getId())).isTrue();
  }

  @Test
  @DisplayName("Should refuse a query with HTTP_REQUIRED when it is sent over the WebSocket")
  void shouldRefuseQueryWithHttpRequiredWhenSentOverWebSocket() {
    try (var socket = connect()) {
      socket.subscribe("query", "query { libraries { id } }");

      var refusal = socket.nextMessageFor("query");
      assertThat(refusal.path("type").asString()).isEqualTo("next");
      assertThat(firstErrorCode(refusal.path("payload"))).isEqualTo("HTTP_REQUIRED");
      assertThat(refusal.path("payload").path("data").path("libraries").isMissingNode()).isTrue();
      assertThat(socket.nextMessageFor("query").path("type").asString()).isEqualTo("complete");
    }
  }

  @Test
  @DisplayName("Should refuse a mutation with HTTP_REQUIRED before executing it over the WebSocket")
  void shouldRefuseMutationWithHttpRequiredBeforeExecutingItOverWebSocket() {
    var library = saveLibrary();

    try (var socket = connect()) {
      socket.subscribe("mutation", removeMutation(library));

      assertThat(firstErrorCode(socket.nextMessageFor("mutation").path("payload")))
          .isEqualTo("HTTP_REQUIRED");
      assertThat(libraryRepository.existsById(library.getId())).isTrue();
    }
  }

  private GraphQlTransportWsTestClient connect() {
    return GraphQlTransportWsTestClient.connectWithBearer(
        port, authTestSupport.profileBearer(admin));
  }

  private Library saveLibrary() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryIds.add(library.getId());
    return library;
  }

  private static String removeMutation(Library library) {
    return "mutation { removeLibrary(id: \"%s\") }".formatted(library.getId());
  }
}
