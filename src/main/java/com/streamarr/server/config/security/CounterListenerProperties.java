package com.streamarr.server.config.security;

import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional dedicated endpoint for the counter notification listener. PgBouncer transaction pooling
 * does not carry LISTEN/NOTIFY, so when application traffic rides a pooler the listener needs its
 * own direct PostgreSQL route; blank values fall back to the application datasource.
 */
@Builder
@ConfigurationProperties(prefix = "auth.counter-listener")
public record CounterListenerProperties(String jdbcUrl, String username, String password) {

  public static class CounterListenerPropertiesBuilder {

    @Override
    public String toString() {
      return "CounterListenerPropertiesBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "CounterListenerProperties[REDACTED]";
  }
}
