package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.Image.IMAGE;

import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.jooq.generated.enums.ImageEntityType;
import com.streamarr.server.jooq.generated.enums.ImageSize;
import com.streamarr.server.jooq.generated.enums.ImageType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class ImageRepositoryCustomImpl implements ImageRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public Set<UUID> insertAllIfAbsent(List<Image> images) {
    if (images.isEmpty()) {
      return Set.of();
    }

    var auditUser = auditorAware.getCurrentAuditor().orElse(null);

    return images.stream()
        .filter(image -> insertIfAbsent(image, auditUser))
        .map(Image::getId)
        .collect(Collectors.toUnmodifiableSet());
  }

  private boolean insertIfAbsent(Image image, UUID auditUser) {
    var ambient = Optional.ofNullable(image.getAmbientColors());

    return dsl.insertInto(IMAGE)
            .set(IMAGE.ID, image.getId())
            .set(IMAGE.ENTITY_ID, image.getEntityId())
            .set(IMAGE.ENTITY_TYPE, ImageEntityType.lookupLiteral(image.getEntityType().name()))
            .set(IMAGE.IMAGE_TYPE, ImageType.lookupLiteral(image.getImageType().name()))
            .set(IMAGE.VARIANT, ImageSize.lookupLiteral(image.getVariant().name()))
            .set(IMAGE.WIDTH, image.getWidth())
            .set(IMAGE.HEIGHT, image.getHeight())
            .set(IMAGE.BLUR_HASH, image.getBlurHash())
            .set(IMAGE.AMBIENT_TOP_LEFT, ambient.map(AmbientColors::topLeft).orElse(null))
            .set(IMAGE.AMBIENT_TOP_RIGHT, ambient.map(AmbientColors::topRight).orElse(null))
            .set(IMAGE.AMBIENT_BOTTOM_RIGHT, ambient.map(AmbientColors::bottomRight).orElse(null))
            .set(IMAGE.AMBIENT_BOTTOM_LEFT, ambient.map(AmbientColors::bottomLeft).orElse(null))
            .set(IMAGE.AMBIENT_PRIMARY, ambient.map(AmbientColors::primary).orElse(null))
            .set(IMAGE.PATH, image.getPath())
            .set(IMAGE.CREATED_BY, auditUser)
            .set(IMAGE.LAST_MODIFIED_BY, auditUser)
            .onConflict(IMAGE.ENTITY_ID, IMAGE.IMAGE_TYPE, IMAGE.VARIANT)
            .doNothing()
            .execute()
        > 0;
  }
}
