package com.streamarr.server.services.metadata.tmdb;

import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.ObjectMapper;

public class TmdbJsonBodyHandler<W> implements HttpResponse.BodyHandler<W> {

  private final Class<W> targetClass;
  private final ObjectMapper objectMapper;

  public TmdbJsonBodyHandler(Class<W> targetClass, ObjectMapper objectMapper) {
    this.targetClass = targetClass;
    this.objectMapper = objectMapper;
  }

  @Override
  public HttpResponse.BodySubscriber<W> apply(HttpResponse.ResponseInfo responseInfo) {
    return asJSON(targetClass, objectMapper, responseInfo);
  }

  private static <T> HttpResponse.BodySubscriber<T> asJSON(
      Class<T> targetType, ObjectMapper objectMapper, HttpResponse.ResponseInfo responseInfo) {
    HttpResponse.BodySubscriber<String> upstream =
        HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);

    return HttpResponse.BodySubscribers.mapping(
        upstream,
        (String body) -> {
          if (responseInfo.statusCode() != 200) {
            var failure = objectMapper.readValue(body, TmdbFailure.class);
            throw new UncheckedIOException(
                new TmdbApiException(responseInfo.statusCode(), failure.getStatusMessage()));
          }

          return objectMapper.readValue(body, targetType);
        });
  }
}
