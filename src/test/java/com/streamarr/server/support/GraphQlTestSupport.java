package com.streamarr.server.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

public final class GraphQlTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  private GraphQlTestSupport() {}

  /** A bearer-authenticated POST /graphql carrying one document. */
  public static MockHttpServletRequestBuilder graphqlRequest(String bearer, String query) {
    return post("/graphql")
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
        .content(JSON.writeValueAsString(Map.of("query", query)));
  }
}
