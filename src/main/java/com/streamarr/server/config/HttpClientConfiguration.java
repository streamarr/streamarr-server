package com.streamarr.server.config;

import com.github.mizosoft.methanol.Methanol;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
