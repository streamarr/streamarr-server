package com.streamarr.server.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "streaming.remote")
public record RemoteTranscodeProperties(
    boolean enabled, UUID sourceNamespaceId, String sourceRoot) {

  public RemoteTranscodeProperties {
    if (enabled) {
      require(sourceNamespaceId, "Remote source namespace ID is required");
      requireText(sourceRoot, "Remote source root is required");
    }
  }

  private static void require(Object value, String message) {
    if (value == null) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }
}
