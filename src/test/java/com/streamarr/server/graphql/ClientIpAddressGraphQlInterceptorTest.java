package com.streamarr.server.graphql;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Client IP Address GraphQL Interceptor Tests")
class ClientIpAddressGraphQlInterceptorTest {

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
