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
public class GraphQlTransportInterceptor implements WebGraphQlInterceptor {

  @Override
  public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
    var transport = GraphQlTransport.HTTP;
    if (request instanceof WebSocketGraphQlRequest) {
      transport = GraphQlTransport.WEBSOCKET;
    }

    var carriedBy = transport;
    request.configureExecutionInput(
        (input, builder) ->
            builder
                .graphQLContext(context -> context.put(GraphQlTransport.class, carriedBy))
                .build());
    return chain.next(request);
  }
}
