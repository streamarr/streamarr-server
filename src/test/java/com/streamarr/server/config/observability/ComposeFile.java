package com.streamarr.server.config.observability;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ComposeFile {

  private ComposeFile() {}

  static JsonNode service(String name) {
    return read().path("services").path(name);
  }

  private static JsonNode read() {
    try {
      return new ObjectMapper()
          .valueToTree(new Yaml().load(Files.readString(Path.of("docker-compose.yml"))));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
