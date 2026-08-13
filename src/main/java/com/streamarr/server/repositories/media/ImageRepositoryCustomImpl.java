package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.Image.IMAGE;

import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.jooq.generated.enums.ImageEntityType;
import com.streamarr.server.jooq.generated.enums.ImageSize;
import com.streamarr.server.jooq.generated.enums.ImageType;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
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

  @Override
  public List<String> replaceLogicalArtwork(List<Image> images) {
    var first = images.getFirst();
    var entityType = ImageEntityType.lookupLiteral(first.getEntityType().name());
    var imageType = ImageType.lookupLiteral(first.getImageType().name());
    var artworkIdentity = first.getEntityId() + "|" + entityType + "|" + imageType;
    var lockKey =
        UUID.nameUUIDFromBytes(artworkIdentity.getBytes(StandardCharsets.UTF_8))
            .getMostSignificantBits();
    dsl.select(DSL.function(DSL.name("pg_advisory_xact_lock"), Object.class, DSL.val(lockKey)))
        .execute();
    var condition =
        IMAGE
            .ENTITY_ID
            .eq(first.getEntityId())
            .and(IMAGE.ENTITY_TYPE.eq(entityType))
            .and(IMAGE.IMAGE_TYPE.eq(imageType));
    var replacedPaths =
        dsl.select(IMAGE.PATH).from(IMAGE).where(condition).forUpdate().fetch(IMAGE.PATH);

    dsl.deleteFrom(IMAGE).where(condition).execute();

    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    images.forEach(image -> dsl.insertInto(IMAGE).set(imageValues(image, auditUser)).execute());

    return replacedPaths;
  }

  private boolean insertIfAbsent(Image image, UUID auditUser) {
    return dsl.insertInto(IMAGE)
            .set(imageValues(image, auditUser))
            .onConflict(IMAGE.ENTITY_ID, IMAGE.IMAGE_TYPE, IMAGE.VARIANT)
            .doNothing()
            .execute()
        > 0;
  }

  private Map<Field<?>, Object> imageValues(Image image, UUID auditUser) {
    var ambient = image.getAmbientColors();

    var values = new LinkedHashMap<Field<?>, Object>();
    values.put(IMAGE.ID, image.getId());
    values.put(IMAGE.ENTITY_ID, image.getEntityId());
    values.put(IMAGE.ENTITY_TYPE, ImageEntityType.lookupLiteral(image.getEntityType().name()));
    values.put(IMAGE.IMAGE_TYPE, ImageType.lookupLiteral(image.getImageType().name()));
    values.put(IMAGE.VARIANT, ImageSize.lookupLiteral(image.getVariant().name()));
    values.put(IMAGE.WIDTH, image.getWidth());
    values.put(IMAGE.HEIGHT, image.getHeight());
    values.put(IMAGE.BLUR_HASH, image.getBlurHash());
    values.put(IMAGE.KEY, image.getKey());
    values.put(IMAGE.CONTENT_SHA256, image.getContentSha256());
    values.put(IMAGE.AMBIENT_TOP_LEFT, ambient.map(AmbientColors::topLeft).orElse(null));
    values.put(IMAGE.AMBIENT_TOP_RIGHT, ambient.map(AmbientColors::topRight).orElse(null));
    values.put(IMAGE.AMBIENT_BOTTOM_RIGHT, ambient.map(AmbientColors::bottomRight).orElse(null));
    values.put(IMAGE.AMBIENT_BOTTOM_LEFT, ambient.map(AmbientColors::bottomLeft).orElse(null));
    values.put(IMAGE.AMBIENT_PRIMARY, ambient.map(AmbientColors::primary).orElse(null));
    values.put(IMAGE.PATH, image.getPath());
    values.put(IMAGE.CREATED_BY, auditUser);
    values.put(IMAGE.LAST_MODIFIED_BY, auditUser);
    return values;
  }
}
