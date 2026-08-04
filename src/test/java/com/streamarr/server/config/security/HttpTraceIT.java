package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@Tag("IntegrationTest")
@DisplayName("HTTP Trace Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpTraceIT extends AbstractIntegrationTest {

  @LocalServerPort private int port;

  @Test
  @DisplayName("Should reject trace when a request targets the application")
  void shouldRejectTraceWhenRequestTargetsApplication() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:%d/api/auth/status".formatted(port)))
            .header("Cookie", AuthCookies.ACCESS_COOKIE + "=sensitive-token")
            .method("TRACE", HttpRequest.BodyPublishers.noBody())
            .build();

    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
      assertThat(response.headers().firstValue("Allow"))
          .hasValueSatisfying(allowedMethods -> assertThat(allowedMethods).doesNotContain("TRACE"));
      assertThat(response.body()).doesNotContain("sensitive-token");
    }
  }
}
