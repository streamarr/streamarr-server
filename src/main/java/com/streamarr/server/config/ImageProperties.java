package com.streamarr.server.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "image")
public record ImageProperties(String storagePath) {

  public ImageProperties {
    if (storagePath == null || storagePath.isBlank()) {
      storagePath = Path.of(System.getProperty("java.io.tmpdir"), "streamarr-images").toString();
    }
  }
}
