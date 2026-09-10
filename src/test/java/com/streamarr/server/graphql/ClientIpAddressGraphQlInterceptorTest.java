package com.streamarr.server.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.web.ClientIpAddressNormalizer;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

@Tag("UnitTest")
@DisplayName("Client IP Address GraphQL Interceptor Tests")
class ClientIpAddressGraphQlInterceptorTest {

  @Test
  @DisplayName("Should hand the resolved address to data fetchers when the request is intercepted")
  void shouldHandResolvedAddressToDataFetchersWhenRequestIsIntercepted() throws Exception {
    var address =
        InetAddress.getByAddress(
            "client.example", InetAddress.ofLiteral("203.0.113.9").getAddress());
    var environment = capturedEnvironment(new InetSocketAddress(address, 12345));

    assertThat(ClientIpAddressGraphQlInterceptor.resolve(environment)).isEqualTo("203.0.113.9");
  }

  @Test
  @DisplayName("Should capture the unspecified address when the request has no remote address")
  void shouldCaptureUnspecifiedAddressWhenRequestHasNoRemoteAddress() {
    var environment = capturedEnvironment(null);

    assertThat(ClientIpAddressGraphQlInterceptor.resolve(environment)).isEqualTo("0.0.0.0");
  }

  @Test
  @DisplayName("Should normalize the client address when the remote socket address is unresolved")
  void shouldNormalizeClientAddressWhenRemoteSocketAddressIsUnresolved() {
    var environment =
        capturedEnvironment(InetSocketAddress.createUnresolved("198.51.100.7", 12345));

    assertThat(ClientIpAddressGraphQlInterceptor.resolve(environment)).isEqualTo("198.51.100.7");
  }

  @ParameterizedTest
  @CsvSource({
    "::ffff:198.51.100.7,198.51.100.7",
    "::ffff:c633:6407,198.51.100.7",
    "2001:db8::7,2001:db8:0:0:0:0:0:7",
    "fe80::1%en0,fe80:0:0:0:0:0:0:1",
    "[fe80::1%remote-interface],fe80:0:0:0:0:0:0:1",
    "[fe80::1]%3,0.0.0.0",
    "198.51.100.7%en0,0.0.0.0",
    "localhost,0.0.0.0"
  })
  @DisplayName("Should preserve normalization when the remote socket address is unresolved")
  void shouldPreserveNormalizationWhenRemoteSocketAddressIsUnresolved(
      String remoteAddress, String expectedAddress) {
    var environment = capturedEnvironment(InetSocketAddress.createUnresolved(remoteAddress, 12345));

    assertThat(ClientIpAddressGraphQlInterceptor.resolve(environment)).isEqualTo(expectedAddress);
  }

  @Test
  @DisplayName("Should drop the scope identifier when the remote address is resolved IPv6")
  void shouldDropScopeIdentifierWhenRemoteAddressIsResolvedIpv6() throws Exception {
    var address = Inet6Address.getByAddress(null, InetAddress.ofLiteral("fe80::1").getAddress(), 3);
    var environment = capturedEnvironment(new InetSocketAddress(address, 12345));

    assertThat(ClientIpAddressGraphQlInterceptor.resolve(environment))
        .isEqualTo("fe80:0:0:0:0:0:0:1");
  }

  @ParameterizedTest
  @CsvSource({"198.51.100.7,198.51.100.7", "2001:db8::7,2001:db8:0:0:0:0:0:7"})
  @DisplayName("Should retain the captured address when a data fetcher runs on another thread")
  void shouldRetainCapturedAddressWhenDataFetcherRunsOnAnotherThread(
      String remoteAddress, String expectedAddress) throws Exception {
    var environment =
        capturedEnvironment(new InetSocketAddress(InetAddress.ofLiteral(remoteAddress), 12345));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var address = executor.submit(() -> ClientIpAddressGraphQlInterceptor.resolve(environment));

      assertThat(address.get()).isEqualTo(expectedAddress);
    }
  }

  @Test
  @DisplayName("Should fail fast when the client address was not captured for the request")
  void shouldFailFastWhenClientAddressWasNotCapturedForRequest() {
    var environment =
        DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(GraphQLContext.newContext().build())
            .build();

    assertThatThrownBy(() -> ClientIpAddressGraphQlInterceptor.resolve(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The client address was not captured for this GraphQL request.");
  }

  private static DataFetchingEnvironment capturedEnvironment(InetSocketAddress remoteAddress) {
    var interceptor = new ClientIpAddressGraphQlInterceptor(new ClientIpAddressNormalizer());
    var request =
        new WebGraphQlRequest(
            URI.create("/graphql"),
            new HttpHeaders(),
            null,
            remoteAddress,
            Map.of(),
            Map.of("query", "{ __typename }"),
            "request-1",
            null);

    interceptor.intercept(request, _ -> Mono.empty()).block();

    return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
        .graphQLContext(request.toExecutionInput().getGraphQLContext())
        .build();
  }
}
