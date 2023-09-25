package com.streamarr.server.config;

import com.github.mizosoft.methanol.Methanol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class HttpClientConfiguration {

    @Bean
    HttpClient httpClient() {
        return Methanol.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }
}
