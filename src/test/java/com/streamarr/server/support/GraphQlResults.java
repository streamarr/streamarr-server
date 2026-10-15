package com.streamarr.server.support;

import tools.jackson.databind.JsonNode;

/** Reads parts of a GraphQL execution result, whether it came over HTTP or in a socket payload. */
public final class GraphQlResults {

  private GraphQlResults() {}

  /** The {@code code} extension of the first error, or an empty string when there is none. */
  public static String firstErrorCode(JsonNode result) {
    return result.path("errors").path(0).path("extensions").path("code").asString();
  }
}
