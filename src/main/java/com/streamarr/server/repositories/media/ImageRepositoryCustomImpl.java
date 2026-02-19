package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.Image.IMAGE;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.jooq.generated.enums.ImageEntityType;
import com.streamarr.server.jooq.generated.enums.ImageSize;
import com.streamarr.server.jooq.generated.enums.ImageType;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class ImageRepositoryCustomImpl implements ImageRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public void insertAllIfAbsent(List<Image> images) {
    if (images.isEmpty()) {
      return;
    }

    var auditUser = auditorAware.getCurrentAuditor().orElse(null);

    var queries =
        images.stream()
            .map(
                image ->
                    dsl.insertInto(IMAGE)
                        .set(IMAGE.ENTITY_ID, image.getEntityId())
                        .set(
                            IMAGE.ENTITY_TYPE,
                            ImageEntityType.lookupLiteral(image.getEntityType().name()))
                        .set(IMAGE.IMAGE_TYPE, ImageType.lookupLiteral(image.getImageType().name()))
                        .set(IMAGE.VARIANT, ImageSize.lookupLiteral(image.getVariant().name()))
                        .set(IMAGE.WIDTH, image.getWidth())
                        .set(IMAGE.HEIGHT, image.getHeight())
                        .set(IMAGE.BLUR_HASH, image.getBlurHash())
                        .set(IMAGE.PATH, image.getPath())
                        .set(IMAGE.CREATED_BY, auditUser)
                        .set(IMAGE.LAST_MODIFIED_BY, auditUser)
                        .onConflict(IMAGE.ENTITY_ID, IMAGE.IMAGE_TYPE, IMAGE.VARIANT)
                        .doNothing())
            .toArray(Query[]::new);

    dsl.batch(queries).execute();
  }
}
