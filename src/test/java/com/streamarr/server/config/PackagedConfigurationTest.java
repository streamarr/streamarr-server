package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Packaged Configuration Tests")
class PackagedConfigurationTest {

  /**
   * The packaged artifact must never choose its own runtime profile: a default boot that silently
   * activates dev would seed a known admin credential on every fresh install. Dev is activated by
   * the spring-boot-maven-plugin run configuration instead.
   */
  @Test
  @DisplayName("Should not activate any profile when packaged configuration loads")
  void shouldNotActivateAnyProfileWhenPackagedConfigurationLoads() throws IOException {
    try (var input = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
      Map<String, Object> root = new Yaml().load(input);

      @SuppressWarnings("unchecked")
      var spring = (Map<String, Object>) root.get("spring");

      assertThat(spring).doesNotContainKey("profiles");
    }
  }
}
