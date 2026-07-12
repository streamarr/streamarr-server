package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Media Source Reference Tests")
class MediaSourceRefTest {

  @Test
  @DisplayName("Should preserve namespace and relative key when source reference is portable")
  void shouldPreserveNamespaceAndRelativeKeyWhenSourceReferenceIsPortable() {
    var namespaceId = UUID.randomUUID();

    var source = new MediaSourceRef(namespaceId, "Movies/Amélie.mkv");

    assertThat(source.namespaceId()).isEqualTo(namespaceId);
    assertThat(source.relativeKey()).isEqualTo("Movies/Amélie.mkv");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "/absolute.mkv",
        "C:/movie.mkv",
        "Movies\\movie.mkv",
        "Movies//movie.mkv",
        "Movies/movie.mkv/",
        ".",
        "..",
        "Movies/./movie.mkv",
        "Movies/../movie.mkv",
        "Movies/\0movie.mkv"
      })
  @DisplayName("Should reject source reference when relative key is not portable")
  void shouldRejectSourceReferenceWhenRelativeKeyIsNotPortable(String relativeKey) {
    var namespaceId = UUID.randomUUID();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new MediaSourceRef(namespaceId, relativeKey))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject source reference when namespace is absent")
  void shouldRejectSourceReferenceWhenNamespaceIsAbsent() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new MediaSourceRef(null, "Movies/movie.mkv"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
