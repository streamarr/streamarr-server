package com.streamarr.server.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "streaming.remote")
public record RemoteTranscodeProperties(
    boolean enabled,
    int port,
    String trustDomain,
    UUID sourceNamespaceId,
    String sourceRoot,
    String certificate,
    String privateKey,
    String trustBundle) {

  public RemoteTranscodeProperties {
    if (enabled) {
      require(sourceNamespaceId, "Remote source namespace ID is required");
      requireText(sourceRoot, "Remote source root is required");
      requireText(certificate, "Remote TLS certificate is required");
      requireText(privateKey, "Remote TLS private key is required");
      requireText(trustBundle, "Remote TLS trust bundle is required");
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
