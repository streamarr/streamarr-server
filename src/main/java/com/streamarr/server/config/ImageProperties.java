package com.streamarr.server.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "image")
public record ImageProperties(String basePath) {

  public ImageProperties {
    if (basePath == null || basePath.isBlank()) {
      basePath = Path.of(System.getProperty("java.io.tmpdir"), "streamarr-images").toString();
    }
  }
}
