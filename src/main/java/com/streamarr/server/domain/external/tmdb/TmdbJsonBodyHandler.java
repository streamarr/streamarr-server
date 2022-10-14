package com.streamarr.server.domain.external.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class TmdbJsonBodyHandler<W> implements HttpResponse.BodyHandler<W> {

    private final Class<W> targetClass;

    public TmdbJsonBodyHandler(Class<W> targetClass) {
        this.targetClass = targetClass;
    }

    @Override
    public HttpResponse.BodySubscriber<W> apply(HttpResponse.ResponseInfo responseInfo) {
        return asJSON(targetClass, responseInfo);
    }

    private static <T> HttpResponse.BodySubscriber<T> asJSON(Class<T> targetType, HttpResponse.ResponseInfo responseInfo) {
        HttpResponse.BodySubscriber<String> upstream = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);

        return HttpResponse.BodySubscribers.mapping(
            upstream,
            (String body) -> {
                try {
                    ObjectMapper objectMapper = new ObjectMapper();

                    if (responseInfo.statusCode() != 200) {
                        var failure = objectMapper.readValue(body, TmdbFailure.class);

                        throw new IOException(failure.getStatusMessage());
                    }

                    return objectMapper.readValue(body, targetType);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
    }
}