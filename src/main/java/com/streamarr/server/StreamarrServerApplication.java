package com.streamarr.server;

import com.streamarr.server.config.vertx.VertxVerticleFactory;
import com.streamarr.server.services.metadata.ImageThumbnailWorkerVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication
@RequiredArgsConstructor
public class StreamarrServerApplication {

    private final Vertx vertx;
    private final VertxVerticleFactory verticleFactory;

    public static void main(String[] args) {
        SpringApplication.run(StreamarrServerApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void deployVerticles() {

        vertx.registerVerticleFactory(verticleFactory);

        vertx.deployVerticle(verticleFactory.prefix() + ":" + ImageThumbnailWorkerVerticle.class.getName(), new DeploymentOptions().setWorker(true));
    }

}
