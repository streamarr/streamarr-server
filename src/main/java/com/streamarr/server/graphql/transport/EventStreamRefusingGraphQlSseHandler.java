package com.streamarr.server.graphql.transport;

import java.util.List;
import java.util.Map;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.webmvc.GraphQlSseHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Refuses every {@code text/event-stream} request on /graphql before it executes. Subscriptions use
 * the WebSocket, and the framework's event-stream handler would otherwise run a query or mutation
 * before rejecting its result.
 */
public class EventStreamRefusingGraphQlSseHandler extends GraphQlSseHandler {

  public EventStreamRefusingGraphQlSseHandler(WebGraphQlHandler graphQlHandler) {
    super(graphQlHandler);
  }

  @Override
  public ServerResponse handleRequest(ServerRequest request) {
    var error = TransportRefusals.websocketRequired().toSpecification();
    return ServerResponse.status(HttpStatus.NOT_ACCEPTABLE)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("errors", List.of(error)));
  }
}
