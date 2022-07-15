package com.streamarr.server.config.vertx;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VertxWebClientProvider {

    private final Vertx vertx;

    public WebClient createHttpClient() {
        return WebClient.create(vertx);
    }
}
