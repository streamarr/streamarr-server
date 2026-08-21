package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.Image.IMAGE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.jooq.generated.enums.ImageEntityType;
import com.streamarr.server.jooq.generated.enums.ImageSize;
import com.streamarr.server.jooq.generated.enums.ImageType;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("IntegrationTest")
@DisplayName("Image Ambient Colors Integration Tests")
class ImageAmbientColorsIT extends AbstractIntegrationTest {

  private final UUID imageId = UUID.randomUUID();

  @Autowired private DSLContext dsl;

  @AfterEach
  void cleanUp() {
    dsl.deleteFrom(IMAGE).where(IMAGE.ID.eq(imageId)).execute();
  }

  @Test
  @DisplayName("Should reject partial ambient colors when image inserted")
  void shouldRejectPartialAmbientColorsWhenImageInserted() {
    assertThatThrownBy(this::insertImageWithOnlyPrimaryAmbientColor)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_image_ambient_colors_complete");
  }

  private void insertImageWithOnlyPrimaryAmbientColor() {
    dsl.insertInto(IMAGE)
        .set(IMAGE.ID, imageId)
        .set(IMAGE.ENTITY_ID, UUID.randomUUID())
        .set(IMAGE.ENTITY_TYPE, ImageEntityType.MOVIE)
        .set(IMAGE.IMAGE_TYPE, ImageType.POSTER)
        .set(IMAGE.VARIANT, ImageSize.SMALL)
        .set(IMAGE.WIDTH, 185)
        .set(IMAGE.HEIGHT, 278)
        .set(IMAGE.AMBIENT_PRIMARY, "#00a0a0")
        .set(IMAGE.PATH, "movie/poster-small.jpg")
        .execute();
  }
}
