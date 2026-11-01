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
    try {
      JsonNode compose =
          new ObjectMapper()
              .valueToTree(new Yaml().load(Files.readString(Path.of("docker-compose.yml"))));
      return compose.path("services").path(name);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
