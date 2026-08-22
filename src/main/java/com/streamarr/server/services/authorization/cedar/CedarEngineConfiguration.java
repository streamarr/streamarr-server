package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.BasicAuthorizationEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CedarEngineConfiguration {

  @Bean
  AuthorizationEngine cedarAuthorizationEngine() {
    return new BasicAuthorizationEngine();
  }
}
