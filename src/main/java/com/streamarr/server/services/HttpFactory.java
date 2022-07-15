package com.streamarr.server.services;

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpFactory {

    private final ActorSystem system;

    public Http createHttpClient() {
        return Http.get(system);
    }
}
