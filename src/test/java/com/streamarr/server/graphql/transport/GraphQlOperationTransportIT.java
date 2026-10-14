package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.Optional;
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

  private AuthTestSupport.TestIdentity admin;
  private GraphQlHttpTestClient http;

  @BeforeEach
  void createAdmin() {
    admin = authTestSupport.createAdminIdentity();
    http = new GraphQlHttpTestClient(endpoint("http"));
  }

  @AfterEach
  void deleteAdmin() {
    http.close();
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName(
      "Should refuse a subscription with WEBSOCKET_REQUIRED when it is sent over HTTP JSON")
  void shouldRefuseSubscriptionWithWebsocketRequiredWhenSentOverHttpJson() {
    var response = http.execute(authTestSupport.profileBearer(admin), STATUS_SUBSCRIPTION);

    assertThat(response.path("errors").path(0).path("extensions").path("code").asString())
        .isEqualTo("WEBSOCKET_REQUIRED");
    assertThat(response.path("data").isMissingNode() || response.path("data").isNull()).isTrue();
    assertThat(activeStatusSubscriptions()).isZero();
  }

  @Test
  @DisplayName("Should refuse an event-stream request with WEBSOCKET_REQUIRED before executing it")
  void shouldRefuseEventStreamRequestWithWebsocketRequiredBeforeExecutingIt() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(endpoint("http"))
                  .header("Content-Type", "application/json")
                  .header("Accept", "text/event-stream")
                  .header("Authorization", "Bearer " + authTestSupport.profileBearer(admin))
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          JSON.writeValueAsString(
                              Map.of(
                                  "query",
                                  "mutation { removeLibrary(id: \"%s\") }"
                                      .formatted(library.getId())))))
                  .build());

      assertThat(response.statusCode()).isEqualTo(406);
      assertThat(
              JSON.readTree(response.body())
                  .path("errors")
                  .path(0)
                  .path("extensions")
                  .path("code")
                  .asString())
          .isEqualTo("WEBSOCKET_REQUIRED");
      assertThat(libraryRepository.existsById(library.getId())).isTrue();
    } finally {
      libraryRepository.deleteById(library.getId());
    }
  }

  @Test
  @DisplayName("Should refuse a query with HTTP_REQUIRED when it is sent over the WebSocket")
  void shouldRefuseQueryWithHttpRequiredWhenSentOverWebSocket() {
    try (var socket =
        GraphQlTransportWsTestClient.connect(
            GraphQlTransportWsTestClient.Handshake.builder()
                .endpoint(endpoint("ws"))
                .bearerToken(Optional.of(authTestSupport.profileBearer(admin)))
                .build())) {
      socket.initialize();

      socket.subscribe("query", "query { libraries { id } }");

      var refusal = socket.nextMessageFor("query");
      assertThat(refusal.path("type").asString()).isEqualTo("next");
      assertThat(
              refusal
                  .path("payload")
                  .path("errors")
                  .path(0)
                  .path("extensions")
                  .path("code")
                  .asString())
          .isEqualTo("HTTP_REQUIRED");
      assertThat(refusal.path("payload").path("data").path("libraries").isMissingNode()).isTrue();
      assertThat(socket.nextMessageFor("query").path("type").asString()).isEqualTo("complete");
    }
  }

  @Test
  @DisplayName("Should refuse a mutation with HTTP_REQUIRED before executing it over the WebSocket")
  void shouldRefuseMutationWithHttpRequiredBeforeExecutingItOverWebSocket() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    try (var socket =
        GraphQlTransportWsTestClient.connect(
            GraphQlTransportWsTestClient.Handshake.builder()
                .endpoint(endpoint("ws"))
                .bearerToken(Optional.of(authTestSupport.profileBearer(admin)))
                .build())) {
      socket.initialize();

      socket.subscribe(
          "mutation", "mutation { removeLibrary(id: \"%s\") }".formatted(library.getId()));

      assertThat(
              socket
                  .nextMessageFor("mutation")
                  .path("payload")
                  .path("errors")
                  .path(0)
                  .path("extensions")
                  .path("code")
                  .asString())
          .isEqualTo("HTTP_REQUIRED");
      assertThat(libraryRepository.existsById(library.getId())).isTrue();
    } finally {
      libraryRepository.deleteById(library.getId());
    }
  }

  private URI endpoint(String scheme) {
    return URI.create("%s://localhost:%d/graphql".formatted(scheme, port));
  }

  private double activeStatusSubscriptions() {
    return meterRegistry
        .get("streamarr.graphql.subscriptions.active")
        .tag("field", "libraryStatusChanged")
        .gauge()
        .value();
  }
}
