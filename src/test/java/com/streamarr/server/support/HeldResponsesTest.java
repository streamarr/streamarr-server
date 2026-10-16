package com.streamarr.server.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.streamarr.server.support.HeldResponses.held;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Held Responses Tests")
class HeldResponsesTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  private final HeldResponses heldResponses = new HeldResponses();
  private final WireMockServer server =
      new WireMockServer(wireMockConfig().dynamicPort().extensions(heldResponses));
  private HttpClient client;

  @BeforeEach
  void startServer() {
    server.start();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    heldResponses.reset();
    client.close();
    server.stop();
  }

  @Test
  @DisplayName("Should hold a marked stub's response until the test releases it")
  void shouldHoldAMarkedStubResponseUntilTheTestReleasesIt() throws Exception {
    server.stubFor(held(get(urlPathEqualTo("/held")).willReturn(aResponse().withStatus(200))));

    var response = request("/held");
    heldResponses.awaitHeldResponses(1, BOUND);

    assertThat(response).isNotDone();
    heldResponses.release();
    assertThat(statusOf(response)).isEqualTo(200);
  }

  @Test
  @DisplayName("Should send an unmarked stub's response while a marked one is held")
  void shouldSendAnUnmarkedStubResponseWhileAMarkedOneIsHeld() throws Exception {
    server.stubFor(held(get(urlPathEqualTo("/held")).willReturn(aResponse().withStatus(200))));
    server.stubFor(get(urlPathEqualTo("/sent")).willReturn(aResponse().withStatus(204)));
    var heldResponse = request("/held");
    heldResponses.awaitHeldResponses(1, BOUND);

    assertThat(statusOf(request("/sent"))).isEqualTo(204);
    assertThat(heldResponse).isNotDone();
  }

  @Test
  @DisplayName("Should send the held response and hold the next one when reset")
  void shouldSendTheHeldResponseAndHoldTheNextOneWhenReset() throws Exception {
    server.stubFor(held(get(urlPathEqualTo("/held")).willReturn(aResponse().withStatus(200))));
    var first = request("/held");
    heldResponses.awaitHeldResponses(1, BOUND);

    heldResponses.reset();

    assertThat(statusOf(first)).isEqualTo(200);
    var second = request("/held");
    heldResponses.awaitHeldResponses(1, BOUND);
    assertThat(second).isNotDone();
  }

  @Test
  @DisplayName("Should fail when fewer responses are held within the bound")
  void shouldFailWhenFewerResponsesAreHeldWithinTheBound() {
    assertThatThrownBy(() -> heldResponses.awaitHeldResponses(1, Duration.ofMillis(50)))
        .isInstanceOf(AssertionError.class)
        .hasMessage("0 of 1 responses were held within PT0.05S");
  }

  private CompletableFuture<HttpResponse<Void>> request(String path) {
    return client.sendAsync(
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private static int statusOf(CompletableFuture<HttpResponse<Void>> response) throws Exception {
    return response.get(BOUND.toMillis(), TimeUnit.MILLISECONDS).statusCode();
  }
}
