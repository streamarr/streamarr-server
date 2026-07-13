package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.Argon2Properties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Packaged Configuration Tests")
class PackagedConfigurationTest {

  private static final int MIN_MEMORY_KIB = 19_456;
  private static final int MIN_ITERATIONS = 2;
  private static final int MIN_PARALLELISM = 1;

  private static final ApplicationContextRunner CONTEXT_RUNNER =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(PackagedConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(Argon2Properties.class)
  static class PackagedConfiguration {}

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

  @Test
  @DisplayName("Should ship Argon2 defaults that meet the OWASP floor")
  void shouldShipArgon2DefaultsThatMeetOwaspFloor() {
    CONTEXT_RUNNER.run(
        context -> {
          assertThat(context).hasNotFailed();
          var properties = context.getBean(Argon2Properties.class);

          assertThat(properties.memoryKib()).isGreaterThanOrEqualTo(MIN_MEMORY_KIB);
          assertThat(properties.iterations()).isGreaterThanOrEqualTo(MIN_ITERATIONS);
          assertThat(properties.parallelism()).isGreaterThanOrEqualTo(MIN_PARALLELISM);
        });
  }

  @Test
  @DisplayName("Should ship separate server and transcode worker process types")
  void shouldShipSeparateServerAndTranscodeWorkerProcessTypes() throws IOException {
    var processes = Set.copyOf(Files.readAllLines(Path.of("Procfile")));

    assertThat(processes)
        .contains(
            "web: java org.springframework.boot.loader.launch.JarLauncher",
            "worker: java -Dloader.main=com.streamarr.transcode.worker.TranscodeWorkerApplication "
                + "org.springframework.boot.loader.launch.PropertiesLauncher");
  }
}
