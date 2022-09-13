package com.streamarr.server.config.vertx;

import io.vertx.core.Vertx;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VertxProperties.class)
public class VertxConfiguration {

    @Bean(destroyMethod = "")
    public Vertx vertx(VertxProperties properties) {
        return Vertx.vertx(properties.toVertxOptions());
    }
}
