package com.streamarr.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

@Tag("UnitTest")
@DisplayName("Persistence Boundary Configuration Tests")
class PersistenceBoundaryConfigurationTest {

  @Test
  @DisplayName("Should close the persistence context before web response rendering")
  void shouldClosePersistenceContextBeforeWebResponseRendering() throws IOException {
    var sources =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));

    assertThat(sources.getFirst().getProperty("spring.jpa.open-in-view")).isEqualTo(false);
  }
}
