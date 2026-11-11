package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Compose shutdown order tests")
class ComposeShutdownOrderTest {

  @Test
  @DisplayName("Should stop the server before PostgreSQL when Compose takes the services down")
  void shouldStopTheServerBeforePostgresWhenComposeTakesTheServicesDown() throws IOException {
    var services =
        new ObjectMapper()
            .valueToTree(new Yaml().load(Files.readString(Path.of("docker-compose.yml"))))
            .path("services");

    assertThat(services.has("postgres")).isTrue();
    assertThat(services.path("streamarr-server").path("depends_on").values())
        .extracting(JsonNode::asString)
        .contains("postgres");
  }
}
