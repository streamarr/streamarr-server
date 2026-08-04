package com.streamarr.server.config.security;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TomcatSecurityConfig {

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> disableHttpTrace() {
    return factory -> factory.addConnectorCustomizers(connector -> connector.setAllowTrace(false));
  }
}
