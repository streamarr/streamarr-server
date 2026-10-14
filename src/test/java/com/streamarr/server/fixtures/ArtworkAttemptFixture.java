package com.streamarr.server.fixtures;

import com.streamarr.server.domain.media.ArtworkAttempt;
import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import org.springframework.transaction.support.TransactionTemplate;

/** Artwork attempts for tests that save processed images directly. */
public final class ArtworkAttemptFixture {

  public static final Instant ATTEMPTED_AT = Instant.parse("2026-09-23T10:00:00Z");

  private static final long FAKE_GENERATION = 1;

  private ArtworkAttemptFixture() {}

  /**
   * An attempt for the item of the first image, for fakes that accept every generation. Without
   * images it names a movie that no image belongs to.
   */
  public static ArtworkAttempt attemptFor(@NonNull List<Image> images) {
    return ArtworkAttempt.builder()
        .item(itemOf(images))
        .generation(FAKE_GENERATION)
        .attemptedAt(ATTEMPTED_AT)
        .build();
  }

  /**
   * Stores an artwork request for the item of the first image and returns an attempt of its
   * generation, so the database accepts the attempt's images.
   */
  public static ArtworkAttempt requestedAttemptFor(
      @NonNull TransactionTemplate transactionTemplate,
      @NonNull ArtworkRequestRepository requests,
      @NonNull List<Image> images) {
    return requestedAttemptFor(transactionTemplate, requests, itemOf(images));
  }

  /** Stores an artwork request for the item and returns an attempt of its generation. */
  public static ArtworkAttempt requestedAttemptFor(
      @NonNull TransactionTemplate transactionTemplate,
      @NonNull ArtworkRequestRepository requests,
      @NonNull ArtworkItem item) {
    var artwork =
        ArtworkSources.builder()
            .entityId(item.itemId())
            .entityType(item.itemType())
            .sources(List.of())
            .build();
    var generation =
        transactionTemplate.execute(
            _ ->
                requests
                    .saveRequest(artwork, ImageRefreshMode.FORCE_REFRESH, ATTEMPTED_AT)
                    .generation());
    return ArtworkAttempt.builder()
        .item(item)
        .generation(generation)
        .attemptedAt(ATTEMPTED_AT)
        .build();
  }

  private static ArtworkItem itemOf(List<Image> images) {
    if (images.isEmpty()) {
      return new ArtworkItem(UUID.randomUUID(), ImageEntityType.MOVIE);
    }

    var first = images.getFirst();
    return new ArtworkItem(first.getEntityId(), first.getEntityType());
  }
}
