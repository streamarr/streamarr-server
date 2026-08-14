package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Image Properties Tests")
class ImagePropertiesTest {

  @Test
  @DisplayName("Should use provided storage path when given")
  void shouldUseProvidedStoragePathWhenGiven() {
    var properties = new ImageProperties("/custom/images");

    assertThat(properties.storagePath()).isEqualTo("/custom/images");
  }

  @Test
  @DisplayName("Should use default path when storage path is null")
  void shouldUseDefaultPathWhenStoragePathIsNull() {
    var properties = new ImageProperties(null);

    assertThat(properties.storagePath()).contains("streamarr-images");
  }

  @Test
  @DisplayName("Should use default path when storage path is blank")
  void shouldUseDefaultPathWhenStoragePathIsBlank() {
    var properties = new ImageProperties("  ");

    assertThat(properties.storagePath()).contains("streamarr-images");
  }

  @Test
  @DisplayName("Should use five-second replacement lock timeout when not provided")
  void shouldUseFiveSecondReplacementLockTimeoutWhenNotProvided() {
    var properties = new ImageProperties("/custom/images");

    assertThat(properties.replacementLockTimeout()).isEqualTo(Duration.ofSeconds(5));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 999_999})
  @DisplayName("Should reject replacement lock timeout when shorter than one millisecond")
  void shouldRejectReplacementLockTimeoutWhenShorterThanOneMillisecond(long timeoutNanos) {
    var timeout = Duration.ofNanos(timeoutNanos);

    assertThatThrownBy(() -> new ImageProperties("/custom/images", timeout))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Image replacement lock timeout must be at least 1ms");
  }
}
