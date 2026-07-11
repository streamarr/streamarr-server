package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
}
