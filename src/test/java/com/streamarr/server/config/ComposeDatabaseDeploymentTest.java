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
@DisplayName("Compose database deployment tests")
class ComposeDatabaseDeploymentTest {

  private static final String DATABASE_VOLUME = "postgres-data";

  // The PostgreSQL 18 image declares this folder as its volume and keeps PGDATA below it, in
  // /var/lib/postgresql/18/docker, so a mount at the older /var/lib/postgresql/data holds no data.
  private static final String POSTGRES_VOLUME_ROOT = "/var/lib/postgresql";

  @Test
  @DisplayName("Should keep the database in a named volume when Compose takes the services down")
  void shouldKeepTheDatabaseInANamedVolumeWhenComposeTakesTheServicesDown() throws IOException {
    var compose =
        new ObjectMapper()
            .valueToTree(new Yaml().load(Files.readString(Path.of("docker-compose.yml"))));

    assertThat(compose.path("services").path("postgres").path("volumes").values())
        .extracting(JsonNode::asString)
        .as("PostgreSQL volume mounts")
        .contains(DATABASE_VOLUME + ":" + POSTGRES_VOLUME_ROOT);
    assertThat(compose.path("volumes").has(DATABASE_VOLUME))
        .as("top-level declaration of the %s volume", DATABASE_VOLUME)
        .isTrue();
  }
}
