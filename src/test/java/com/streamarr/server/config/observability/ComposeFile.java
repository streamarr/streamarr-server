package com.streamarr.server.config.observability;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ComposeFile {

  private static final Pattern VARIABLE_WITH_DEFAULT = Pattern.compile("\\$\\{[^:}]+:-([^}]*)}");

  private ComposeFile() {}

  // The value Compose gives a ${VARIABLE:-default} reference while the variable is unset.
  static String withDefaults(String value) {
    return VARIABLE_WITH_DEFAULT.matcher(value).replaceAll("$1");
  }

  static JsonNode service(String name) {
    return read().path("services").path(name);
  }

  static JsonNode volumes() {
    return read().path("volumes");
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
