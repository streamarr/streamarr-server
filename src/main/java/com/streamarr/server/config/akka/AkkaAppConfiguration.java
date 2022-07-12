package com.streamarr.server.config.akka;

import akka.actor.ActorSystem;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.streamarr.server.config.extension.SpringExtension.SPRING_EXTENSION_PROVIDER;

@Configuration
@RequiredArgsConstructor
public class AkkaAppConfiguration {

    private final ApplicationContext applicationContext;

    @Bean
    public ActorSystem actorSystem() {
        ActorSystem system = ActorSystem.create("akka-streamarr");
        SPRING_EXTENSION_PROVIDER.get(system)
            .initialize(applicationContext);
        return system;
    }
}
