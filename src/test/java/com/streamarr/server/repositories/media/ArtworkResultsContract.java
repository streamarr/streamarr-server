package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.media.ArtworkAttempt;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules for storing an attempt's artwork results that every {@link ItemResultRepository} keeps,
 * so the fake that unit tests use behaves as the PostgreSQL repository does.
 */
public interface ArtworkResultsContract {

  Instant EARLIER = Instant.parse("2026-09-23T10:00:00Z");
  Instant LATER = EARLIER.plusSeconds(60);

  ItemResultRepository itemResults();

  /** Creates a movie that artwork can be requested for. */
  ArtworkTarget createMovie();

  /** Stores an artwork request for the movie and returns its generation. */
  long requestArtwork(ArtworkTarget movie);

  @Test
  @DisplayName(
      "Should keep a later attempt's saved image result when an earlier attempt of the same"
          + " generation stores its failure afterwards")
  default void shouldKeepALaterAttemptsSavedImageResultWhenAnEarlierAttemptStoresItsFailure() {
    var movie = createMovie();
    var generation = requestArtwork(movie);
    itemResults()
        .overwrite(
            poster(movie, generation)
                .outcome(new ItemOutcome.Succeeded())
                .attemptedAt(LATER)
                .build());

    itemResults()
        .trySaveArtworkResults(
            attempt(movie, generation, EARLIER),
            List.of(
                poster(movie, generation)
                    .outcome(new ItemOutcome.Failed(ItemFailureReason.DOWNLOAD_FAILED, "404"))
                    .build(),
                poster(movie, generation)
                    .imageType(ImageType.BACKDROP)
                    .outcome(new ItemOutcome.Unavailable())
                    .build()));

    assertThat(itemResults().findByItem(movie.targetId(), movie.targetType()))
        .extracting(ItemResult::imageType, ItemResult::outcome)
        .containsExactlyInAnyOrder(
            tuple(ImageType.POSTER, new ItemOutcome.Succeeded()),
            tuple(ImageType.BACKDROP, new ItemOutcome.Unavailable()));
  }

  @Test
  @DisplayName(
      "Should keep a saved image result when a later attempt of the same generation stores a"
          + " failure")
  default void shouldKeepASavedImageResultWhenALaterAttemptOfTheSameGenerationStoresAFailure() {
    var movie = createMovie();
    var generation = requestArtwork(movie);
    var saved = poster(movie, generation).outcome(new ItemOutcome.Succeeded()).build();
    itemResults().overwrite(saved);

    itemResults()
        .trySaveArtworkResults(
            attempt(movie, generation, LATER),
            List.of(
                poster(movie, generation)
                    .outcome(new ItemOutcome.Failed(ItemFailureReason.DOWNLOAD_FAILED, "404"))
                    .attemptedAt(LATER)
                    .build()));

    assertThat(itemResults().findByItem(movie.targetId(), movie.targetType()))
        .containsExactly(saved);
  }

  @Test
  @DisplayName(
      "Should store a later attempt's kept image result when a saved one of the same generation"
          + " exists")
  default void shouldStoreALaterAttemptsKeptImageResultWhenASavedOneOfTheSameGenerationExists() {
    var movie = createMovie();
    var generation = requestArtwork(movie);
    itemResults().overwrite(poster(movie, generation).outcome(new ItemOutcome.Succeeded()).build());
    var kept =
        poster(movie, generation)
            .outcome(new ItemOutcome.Succeeded())
            .sourceKey("/poster.jpg")
            .attemptedAt(LATER)
            .build();

    itemResults().trySaveArtworkResults(attempt(movie, generation, LATER), List.of(kept));

    assertThat(itemResults().findByItem(movie.targetId(), movie.targetType()))
        .containsExactly(kept);
  }

  @Test
  @DisplayName(
      "Should replace a result when it belongs to an older generation whatever its attempt time")
  default void shouldReplaceAResultWhenItBelongsToAnOlderGenerationWhateverItsAttemptTime() {
    var movie = createMovie();
    itemResults()
        .overwrite(
            poster(movie, 0L).outcome(new ItemOutcome.Succeeded()).attemptedAt(LATER).build());
    var generation = requestArtwork(movie);

    itemResults()
        .trySaveArtworkResults(
            attempt(movie, generation, EARLIER),
            List.of(poster(movie, generation).outcome(new ItemOutcome.Unavailable()).build()));

    assertThat(itemResults().findByItem(movie.targetId(), movie.targetType()))
        .extracting(ItemResult::outcome, ItemResult::artworkGeneration)
        .containsExactly(tuple(new ItemOutcome.Unavailable(), generation));
  }

  private static ArtworkAttempt attempt(ArtworkTarget movie, long generation, Instant attemptedAt) {
    return ArtworkAttempt.builder()
        .target(movie)
        .generation(generation)
        .attemptedAt(attemptedAt)
        .build();
  }

  private static ItemResult.ItemResultBuilder poster(ArtworkTarget movie, long generation) {
    return ItemResult.builder()
        .itemId(movie.targetId())
        .itemType(ImageEntityType.MOVIE)
        .step(ItemStep.ARTWORK)
        .imageType(ImageType.POSTER)
        .attemptedAt(EARLIER)
        .artworkGeneration(generation);
  }
}
