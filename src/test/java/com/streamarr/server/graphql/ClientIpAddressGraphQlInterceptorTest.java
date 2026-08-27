package com.streamarr.server.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.web.ClientIpAddressResolver;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import reactor.core.publisher.Mono;

@Tag("UnitTest")
@DisplayName("Client IP Address GraphQL Interceptor Tests")
class ClientIpAddressGraphQlInterceptorTest {

  @Test
  @DisplayName("Should hand the resolved address to data fetchers when the request is intercepted")
  void shouldHandResolvedAddressToDataFetchersWhenRequestIsIntercepted() {
    var servletRequest = new MockHttpServletRequest();
    servletRequest.setRemoteAddr("198.51.100.7");
    var interceptor =
        new ClientIpAddressGraphQlInterceptor(new ClientIpAddressResolver(servletRequest));
    var request =
        new WebGraphQlRequest(
            URI.create("/graphql"),
            new HttpHeaders(),
            null,
            null,
            Map.of(),
            Map.of("query", "{ __typename }"),
            "request-1",
            null);

    interceptor.intercept(request, ignored -> Mono.empty()).block();

    var environment =
        DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(request.toExecutionInput().getGraphQLContext())
            .build();
    assertThat(ClientIpAddressGraphQlInterceptor.resolve(environment)).isEqualTo("198.51.100.7");
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
}
