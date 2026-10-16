package com.streamarr.server.graphql.transport;

import com.streamarr.server.config.CanonicalBaseUrl;
import com.streamarr.server.graphql.subscriptions.SubscriptionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
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

  // A browser may open the socket from the server's own origin or from the STREAMARR_BASE_URL
  // origin, which browsers send behind a TLS-terminating proxy. Native clients send no Origin.
  @Bean
  GraphQlWebSocketHandler graphQlWebSocketHandler(
      WebGraphQlHandler webGraphQlHandler,
      GraphQlProperties graphQlProperties,
      JsonMapper jsonMapper,
      CanonicalBaseUrl canonicalBaseUrl,
      Clock clock,
      TaskScheduler taskScheduler,
      SubscriptionProperties subscriptionProperties,
      MeterRegistry meterRegistry) {
    var websocket = graphQlProperties.getWebsocket();
    // Not a bean: an Executor bean would replace the application's task executor.
    var sendThreads = Thread.ofVirtual().name("graphql-websocket-send-", 0).factory();
    Executor sendExecutor = task -> sendThreads.newThread(task).start();
    var allowedOrigins = new CorsConfiguration();
    canonicalBaseUrl.origin().ifPresent(allowedOrigins::addAllowedOrigin);
    return AccessBoundGraphQlWebSocketHandler.builder()
        .graphQlHandler(webGraphQlHandler)
        .jsonConverter(new JacksonJsonHttpMessageConverter(jsonMapper))
        .connectionInitTimeout(websocket.getConnectionInitTimeout())
        .keepAlive(requireKeepAlive(websocket))
        .allowedOrigins(allowedOrigins)
        .clock(clock)
        .taskScheduler(taskScheduler)
        .sendBuffer(
            SendBufferSettings.builder()
                .sendExecutor(sendExecutor)
                .capacity(subscriptionProperties.connectionSendBuffer())
                .meterRegistry(meterRegistry)
                .build())
        .build();
  }

  // Spring reads a missing keep-alive as "send no pings", so idle sockets meet proxy timeouts.
  private static Duration requireKeepAlive(GraphQlProperties.Websocket websocket) {
    var keepAlive = websocket.getKeepAlive();
    if (keepAlive == null) {
      throw new InvalidConfigurationPropertyValueException(
          "spring.graphql.websocket.keep-alive",
          null,
          "A keep-alive is required. Set GRAPHQL_WEBSOCKET_KEEP_ALIVE to a duration such as 20s.");
    }

    return keepAlive;
  }
}
