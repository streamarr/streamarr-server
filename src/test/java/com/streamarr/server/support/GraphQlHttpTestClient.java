package com.streamarr.server.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Posts GraphQL documents to a running server over real HTTP, the way clients send them. */
public final class GraphQlHttpTestClient implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final URI endpoint;

  public GraphQlHttpTestClient(URI endpoint) {
    this.endpoint = endpoint;
  }

  /** Executes one document with a bearer token and returns the JSON response body. */
  public JsonNode execute(String bearerToken, String document) {
    var request =
        HttpRequest.newBuilder(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JSON.writeValueAsString(Map.of("query", document))))
            .build();
    return JSON.readTree(send(request).body());
  }

  public HttpResponse<String> send(HttpRequest request) {
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new AssertionError("The GraphQL request failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while sending a GraphQL request", e);
    }
  }

  @Override
  public void close() {
    httpClient.close();
  }
}
