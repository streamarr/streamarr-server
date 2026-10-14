package com.streamarr.server.graphql.transport;

import com.streamarr.server.config.CanonicalBaseUrl;
import java.time.Clock;
import org.springframework.boot.graphql.autoconfigure.GraphQlProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.webmvc.GraphQlSseHandler;
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.cors.CorsConfiguration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class GraphQlTransportConfiguration {

  @Bean
  GraphQlSseHandler graphQlSseHandler(WebGraphQlHandler webGraphQlHandler) {
    return new EventStreamRefusingGraphQlSseHandler(webGraphQlHandler);
  }

  /**
   * The handshake origin rule: a browser may open the socket from the server's own origin or from
   * the configured STREAMARR_BASE_URL origin, which is what browsers send behind a TLS-terminating
   * proxy. A request without an Origin, as native clients send, passes.
   */
  @Bean
  GraphQlWebSocketHandler graphQlWebSocketHandler(
      WebGraphQlHandler webGraphQlHandler,
      GraphQlProperties graphQlProperties,
      JsonMapper jsonMapper,
      CanonicalBaseUrl canonicalBaseUrl,
      Clock clock,
      TaskScheduler taskScheduler) {
    var websocket = graphQlProperties.getWebsocket();
    var allowedOrigins = new CorsConfiguration();
    canonicalBaseUrl.origin().ifPresent(allowedOrigins::addAllowedOrigin);
    return AccessBoundGraphQlWebSocketHandler.builder()
        .graphQlHandler(webGraphQlHandler)
        .jsonConverter(new JacksonJsonHttpMessageConverter(jsonMapper))
        .connectionInitTimeout(websocket.getConnectionInitTimeout())
        .keepAlive(websocket.getKeepAlive())
        .allowedOrigins(allowedOrigins)
        .clock(clock)
        .taskScheduler(taskScheduler)
        .build();
  }
}
