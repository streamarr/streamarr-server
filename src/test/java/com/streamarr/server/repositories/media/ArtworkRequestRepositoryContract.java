package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.media.ArtworkBacklog;
import com.streamarr.server.domain.media.ArtworkCompletionEvidence;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.repositories.media.ArtworkRequestRepository.SavedArtworkRequest.NewGeneration;
import com.streamarr.server.repositories.media.ArtworkRequestRepository.SavedArtworkRequest.TakenOver;
import com.streamarr.server.repositories.media.ArtworkRequestRepository.SavedArtworkRequest.Unchanged;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.NewArtworkRequest;
import com.streamarr.server.services.PendingArtworkRequest;
import com.streamarr.server.services.StoredArtworkRequest;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
  ArtworkTarget createMovie();

  /** Creates a person that artwork can be requested for. */
  ArtworkTarget createPerson();

  void deleteMovie(ArtworkTarget movie);

  /** Stores an artwork result as the step that records it would. */
  void saveResult(ItemResult result);

  /** Runs work that requires the caller's transaction. */
  <T> T inTransaction(Supplier<T> work);

  @Test
  @DisplayName("Should start a generation when a target requests artwork for the first time")
  default void shouldStartAGenerationWhenATargetRequestsArtworkForTheFirstTime() {
    var movie = createMovie();

    var saved = save(movie, ImageRefreshMode.PRESERVE, POSTER, BACKDROP);

    assertThat(saved).isInstanceOf(NewGeneration.class);
    assertThat(repository().findRequest(movie))
        .contains(
            StoredArtworkRequest.builder()
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
        .map(StoredArtworkRequest::refreshMode)
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
        .map(StoredArtworkRequest::artwork)
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
            StoredArtworkRequest.builder()
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
        .map(StoredArtworkRequest::generation)
        .contains(second.generation());
  }

  @Test
  @DisplayName("Should report no request left when a completed generation was already deleted")
  default void shouldReportNoRequestLeftWhenACompletedGenerationWasAlreadyDeleted() {
    var movie = createMovie();

    assertThat(inTransaction(() -> repository().tryCompleteGeneration(movie, 1))).isTrue();
  }

  @Test
  @DisplayName("Should lock and return the pending request when a request is pending")
  default void shouldLockAndReturnThePendingRequestWhenARequestIsPending() {
    var movie = createMovie();
    var saved = save(movie, ImageRefreshMode.PRESERVE, POSTER);
    var other = createMovie();

    assertThat(inTransaction(() -> repository().lockPendingRequest(movie)))
        .contains(new PendingArtworkRequest(saved.generation(), Optional.empty()));
    assertThat(inTransaction(() -> repository().lockPendingRequest(other))).isEmpty();
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
  @DisplayName("Should read the pending generation and artwork results when targets are checked")
  default void shouldReadThePendingGenerationAndArtworkResultsWhenTargetsAreChecked() {
    var pending = createMovie();
    var saved = save(pending, ImageRefreshMode.PRESERVE, POSTER);
    var finished = createMovie();
    var result = savedPoster(finished, 7);
    saveResult(result);

    var evidence = repository().findCompletionEvidence(List.of(pending, finished));

    assertThat(evidence)
        .containsExactlyInAnyOrder(
            new ArtworkCompletionEvidence(pending, Optional.of(saved.generation()), List.of()),
            new ArtworkCompletionEvidence(finished, Optional.empty(), List.of(result)));
  }

  @Test
  @DisplayName(
      "Should count pending images and the earliest request time by target type when requests are"
          + " pending")
  default void
      shouldCountPendingImagesAndTheEarliestRequestTimeByTargetTypeWhenRequestsArePending() {
    saveAt(createMovie(), REQUESTED_AT.plusSeconds(60), POSTER);
    saveAt(createMovie(), REQUESTED_AT, POSTER, BACKDROP);
    var person = createPerson();
    inTransaction(
        () ->
            repository()
                .saveRequest(
                    NewArtworkRequest.builder()
                        .artwork(
                            ArtworkSources.builder()
                                .targetId(person.targetId())
                                .targetType(ImageEntityType.PERSON)
                                .sources(
                                    List.of(new TmdbImageSource(ImageType.PROFILE, "/face.jpg")))
                                .build())
                        .refreshMode(ImageRefreshMode.PRESERVE)
                        .requestedAt(REQUESTED_AT.plusSeconds(30))
                        .build()));

    assertThat(repository().findArtworkBacklogByTargetType())
        .containsAllEntriesOf(
            Map.of(
                ImageEntityType.MOVIE,
                new ArtworkBacklog(4, REQUESTED_AT),
                ImageEntityType.PERSON,
                new ArtworkBacklog(1, REQUESTED_AT.plusSeconds(30))));
  }

  @Test
  @DisplayName(
      "Should leave images with a final result of the pending generation out of the pending count"
          + " when their request is pending")
  default void shouldLeaveImagesWithAFinalResultOutOfThePendingCountWhenTheirRequestIsPending() {
    var movie = createMovie();
    var saved = save(movie, ImageRefreshMode.PRESERVE, POSTER, BACKDROP);
    saveResult(savedPoster(movie, saved.generation()));
    saveResult(
        savedPoster(movie, saved.generation()).toBuilder()
            .imageType(ImageType.BACKDROP)
            .outcome(new ItemOutcome.Failed(ItemFailureReason.TEMPORARY, "timeout"))
            .build());
    var olderResult = createMovie();
    save(olderResult, ImageRefreshMode.PRESERVE, POSTER, BACKDROP);
    saveResult(savedPoster(olderResult, 0));

    assertThat(repository().findArtworkBacklogByTargetType())
        .containsEntry(ImageEntityType.MOVIE, new ArtworkBacklog(3, REQUESTED_AT));
  }

  @Test
  @DisplayName("Should keep the first request time when a changed request replaces a pending one")
  default void shouldKeepTheFirstRequestTimeWhenAChangedRequestReplacesAPendingOne() {
    var movie = createMovie();
    saveAt(movie, REQUESTED_AT, POSTER, BACKDROP);

    saveAt(movie, REQUESTED_AT.plusSeconds(60), OTHER_POSTER, BACKDROP);

    assertThat(repository().findArtworkBacklogByTargetType())
        .containsEntry(ImageEntityType.MOVIE, new ArtworkBacklog(2, REQUESTED_AT));
  }

  @Test
  @DisplayName("Should carry the library operation's id when an operation requests artwork first")
  default void shouldCarryTheLibraryOperationsIdWhenAnOperationRequestsArtworkFirst() {
    var movie = createMovie();
    var operation = UUID.randomUUID();

    var saved = saveFor(movie, operation, ImageRefreshMode.PRESERVE, POSTER);

    assertThat(saved).isInstanceOf(NewGeneration.class);
    assertThat(repository().findRequest(movie))
        .flatMap(StoredArtworkRequest::libraryOperationId)
        .contains(operation);
    assertThat(inTransaction(() -> repository().lockPendingRequest(movie)))
        .contains(new PendingArtworkRequest(saved.generation(), Optional.of(operation)));
  }

  @Test
  @DisplayName(
      "Should keep the pending request's library operation id when a request from outside an"
          + " operation changes the sources")
  default void shouldKeepThePendingLibraryOperationIdWhenARequestFromOutsideChangesTheSources() {
    var movie = createMovie();
    var operation = UUID.randomUUID();
    var first = saveFor(movie, operation, ImageRefreshMode.PRESERVE, POSTER);

    var second = save(movie, ImageRefreshMode.PRESERVE, OTHER_POSTER);

    assertThat(second.generation()).isGreaterThan(first.generation());
    assertThat(repository().findRequest(movie))
        .flatMap(StoredArtworkRequest::libraryOperationId)
        .contains(operation);
  }

  @Test
  @DisplayName(
      "Should keep the request unchanged when a request from outside an operation matches a"
          + " request that an operation waits for")
  default void shouldKeepTheRequestUnchangedWhenARequestFromOutsideMatchesAnOperationsRequest() {
    var movie = createMovie();
    var operation = UUID.randomUUID();
    var first = saveFor(movie, operation, ImageRefreshMode.PRESERVE, POSTER);

    var second = save(movie, ImageRefreshMode.PRESERVE, POSTER);

    assertThat(second).isEqualTo(new Unchanged(first.generation()));
    assertThat(repository().findRequest(movie))
        .flatMap(StoredArtworkRequest::libraryOperationId)
        .contains(operation);
  }

  @Test
  @DisplayName(
      "Should take over the pending generation when an operation requests artwork that a request"
          + " from outside an operation already covers")
  default void shouldTakeOverThePendingGenerationWhenAnOperationRequestsCoveredArtwork() {
    var movie = createMovie();
    var watcherRequest = save(movie, ImageRefreshMode.FORCE_REFRESH, POSTER);
    var operation = UUID.randomUUID();

    var saved = saveFor(movie, operation, ImageRefreshMode.PRESERVE, POSTER);

    assertThat(saved).isEqualTo(new TakenOver(watcherRequest.generation()));
    assertThat(repository().findRequest(movie))
        .flatMap(StoredArtworkRequest::libraryOperationId)
        .contains(operation);
  }

  @Test
  @DisplayName(
      "Should take over the pending generation when an operation requests artwork that an ended"
          + " operation requested")
  default void shouldTakeOverThePendingGenerationWhenAnOperationRequestsAnEndedOperationsArtwork() {
    var movie = createMovie();
    var endedOperation = saveFor(movie, UUID.randomUUID(), ImageRefreshMode.PRESERVE, POSTER);
    var operation = UUID.randomUUID();

    var saved = saveFor(movie, operation, ImageRefreshMode.PRESERVE, POSTER);

    assertThat(saved).isEqualTo(new TakenOver(endedOperation.generation()));
    assertThat(repository().findRequest(movie))
        .flatMap(StoredArtworkRequest::libraryOperationId)
        .contains(operation);
  }

  @Test
  @DisplayName("Should keep the request unchanged when the same operation requests it again")
  default void shouldKeepTheRequestUnchangedWhenTheSameOperationRequestsItAgain() {
    var movie = createMovie();
    var operation = UUID.randomUUID();
    var first = saveFor(movie, operation, ImageRefreshMode.PRESERVE, POSTER);

    var second = saveFor(movie, operation, ImageRefreshMode.PRESERVE, POSTER);

    assertThat(second).isEqualTo(new Unchanged(first.generation()));
  }

  @Test
  @DisplayName(
      "Should give the taking-over operation's id to a final result of the pending generation and"
          + " leave a result that waits to retry with its id")
  default void shouldGiveTheTakingOverOperationsIdToAFinalResultAndLeaveAResultThatWaitsToRetry() {
    var movie = createMovie();
    var endedOperation = UUID.randomUUID();
    var pending = saveFor(movie, endedOperation, ImageRefreshMode.PRESERVE, POSTER, BACKDROP);
    saveResult(savedPoster(movie, pending.generation()));
    saveResult(
        savedPoster(movie, pending.generation()).toBuilder()
            .imageType(ImageType.BACKDROP)
            .outcome(new ItemOutcome.Failed(ItemFailureReason.TEMPORARY, "timeout"))
            .build());
    var operation = UUID.randomUUID();

    saveFor(movie, operation, ImageRefreshMode.PRESERVE, POSTER, BACKDROP);

    assertThat(artworkResultsOf(movie))
        .extracting(ItemResult::imageType, ItemResult::libraryOperationId)
        .containsExactlyInAnyOrder(
            tuple(ImageType.POSTER, Optional.of(operation)),
            tuple(ImageType.BACKDROP, Optional.of(endedOperation)));
  }

  @Test
  @DisplayName(
      "Should leave a final result of an older generation with its id when an operation takes over"
          + " the pending generation")
  default void shouldLeaveAFinalResultOfAnOlderGenerationWithItsIdWhenAnOperationTakesOver() {
    var movie = createMovie();
    var endedOperation = UUID.randomUUID();
    var older = saveFor(movie, endedOperation, ImageRefreshMode.PRESERVE, POSTER);
    saveResult(
        savedPoster(movie, older.generation()).toBuilder()
            .imageType(ImageType.BACKDROP)
            .outcome(new ItemOutcome.Unavailable())
            .sourceKey(null)
            .build());
    var pending = save(movie, ImageRefreshMode.PRESERVE, OTHER_POSTER);
    saveResult(savedPoster(movie, pending.generation()));
    var operation = UUID.randomUUID();

    saveFor(movie, operation, ImageRefreshMode.PRESERVE, OTHER_POSTER);

    assertThat(artworkResultsOf(movie))
        .extracting(ItemResult::imageType, ItemResult::libraryOperationId)
        .containsExactlyInAnyOrder(
            tuple(ImageType.POSTER, Optional.of(operation)),
            tuple(ImageType.BACKDROP, Optional.of(endedOperation)));
  }

  @Test
  @DisplayName(
      "Should save an artwork result with the library operation id that its request carries")
  default void shouldSaveAnArtworkResultWithTheLibraryOperationIdThatItsRequestCarries() {
    var movie = createMovie();
    var operation = UUID.randomUUID();
    var saved = saveFor(movie, operation, ImageRefreshMode.PRESERVE, POSTER);

    saveResult(savedPoster(movie, saved.generation()));

    assertThat(artworkResultsOf(movie))
        .extracting(ItemResult::libraryOperationId)
        .containsExactly(Optional.of(operation));
  }

  @Test
  @DisplayName(
      "Should save an artwork result without a library operation id when its request carries none")
  default void shouldSaveAnArtworkResultWithoutALibraryOperationIdWhenItsRequestCarriesNone() {
    var movie = createMovie();
    var saved = save(movie, ImageRefreshMode.PRESERVE, POSTER);

    saveResult(
        savedPoster(movie, saved.generation()).toBuilder()
            .libraryOperationId(Optional.of(UUID.randomUUID()))
            .build());

    assertThat(artworkResultsOf(movie))
        .extracting(ItemResult::libraryOperationId)
        .containsExactly(Optional.empty());
  }

  @Test
  @DisplayName(
      "Should count the images of an operation's primary requests that have no result for the"
          + " operation")
  default void shouldCountTheImagesOfAnOperationsPrimaryRequestsThatHaveNoResultForTheOperation() {
    var operation = UUID.randomUUID();
    var movie = createMovie();
    var saved = saveFor(movie, operation, ImageRefreshMode.PRESERVE, POSTER, BACKDROP);
    saveResult(savedPoster(movie, saved.generation()));
    saveFor(createMovie(), UUID.randomUUID(), ImageRefreshMode.PRESERVE, POSTER);
    save(createMovie(), ImageRefreshMode.PRESERVE, POSTER);

    assertThat(repository().countPrimaryImagesAwaitingResultForLibraryOperation(operation)).isOne();
  }

  @Test
  @DisplayName(
      "Should count a result that waits to retry as a result for the operation when its images"
          + " are counted")
  default void shouldCountAResultThatWaitsToRetryAsAResultForTheOperation() {
    var operation = UUID.randomUUID();
    var movie = createMovie();
    var saved = saveFor(movie, operation, ImageRefreshMode.PRESERVE, POSTER);
    saveResult(
        savedPoster(movie, saved.generation()).toBuilder()
            .outcome(new ItemOutcome.Failed(ItemFailureReason.TEMPORARY, "timeout"))
            .build());
    saveResult(
        savedPoster(movie, saved.generation()).toBuilder()
            .imageType(ImageType.BACKDROP)
            .outcome(new ItemOutcome.Unavailable())
            .sourceKey(null)
            .build());

    assertThat(repository().countPrimaryImagesAwaitingResultForLibraryOperation(operation))
        .isZero();
  }

  @Test
  @DisplayName(
      "Should count an image whose only result belongs to an older generation or another operation")
  default void shouldCountAnImageWhoseOnlyResultBelongsToAnOlderGenerationOrAnotherOperation() {
    var endedOperation = UUID.randomUUID();
    var movie = createMovie();
    var older = saveFor(movie, endedOperation, ImageRefreshMode.PRESERVE, POSTER);
    saveResult(
        savedPoster(movie, older.generation()).toBuilder()
            .imageType(ImageType.BACKDROP)
            .outcome(new ItemOutcome.Failed(ItemFailureReason.TEMPORARY, "timeout"))
            .sourceKey(null)
            .build());
    var operation = UUID.randomUUID();
    saveFor(movie, operation, ImageRefreshMode.FORCE_REFRESH, POSTER);

    assertThat(repository().countPrimaryImagesAwaitingResultForLibraryOperation(operation))
        .isEqualTo(2);
  }

  private List<ItemResult> artworkResultsOf(ArtworkTarget target) {
    return repository().findCompletionEvidence(List.of(target)).getFirst().artworkResults();
  }

  private ArtworkRequestRepository.SavedArtworkRequest saveFor(
      ArtworkTarget target,
      UUID libraryOperationId,
      ImageRefreshMode refreshMode,
      ImageSource... sources) {
    return inTransaction(
        () ->
            repository()
                .saveRequest(
                    NewArtworkRequest.builder()
                        .artwork(artwork(target, sources))
                        .refreshMode(refreshMode)
                        .requestedAt(REQUESTED_AT)
                        .libraryOperationId(Optional.of(libraryOperationId))
                        .build()));
  }

  private ArtworkRequestRepository.SavedArtworkRequest saveAt(
      ArtworkTarget target, Instant requestedAt, ImageSource... sources) {
    return inTransaction(
        () ->
            repository()
                .saveRequest(
                    NewArtworkRequest.builder()
                        .artwork(artwork(target, sources))
                        .refreshMode(ImageRefreshMode.PRESERVE)
                        .requestedAt(requestedAt)
                        .build()));
  }

  private ArtworkRequestRepository.SavedArtworkRequest save(
      ArtworkTarget target, ImageRefreshMode refreshMode, ImageSource... sources) {
    return inTransaction(
        () ->
            repository()
                .saveRequest(
                    NewArtworkRequest.builder()
                        .artwork(artwork(target, sources))
                        .refreshMode(refreshMode)
                        .requestedAt(REQUESTED_AT)
                        .build()));
  }

  private static ArtworkSources artwork(ArtworkTarget target, ImageSource... sources) {
    return ArtworkSources.builder()
        .targetId(target.targetId())
        .targetType(target.targetType())
        .sources(List.of(sources))
        .build();
  }

  private static ItemResult savedPoster(ArtworkTarget target, long generation) {
    return ItemResult.builder()
        .itemId(target.targetId())
        .itemType(target.targetType())
        .step(ItemStep.ARTWORK)
        .imageType(ImageType.POSTER)
        .outcome(new ItemOutcome.Succeeded())
        .sourceKey("/poster.jpg")
        .attemptedAt(REQUESTED_AT)
        .artworkGeneration(generation)
        .build();
  }
}
