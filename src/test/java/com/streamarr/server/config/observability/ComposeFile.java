package com.streamarr.server.config.observability;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ComposeFile {

  private static final Pattern VARIABLE_WITH_DEFAULT = Pattern.compile("\\$\\{([^:}]+):-([^}]*)}");

  private ComposeFile() {}

  static String withDefaults(String value) {
    return withVariables(value, Map.of());
  }

  // Compose resolves ${VARIABLE:-default} to the default while the variable is unset or empty.
  static String withVariables(String value, Map<String, String> variables) {
    return VARIABLE_WITH_DEFAULT
        .matcher(value)
        .replaceAll(
            reference -> {
              var assigned = variables.getOrDefault(reference.group(1), "");
              return Matcher.quoteReplacement(assigned.isEmpty() ? reference.group(2) : assigned);
            });
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
