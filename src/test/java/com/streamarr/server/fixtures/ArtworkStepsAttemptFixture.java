package com.streamarr.server.fixtures;

import com.streamarr.server.domain.media.ArtworkStepsAttempt;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.NewArtworkRequest;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import org.springframework.transaction.support.TransactionTemplate;

/** Artwork steps attempts for tests that save processed images directly. */
public final class ArtworkStepsAttemptFixture {

  public static final Instant ATTEMPTED_AT = Instant.parse("2026-09-23T10:00:00Z");

  private static final long FAKE_GENERATION = 1;

  private ArtworkStepsAttemptFixture() {}

  /**
   * An attempt for the target of the first image, for fakes that accept every generation. Without
   * images it names a movie that no image belongs to.
   */
  public static ArtworkStepsAttempt attemptFor(@NonNull List<Image> images) {
    return ArtworkStepsAttempt.builder()
        .target(targetOf(images))
        .generation(FAKE_GENERATION)
        .attemptedAt(ATTEMPTED_AT)
        .build();
  }

  /**
   * Stores an artwork request for the target of the first image and returns an attempt of its
   * generation, so the database accepts the attempt's images.
   */
  public static ArtworkStepsAttempt requestedAttemptFor(
      @NonNull TransactionTemplate transactionTemplate,
      @NonNull ArtworkRequestRepository requests,
      @NonNull List<Image> images) {
    return requestedAttemptFor(transactionTemplate, requests, targetOf(images));
  }

  /** Stores an artwork request for the target and returns an attempt of its generation. */
  public static ArtworkStepsAttempt requestedAttemptFor(
      @NonNull TransactionTemplate transactionTemplate,
      @NonNull ArtworkRequestRepository requests,
      @NonNull ArtworkTarget target) {
    var artwork =
        ArtworkSources.builder()
            .targetId(target.targetId())
            .targetType(target.targetType())
            .sources(List.of())
            .build();
    var generation =
        transactionTemplate.execute(
            _ ->
                requests
                    .saveRequest(
                        NewArtworkRequest.builder()
                            .artwork(artwork)
                            .refreshMode(ImageRefreshMode.FORCE_REFRESH)
                            .requestedAt(ATTEMPTED_AT)
                            .build())
                    .generation());
    return ArtworkStepsAttempt.builder()
        .target(target)
        .generation(generation)
        .attemptedAt(ATTEMPTED_AT)
        .build();
  }

  private static ArtworkTarget targetOf(List<Image> images) {
    if (images.isEmpty()) {
      return new ArtworkTarget(UUID.randomUUID(), ImageEntityType.MOVIE);
    }

    var first = images.getFirst();
    return new ArtworkTarget(first.getEntityId(), first.getEntityType());
  }
}
