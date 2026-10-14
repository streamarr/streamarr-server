package com.streamarr.server.graphql.transport;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.webmvc.GraphQlSseHandler;

@Configuration(proxyBeanMethods = false)
class GraphQlTransportConfiguration {

  @Bean
  GraphQlSseHandler graphQlSseHandler(WebGraphQlHandler webGraphQlHandler) {
    return new EventStreamRefusingGraphQlSseHandler(webGraphQlHandler);
  }
}
