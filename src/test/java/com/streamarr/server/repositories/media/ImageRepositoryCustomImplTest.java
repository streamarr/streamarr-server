package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Image Repository Custom Implementation Tests")
class ImageRepositoryCustomImplTest {

  @Test
  @DisplayName("Should return no replaced paths when replacement is empty")
  void shouldReturnNoReplacedPathsWhenReplacementIsEmpty() {
    var repository = new ImageRepositoryCustomImpl(null, null, null);

    assertThat(repository.replaceLogicalArtwork(List.of())).isEmpty();
  }
}
