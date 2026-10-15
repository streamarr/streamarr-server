package com.streamarr.server.graphql.transport;

/** The transport a GraphQL operation arrived on. */
enum GraphQlTransport {
  /** POST /graphql: queries and mutations. */
  HTTP,
  /** graphql-transport-ws on /graphql: subscriptions. */
  WEBSOCKET
}
