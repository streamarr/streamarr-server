package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.Episode.EPISODE;
import static com.streamarr.server.jooq.generated.tables.Image.IMAGE;
import static com.streamarr.server.jooq.generated.tables.Movie.MOVIE;
import static com.streamarr.server.jooq.generated.tables.Season.SEASON;
import static com.streamarr.server.jooq.generated.tables.Series.SERIES;

import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.ArtworkAttempt;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.exceptions.ObsoleteArtworkAttemptException;
import com.streamarr.server.jooq.generated.enums.ImageEntityType;
import com.streamarr.server.jooq.generated.enums.ImageSize;
import com.streamarr.server.jooq.generated.enums.ImageType;
import com.streamarr.server.repositories.PostgresTransactionLocks;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;

@RequiredArgsConstructor
public class ImageRepositoryCustomImpl implements ImageRepositoryCustom {

  private final DSLContext dsl;
  private final ImageProperties imageProperties;
  private final PostgresTransactionLocks transactionLocks;
  private final ItemLocks itemLocks;

  @Override
  public Set<UUID> insertAllIfAbsent(List<Image> images, ArtworkAttempt attempt) {
    if (images.isEmpty()) {
      return Set.of();
    }

    transactionLocks.limitLockWait(imageProperties.replacementLockTimeout());
    lockArtworkGeneration(attempt);

    return images.stream()
        .filter(this::insertIfAbsent)
        .map(Image::getId)
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public List<String> replaceLogicalArtwork(List<Image> images, ArtworkAttempt attempt) {
    if (images.isEmpty()) {
      return List.of();
    }

    var first = images.getFirst();
    var entityType = ImageEntityType.lookupLiteral(first.getEntityType().name());
    var imageType = ImageType.lookupLiteral(first.getImageType().name());
    var artworkIdentity = first.getEntityId() + "|" + entityType + "|" + imageType;
    var lockKey =
        UUID.nameUUIDFromBytes(artworkIdentity.getBytes(StandardCharsets.UTF_8))
            .getMostSignificantBits();
    transactionLocks.limitLockWait(imageProperties.replacementLockTimeout());
    transactionLocks.lock(lockKey);
    lockArtworkGeneration(attempt);

    var condition =
        IMAGE
            .ENTITY_ID
            .eq(first.getEntityId())
            .and(IMAGE.ENTITY_TYPE.eq(entityType))
            .and(IMAGE.IMAGE_TYPE.eq(imageType));
    var replacedPaths =
        dsl.select(IMAGE.PATH).from(IMAGE).where(condition).forUpdate().fetch(IMAGE.PATH);

    dsl.deleteFrom(IMAGE).where(condition).execute();
    images.forEach(image -> dsl.insertInto(IMAGE).set(imageValues(image)).execute());

    return replacedPaths;
  }

  @Override
  public List<String> lockMoviesAndFindArtworkPaths(Collection<UUID> movieIds) {
    var movies = itemLocks.lockForDeletion(MOVIE.ID, MOVIE.ID.in(movieIds));
    return artworkPaths(IMAGE.MOVIE_ID.in(movies));
  }

  @Override
  public List<String> lockSeriesAndFindArtworkPaths(Collection<UUID> seriesIds) {
    var series = itemLocks.lockForDeletion(SERIES.ID, SERIES.ID.in(seriesIds));
    var seasons = itemLocks.lockForDeletion(SEASON.ID, SEASON.SERIES_ID.in(series));
    var episodes = itemLocks.lockForDeletion(EPISODE.ID, EPISODE.SEASON_ID.in(seasons));
    return artworkPaths(
        IMAGE
            .SERIES_ID
            .in(series)
            .or(IMAGE.SEASON_ID.in(seasons))
            .or(IMAGE.EPISODE_ID.in(episodes)));
  }

  private void lockArtworkGeneration(ArtworkAttempt attempt) {
    if (!itemLocks.tryLockArtworkGeneration(attempt.target(), attempt.generation())) {
      throw new ObsoleteArtworkAttemptException(attempt);
    }
  }

  private List<String> artworkPaths(Condition owners) {
    return dsl.select(IMAGE.PATH).from(IMAGE).where(owners).fetch(IMAGE.PATH);
  }

  private boolean insertIfAbsent(Image image) {
    return dsl.insertInto(IMAGE)
            .set(imageValues(image))
            .onConflict(IMAGE.ENTITY_ID, IMAGE.IMAGE_TYPE, IMAGE.VARIANT)
            .doNothing()
            .execute()
        > 0;
  }

  private Map<Field<?>, Object> imageValues(Image image) {
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
    values.put(IMAGE.AMBIENT_DARK_VIBRANT, ambient.map(AmbientColors::darkVibrant).orElse(null));
    values.put(IMAGE.AMBIENT_DARK_MUTED, ambient.map(AmbientColors::darkMuted).orElse(null));
    values.put(IMAGE.AMBIENT_LIGHT_VIBRANT, ambient.map(AmbientColors::lightVibrant).orElse(null));
    values.put(IMAGE.AMBIENT_LIGHT_MUTED, ambient.map(AmbientColors::lightMuted).orElse(null));
    values.put(IMAGE.PATH, image.getPath());
    return values;
  }
}
