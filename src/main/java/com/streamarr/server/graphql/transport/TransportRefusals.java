package com.streamarr.server.graphql.transport;

import com.netflix.graphql.types.errors.ErrorType;
import com.streamarr.server.graphql.CodedGraphQlErrors;
import graphql.GraphQLError;

/** The documented errors for an operation sent over the wrong transport. */
final class TransportRefusals {

  private TransportRefusals() {}

  static GraphQLError websocketRequired() {
    return CodedGraphQlErrors.withCode(
        ErrorType.BAD_REQUEST,
        "WEBSOCKET_REQUIRED",
        "Subscriptions use the graphql-transport-ws WebSocket on /graphql.");
  }

  static GraphQLError httpRequired() {
    return CodedGraphQlErrors.withCode(
        ErrorType.BAD_REQUEST, "HTTP_REQUIRED", "Queries and mutations use HTTP POST on /graphql.");
  }
}
