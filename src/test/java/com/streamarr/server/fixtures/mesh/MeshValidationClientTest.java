package com.streamarr.server.fixtures.mesh;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Mesh Validation Client Tests")
class MeshValidationClientTest {

  @Test
  @DisplayName("Should reject a TLS enforcement claim when the target cannot resolve")
  void shouldRejectTlsEnforcementClaimWhenTargetCannotResolve() {
    assertThatThrownBy(
            () ->
                MeshValidationClient.main(
                    new String[] {
                      "tls-required", "http://missing-mesh-validation.invalid:8080/health"
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  @DisplayName("Should confirm HTTP denial when the endpoint returns forbidden")
  void shouldConfirmHttpDenialWhenTheEndpointReturnsForbidden() throws Exception {
    try (var endpoint = HttpEndpoint.builder().status(403).build()) {
      var arguments = new String[] {"http-forbidden", endpoint.address()};

      assertThatCode(() -> MeshValidationClient.main(arguments)).doesNotThrowAnyException();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 401, 500})
  @DisplayName("Should reject HTTP denial when only the response body claims forbidden")
  void shouldRejectHttpDenialWhenOnlyTheResponseBodyClaimsForbidden(int status) throws Exception {
    try (var endpoint = HttpEndpoint.builder().status(status).build()) {
      var arguments = new String[] {"http-forbidden", endpoint.address()};

      assertThatThrownBy(() -> MeshValidationClient.main(arguments))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(Integer.toString(status));
    }
  }

  @DisplayName("HTTP Endpoint")
  private static final class HttpEndpoint implements AutoCloseable {
    private final HttpServer server;

    @Builder
    private HttpEndpoint(int status) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/health",
          exchange -> {
            try (exchange) {
              var body = "HTTP exchange failed: 403".getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(status, body.length);
              exchange.getResponseBody().write(body);
            }
          });
      server.start();
    }

    private String address() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/health";
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
