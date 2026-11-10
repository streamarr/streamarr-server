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

/**
 * Compose restarts a crashed server in the same container, so the segment folder keeps what the
 * crashed process left. The server deletes leftovers only in a segment folder of its own.
 */
@Tag("UnitTest")
@DisplayName("Segment folder deployment tests")
class SegmentFolderDeploymentTest {

  @Test
  @DisplayName("Should give the Compose server a segment folder of its own")
  void shouldGiveTheComposeServerASegmentFolderOfItsOwn() throws IOException {
    var environment =
        yaml(Path.of("docker-compose.yml"))
            .path("services")
            .path("streamarr-server")
            .path("environment");
    var configured = environment.path("STREAMING_SEGMENT_BASE_PATH").asString();

    var properties = StreamingProperties.builder().segmentBasePath(configured).build();

    assertThat(properties.ownedSegmentFolder()).contains(Path.of("/tmp/streamarr-server/segments"));
  }

  private static JsonNode yaml(Path path) throws IOException {
    return new ObjectMapper().valueToTree(new Yaml().load(Files.readString(path)));
  }
}
