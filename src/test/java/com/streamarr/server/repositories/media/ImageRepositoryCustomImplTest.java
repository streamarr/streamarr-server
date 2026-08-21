package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.ImageFixture;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
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

  @Test
  @DisplayName("Should insert image when ambient colors are absent")
  void shouldInsertImageWhenAmbientColorsAreAbsent() throws Exception {
    var imageId = UUID.randomUUID();
    var image =
        ImageFixture.imageBuilder(UUID.randomUUID())
            .id(imageId)
            .key("poster")
            .path("poster-small.jpg")
            .build();

    try (var connection = new MockConnection(_ -> new MockResult[] {new MockResult(1)})) {
      var repository =
          new ImageRepositoryCustomImpl(
              DSL.using(connection, SQLDialect.POSTGRES), () -> Optional.empty(), null);

      assertThat(repository.insertAllIfAbsent(List.of(image))).containsExactly(imageId);
    }
  }
}
