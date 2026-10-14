package com.streamarr.server.graphql.transport;

import com.netflix.graphql.types.errors.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.util.Map;

/** The documented errors for an operation sent over the wrong transport. */
final class TransportRefusals {

  static final String WEBSOCKET_REQUIRED = "WEBSOCKET_REQUIRED";
  static final String HTTP_REQUIRED = "HTTP_REQUIRED";

  private TransportRefusals() {}

  static GraphQLError websocketRequired() {
    return refusal(
        "Subscriptions use the graphql-transport-ws WebSocket on /graphql.", WEBSOCKET_REQUIRED);
  }

  static GraphQLError httpRequired() {
    return refusal("Queries and mutations use HTTP POST on /graphql.", HTTP_REQUIRED);
  }

  private static GraphQLError refusal(String message, String code) {
    return GraphqlErrorBuilder.newError()
        .message(message)
        .errorType(ErrorType.BAD_REQUEST)
        .extensions(Map.of("errorType", ErrorType.BAD_REQUEST.name(), "code", code))
        .build();
  }
}
