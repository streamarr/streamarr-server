package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

@Tag("UnitTest")
@DisplayName("Streaming Properties Tests")
class StreamingPropertiesTest {

  private static final ApplicationContextRunner CONTEXT_RUNNER =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(StreamingPropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(StreamingProperties.class)
  static class StreamingPropertiesConfiguration {}

  @Test
  @DisplayName("Should default max concurrent transcodes to 8 when unset")
  void shouldDefaultMaxConcurrentTranscodesToEightWhenUnset() {
    var properties = StreamingProperties.builder().build();

    assertThat(properties.maxConcurrentTranscodes()).isEqualTo(8);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("Should reject max concurrent transcodes when not positive")
  void shouldRejectMaxConcurrentTranscodesWhenNotPositive(int configured) {
    var builder = StreamingProperties.builder().maxConcurrentTranscodes(configured);

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max-concurrent-transcodes");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("Should fail startup when configured max concurrent transcodes is not positive")
  void shouldFailStartupWhenConfiguredMaxConcurrentTranscodesIsNotPositive(int configured) {
    CONTEXT_RUNNER
        .withPropertyValues("streaming.max-concurrent-transcodes=" + configured)
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("Should default segment duration to 6 seconds when null")
  void shouldDefaultSegmentDurationToSixSecondsWhenNull() {
    var properties = StreamingProperties.builder().build();

    assertThat(properties.targetSegmentDuration()).isEqualTo(Duration.ofSeconds(6));
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

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject session retention when not positive")
  void shouldRejectSessionRetentionWhenNotPositive(long retentionSeconds) {
    CONTEXT_RUNNER
        .withPropertyValues("streaming.session-retention=" + Duration.ofSeconds(retentionSeconds))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("Should accept positive session retention")
  void shouldAcceptPositiveSessionRetention() {
    CONTEXT_RUNNER
        .withPropertyValues("streaming.session-retention=PT1H")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(StreamingProperties.class).sessionRetention())
                  .isEqualTo(Duration.ofHours(1));
            });
  }
}
