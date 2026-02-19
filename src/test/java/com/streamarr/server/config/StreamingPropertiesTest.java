package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Streaming Properties Tests")
class StreamingPropertiesTest {

  @Test
  @DisplayName("Should default segment duration to 6 seconds when null")
  void shouldDefaultSegmentDurationToSixSecondsWhenNull() {
    var properties = StreamingProperties.builder().build();

    assertThat(properties.segmentDuration()).isEqualTo(Duration.ofSeconds(6));
  }

  @Test
  @DisplayName("Should default session timeout to 60 seconds when null")
  void shouldDefaultSessionTimeoutToSixtySecondsWhenNull() {
    var properties = StreamingProperties.builder().build();

    assertThat(properties.sessionTimeout()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  @DisplayName("Should default segment base path when null")
  void shouldDefaultSegmentBasePathWhenNull() {
    var properties = StreamingProperties.builder().build();

    assertThat(properties.segmentBasePath()).contains("streamarr-segments");
  }

  @Test
  @DisplayName("Should default segment base path when blank")
  void shouldDefaultSegmentBasePathWhenBlank() {
    var properties = StreamingProperties.builder().segmentBasePath("  ").build();

    assertThat(properties.segmentBasePath()).contains("streamarr-segments");
  }

  @Test
  @DisplayName("Should use provided segment base path when given")
  void shouldUseProvidedSegmentBasePathWhenGiven() {
    var properties = StreamingProperties.builder().segmentBasePath("/custom/segments").build();

    assertThat(properties.segmentBasePath()).isEqualTo("/custom/segments");
  }
}
