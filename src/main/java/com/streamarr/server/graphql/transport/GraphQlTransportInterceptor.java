package com.streamarr.server.graphql.transport;

import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Records which transport carried each operation, for {@link OperationTransportInstrumentation}.
 */
@Component
class GraphQlTransportInterceptor implements WebGraphQlInterceptor {

  @Override
  public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
    var transport = transportOf(request);
    request.configureExecutionInput(
        (input, builder) ->
            builder
                .graphQLContext(context -> context.put(GraphQlTransport.class, transport))
                .build());
    return chain.next(request);
  }

  private static GraphQlTransport transportOf(WebGraphQlRequest request) {
    if (request instanceof WebSocketGraphQlRequest) {
      return GraphQlTransport.WEBSOCKET;
    }

    return GraphQlTransport.HTTP;
  }
}
