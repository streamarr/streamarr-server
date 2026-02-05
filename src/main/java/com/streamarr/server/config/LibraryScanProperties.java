package com.streamarr.server.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "library.scan")
public record LibraryScanProperties(
    List<String> additionalIgnoredFilenames,
    List<String> additionalIgnoredExtensions,
    List<String> additionalIgnoredPrefixes) {

  public LibraryScanProperties {
    if (additionalIgnoredFilenames == null) {
      additionalIgnoredFilenames = List.of();
    }
    if (additionalIgnoredExtensions == null) {
      additionalIgnoredExtensions = List.of();
    }
    if (additionalIgnoredPrefixes == null) {
      additionalIgnoredPrefixes = List.of();
    }
  }
}
