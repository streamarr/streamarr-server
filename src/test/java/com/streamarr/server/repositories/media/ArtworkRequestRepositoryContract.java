package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.ArtworkState;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.repositories.media.ArtworkRequestRepository.SavedArtworkRequest.NewGeneration;
import com.streamarr.server.repositories.media.ArtworkRequestRepository.SavedArtworkRequest.Unchanged;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.PendingArtworkRequest;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The artwork request rules that every {@link ArtworkRequestRepository} keeps, so the fake that
 * unit tests use behaves as the PostgreSQL repository does.
 */
public interface ArtworkRequestRepositoryContract {

  Instant REQUESTED_AT = Instant.parse("2026-10-14T10:00:00Z");
  ImageSource POSTER = new TmdbImageSource(ImageType.POSTER, "/poster.jpg");
  ImageSource BACKDROP = new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg");
  ImageSource OTHER_POSTER = new TmdbImageSource(ImageType.POSTER, "/other-poster.jpg");

  ArtworkRequestRepository repository();

  /** Creates a movie that artwork can be requested for. */
  ArtworkItem createMovie();

  /** Creates a person that artwork can be requested for. */
  ArtworkItem createPerson();

  void deleteMovie(ArtworkItem movie);

  /** Stores an artwork result as the step that records it would. */
  void saveResult(ItemResult result);

  /** Runs work that requires the caller's transaction. */
  <T> T inTransaction(Supplier<T> work);

  @Test
  @DisplayName("Should start a generation when an item requests artwork for the first time")
  default void shouldStartAGenerationWhenAnItemRequestsArtworkForTheFirstTime() {
    var movie = createMovie();

    var saved = save(movie, ImageRefreshMode.PRESERVE, POSTER, BACKDROP);

    assertThat(saved).isInstanceOf(NewGeneration.class);
    assertThat(repository().findRequest(movie))
        .contains(
            PendingArtworkRequest.builder()
                .artwork(artwork(movie, POSTER, BACKDROP))
                .refreshMode(ImageRefreshMode.PRESERVE)
                .generation(saved.generation())
                .build());
  }

  @Test
  @DisplayName("Should keep the generation when an identical request arrives")
  default void shouldKeepTheGenerationWhenAnIdenticalRequestArrives() {
    var movie = createMovie();
    var first = save(movie, ImageRefreshMode.PRESERVE, POSTER, BACKDROP);

    var second = save(movie, ImageRefreshMode.PRESERVE, BACKDROP, POSTER);

    assertThat(second).isEqualTo(new Unchanged(first.generation()));
  }

  @Test
  @DisplayName("Should keep the generation when a weaker request keeps the sources")
  default void shouldKeepTheGenerationWhenAWeakerRequestKeepsTheSources() {
    var movie = createMovie();
    var forced = save(movie, ImageRefreshMode.FORCE_REFRESH, POSTER);

    var preserve = save(movie, ImageRefreshMode.PRESERVE, POSTER);

    assertThat(preserve).isEqualTo(new Unchanged(forced.generation()));
    assertThat(repository().findRequest(movie))
        .map(PendingArtworkRequest::refreshMode)
        .contains(ImageRefreshMode.FORCE_REFRESH);
  }

  @Test
  @DisplayName("Should start a new generation when the sources change")
  default void shouldStartANewGenerationWhenTheSourcesChange() {
    var movie = createMovie();
    var first = save(movie, ImageRefreshMode.PRESERVE, POSTER);

    var second = save(movie, ImageRefreshMode.PRESERVE, OTHER_POSTER);

    assertThat(second).isInstanceOf(NewGeneration.class);
    assertThat(second.generation()).isGreaterThan(first.generation());
    assertThat(repository().findRequest(movie))
        .map(PendingArtworkRequest::artwork)
        .contains(artwork(movie, OTHER_POSTER));
  }

  @Test
  @DisplayName("Should start a new generation when a stronger refresh mode arrives")
  default void shouldStartANewGenerationWhenAStrongerRefreshModeArrives() {
    var movie = createMovie();
    var first = save(movie, ImageRefreshMode.PRESERVE, POSTER);

    var second = save(movie, ImageRefreshMode.REFRESH_IF_CHANGED, POSTER);

    assertThat(second).isInstanceOf(NewGeneration.class);
    assertThat(second.generation()).isGreaterThan(first.generation());
  }

  @Test
  @DisplayName("Should keep the stronger refresh mode when a weaker request changes the sources")
  default void shouldKeepTheStrongerRefreshModeWhenAWeakerRequestChangesTheSources() {
    var movie = createMovie();
    save(movie, ImageRefreshMode.FORCE_REFRESH, POSTER);

    var changed = save(movie, ImageRefreshMode.PRESERVE, OTHER_POSTER);

    assertThat(repository().findRequest(movie))
        .contains(
            PendingArtworkRequest.builder()
                .artwork(artwork(movie, OTHER_POSTER))
                .refreshMode(ImageRefreshMode.FORCE_REFRESH)
                .generation(changed.generation())
                .build());
  }

  @Test
  @DisplayName("Should delete the request when its pending generation completes")
  default void shouldDeleteTheRequestWhenItsPendingGenerationCompletes() {
    var movie = createMovie();
    var saved = save(movie, ImageRefreshMode.PRESERVE, POSTER);

    var completed =
        inTransaction(() -> repository().tryCompleteGeneration(movie, saved.generation()));

    assertThat(completed).isTrue();
    assertThat(repository().findRequest(movie)).isEmpty();
  }

  @Test
  @DisplayName("Should keep the request when a newer generation replaced the completed one")
  default void shouldKeepTheRequestWhenANewerGenerationReplacedTheCompletedOne() {
    var movie = createMovie();
    var first = save(movie, ImageRefreshMode.PRESERVE, POSTER);
    var second = save(movie, ImageRefreshMode.PRESERVE, OTHER_POSTER);

    var completed =
        inTransaction(() -> repository().tryCompleteGeneration(movie, first.generation()));

    assertThat(completed).isFalse();
    assertThat(repository().findRequest(movie))
        .map(PendingArtworkRequest::generation)
        .contains(second.generation());
  }

  @Test
  @DisplayName("Should report no request left when a completed generation was already deleted")
  default void shouldReportNoRequestLeftWhenACompletedGenerationWasAlreadyDeleted() {
    var movie = createMovie();

    assertThat(inTransaction(() -> repository().tryCompleteGeneration(movie, 1))).isTrue();
  }

  @Test
  @DisplayName("Should lock and return the pending generation when a request is pending")
  default void shouldLockAndReturnThePendingGenerationWhenARequestIsPending() {
    var movie = createMovie();
    var saved = save(movie, ImageRefreshMode.PRESERVE, POSTER);
    var other = createMovie();

    assertThat(inTransaction(() -> repository().lockPendingGeneration(movie)))
        .contains(saved.generation());
    assertThat(inTransaction(() -> repository().lockPendingGeneration(other))).isEmpty();
  }

  @Test
  @DisplayName("Should delete the request when its movie is deleted")
  default void shouldDeleteTheRequestWhenItsMovieIsDeleted() {
    var movie = createMovie();
    save(movie, ImageRefreshMode.PRESERVE, POSTER);

    deleteMovie(movie);

    assertThat(repository().findRequest(movie)).isEmpty();
  }

  @Test
  @DisplayName("Should read the pending generation and artwork results when items are checked")
  default void shouldReadThePendingGenerationAndArtworkResultsWhenItemsAreChecked() {
    var pending = createMovie();
    var saved = save(pending, ImageRefreshMode.PRESERVE, POSTER);
    var finished = createMovie();
    var result = savedPoster(finished, 7);
    saveResult(result);

    var states = repository().findArtworkStates(List.of(pending, finished));

    assertThat(states)
        .containsExactlyInAnyOrder(
            new ArtworkState(pending, Optional.of(saved.generation()), List.of()),
            new ArtworkState(finished, Optional.empty(), List.of(result)));
  }

  @Test
  @DisplayName("Should count the images that pending requests ask for by item type")
  default void shouldCountTheImagesThatPendingRequestsAskForByItemType() {
    save(createMovie(), ImageRefreshMode.PRESERVE, POSTER);
    save(createMovie(), ImageRefreshMode.PRESERVE, POSTER, BACKDROP);
    var person = createPerson();
    inTransaction(
        () ->
            repository()
                .saveRequest(
                    ArtworkSources.builder()
                        .entityId(person.itemId())
                        .entityType(ImageEntityType.PERSON)
                        .sources(List.of(new TmdbImageSource(ImageType.PROFILE, "/face.jpg")))
                        .build(),
                    ImageRefreshMode.PRESERVE,
                    REQUESTED_AT));

    assertThat(repository().countPendingImagesByItemType())
        .containsAllEntriesOf(Map.of(ImageEntityType.MOVIE, 4L, ImageEntityType.PERSON, 1L));
  }

  private ArtworkRequestRepository.SavedArtworkRequest save(
      ArtworkItem item, ImageRefreshMode refreshMode, ImageSource... sources) {
    return inTransaction(
        () -> repository().saveRequest(artwork(item, sources), refreshMode, REQUESTED_AT));
  }

  private static ArtworkSources artwork(ArtworkItem item, ImageSource... sources) {
    return ArtworkSources.builder()
        .entityId(item.itemId())
        .entityType(item.itemType())
        .sources(List.of(sources))
        .build();
  }

  private static ItemResult savedPoster(ArtworkItem item, long generation) {
    return ItemResult.builder()
        .itemId(item.itemId())
        .itemType(item.itemType())
        .step(ItemStep.ARTWORK)
        .imageType(ImageType.POSTER)
        .outcome(new ItemOutcome.Succeeded())
        .sourceKey("/poster.jpg")
        .attemptedAt(REQUESTED_AT)
        .artworkGeneration(generation)
        .build();
  }
}
