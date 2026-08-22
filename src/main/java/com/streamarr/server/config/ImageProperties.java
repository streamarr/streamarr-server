package com.streamarr.server.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "image")
public record ImageProperties(String storagePath, Duration replacementLockTimeout) {

  private static final Duration DEFAULT_REPLACEMENT_LOCK_TIMEOUT = Duration.ofSeconds(5);

  public ImageProperties(String storagePath) {
    this(storagePath, DEFAULT_REPLACEMENT_LOCK_TIMEOUT);
  }

  @ConstructorBinding
  public ImageProperties {
    if (storagePath == null || storagePath.isBlank()) {
      storagePath = Path.of(System.getProperty("java.io.tmpdir"), "streamarr-images").toString();
    }
    if (replacementLockTimeout == null) {
      replacementLockTimeout = DEFAULT_REPLACEMENT_LOCK_TIMEOUT;
    }
    if (replacementLockTimeout.compareTo(Duration.ofMillis(1)) < 0) {
      throw new IllegalArgumentException("Image replacement lock timeout must be at least 1ms");
    }
  }
}
