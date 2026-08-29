package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.fixtures.ImageFixture;
import java.util.ArrayList;
import java.util.Arrays;
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
              DSL.using(connection, SQLDialect.POSTGRES), Optional::empty, null);

      assertThat(repository.insertAllIfAbsent(List.of(image))).containsExactly(imageId);
    }
  }

  @Test
  @DisplayName("Should bind target swatches when ambient colors carry them")
  void shouldBindTargetSwatchesWhenAmbientColorsCarryThem() throws Exception {
    var image =
        ImageFixture.imageBuilder(UUID.randomUUID())
            .id(UUID.randomUUID())
            .key("poster")
            .path("poster-small.jpg")
            .ambientColors(
                Optional.of(
                    AmbientColors.builder()
                        .topLeft("#010101")
                        .topRight("#020202")
                        .bottomRight("#030303")
                        .bottomLeft("#040404")
                        .primary("#00a0a0")
                        .darkVibrant("#103070")
                        .darkMuted("#283830")
                        .lightVibrant("#68f8f8")
                        .lightMuted("#c8d0c8")
                        .build()))
            .build();
    var bindings = new ArrayList<>();

    try (var connection =
        new MockConnection(
            ctx -> {
              bindings.addAll(Arrays.asList(ctx.bindings()));
              return new MockResult[] {new MockResult(1)};
            })) {
      var repository =
          new ImageRepositoryCustomImpl(
              DSL.using(connection, SQLDialect.POSTGRES), Optional::empty, null);

      repository.insertAllIfAbsent(List.of(image));
    }

    assertThat(bindings).contains("#103070", "#283830", "#68f8f8", "#c8d0c8");
  }
}
