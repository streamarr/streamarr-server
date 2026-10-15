package com.streamarr.server.services;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.exceptions.ImageDownloadException;
import com.streamarr.server.fakes.CountingSleeper;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeItemResultRepository;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.GatedImageDownloader;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.ArtworkFixture;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.TmdbImageDownloader;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.support.BoundedTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Artwork Request Service Tests")
class ArtworkRequestServiceTest {

  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final ImageSource POSTER = new TmdbImageSource(ImageType.POSTER, "/poster.jpg");
  private static final ImageSource BACKDROP =
      new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg");

  private final FakeImageRepository imageRepository = new FakeImageRepository();
  private final FakeItemResultRepository itemResults = new FakeItemResultRepository();
  private final FakeTmdbHttpService imageDownloader = new FakeTmdbHttpService();
  private final MutableClock clock = new MutableClock();
  private ArtworkFixture artwork;
  private ArtworkRequestService artworkRequestService;

  @BeforeEach
  void setUp() {
    imageDownloader.setImageData(createTestImage(600, 900));
    artwork = artworkWith(imageDownloader);
    artworkRequestService = artwork.artworkRequestService();
  }

  @Nested
  @DisplayName("Primary artwork steps")
  class PrimaryArtworkSteps {

    static Stream<Arguments> primaryImageTypes() {
      return Stream.of(
          Arguments.of(ImageEntityType.MOVIE, List.of(ImageType.POSTER, ImageType.BACKDROP)),
          Arguments.of(ImageEntityType.SERIES, List.of(ImageType.POSTER, ImageType.BACKDROP)),
          Arguments.of(ImageEntityType.SEASON, List.of(ImageType.POSTER)),
          Arguments.of(ImageEntityType.EPISODE, List.of(ImageType.STILL)));
    }

    @Test
    @DisplayName("Should save each source image and its result when primary artwork is requested")
    void shouldSaveEachSourceImageAndItsResultWhenPrimaryArtworkIsRequested() {
      var movieId = UUID.randomUUID();

      requestPrimaryArtwork(movieArtwork(movieId, POSTER, BACKDROP));

      assertThat(outcomesOf(movieId))
          .containsOnly(
              Map.entry(ImageType.POSTER, new ItemOutcome.Succeeded()),
              Map.entry(ImageType.BACKDROP, new ItemOutcome.Succeeded()));
      assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
          .extracting(Image::getImageType)
          .containsOnly(ImageType.POSTER, ImageType.BACKDROP);
      assertThat(artwork.requests().requests()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("primaryImageTypes")
    @DisplayName("Should store unavailable when the provider has no primary image")
    void shouldStoreUnavailableWhenTheProviderHasNoPrimaryImage(
        ImageEntityType entityType, List<ImageType> primaryImageTypes) {
      var entityId = UUID.randomUUID();
      var sources =
          ArtworkSources.builder()
              .targetId(entityId)
              .targetType(entityType)
              .sources(List.of())
              .build();

      var summary = requestPrimaryArtwork(sources);

      assertThat(summary.counts())
          .isEqualTo(FinishedImageCounts.builder().unavailable(primaryImageTypes.size()).build());
      assertThat(itemResults.findByItem(entityId, entityType))
          .extracting(ItemResult::imageType)
          .containsExactlyInAnyOrderElementsOf(primaryImageTypes);
      assertThat(imageDownloader.getDownloadCount()).isZero();
    }

    @Test
    @DisplayName("Should finish the step with a download failure when the host answers not found")
    void shouldFinishTheStepWithADownloadFailureWhenTheHostAnswersNotFound() {
      var movieId = UUID.randomUUID();
      imageDownloader.failDownloadWith(
          "/poster.jpg", new ImageDownloadException(404, "/poster.jpg", Optional.empty()));

      var summary = requestPrimaryArtwork(movieArtwork(movieId, POSTER, BACKDROP));

      assertThat(summary.counts())
          .isEqualTo(FinishedImageCounts.builder().saved(1).failed(1).build());
      assertThat(failureReasonOf(movieId, ImageType.POSTER))
          .isEqualTo(ItemFailureReason.DOWNLOAD_FAILED);
      assertThat(artwork.scheduling().pendingTargets()).isEmpty();
    }

    @Test
    @DisplayName(
        "Should keep the step pending with a temporary failure when the download cannot connect")
    void shouldKeepTheStepPendingWithATemporaryFailureWhenTheDownloadCannotConnect() {
      var movieId = UUID.randomUUID();
      imageDownloader.setFailOnPath("/poster.jpg");

      var summary = requestPrimaryArtwork(movieArtwork(movieId, POSTER, BACKDROP));

      assertThat(summary.counts())
          .isEqualTo(FinishedImageCounts.builder().saved(1).failed(1).build());
      assertThat(failureReasonOf(movieId, ImageType.POSTER)).isEqualTo(ItemFailureReason.TEMPORARY);
      assertThat(artwork.scheduling().pendingTargets())
          .containsExactly(new ArtworkTarget(movieId, ImageEntityType.MOVIE));
    }

    @Test
    @DisplayName("Should save the image on a later attempt when a temporary failure passes")
    void shouldSaveTheImageOnALaterAttemptWhenATemporaryFailurePasses() {
      var movieId = UUID.randomUUID();
      imageDownloader.setFailOnPath("/poster.jpg");
      requestPrimaryArtwork(movieArtwork(movieId, POSTER, BACKDROP));
      var downloadsBeforeRetry = imageDownloader.getDownloadCount();

      imageDownloader.setFailOnPath(null);
      artwork.scheduling().runPendingSteps();

      assertThat(outcomesOf(movieId))
          .containsOnly(
              Map.entry(ImageType.POSTER, new ItemOutcome.Succeeded()),
              Map.entry(ImageType.BACKDROP, new ItemOutcome.Succeeded()));
      assertThat(imageDownloader.getDownloadCount()).isEqualTo(downloadsBeforeRetry + 1);
      assertThat(artwork.scheduling().pendingTargets()).isEmpty();
    }

    @Test
    @DisplayName("Should keep the step pending when processed images cannot be saved")
    void shouldKeepTheStepPendingWhenProcessedImagesCannotBeSaved() throws IOException {
      imageRepository.setFailOnInsertAllIfAbsent(true);
      var movieId = UUID.randomUUID();

      requestPrimaryArtwork(movieArtwork(movieId, POSTER));

      assertThat(failureReasonOf(movieId, ImageType.POSTER)).isEqualTo(ItemFailureReason.TEMPORARY);
      assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
          .isEmpty();
      assertThat(storedFiles()).isEmpty();
    }

    @Test
    @DisplayName(
        "Should count stored artwork as skipped and request nothing when preserve keeps it")
    void shouldCountStoredArtworkAsSkippedAndRequestNothingWhenPreserveKeepsIt() {
      var movieId = UUID.randomUUID();
      imageRepository.save(imageBuilder(movieId).key("/poster.jpg").path("movie/poster").build());
      imageRepository.save(
          imageBuilder(movieId)
              .imageType(ImageType.BACKDROP)
              .key("/backdrop.jpg")
              .path("movie/backdrop")
              .build());

      var summary = requestPrimaryArtwork(movieArtwork(movieId, POSTER, BACKDROP));

      assertThat(summary.counts()).isEqualTo(FinishedImageCounts.builder().skipped(2).build());
      assertThat(artwork.requests().requests()).isEmpty();
      assertThat(imageDownloader.getDownloadCount()).isZero();
    }

    @Test
    @DisplayName("Should count stored artwork as skipped when a missing source keeps it")
    void shouldCountStoredArtworkAsSkippedWhenAMissingSourceKeepsIt() {
      var movieId = UUID.randomUUID();
      imageRepository.save(imageBuilder(movieId).key("/poster.jpg").path("movie/poster").build());

      var summary = requestPrimaryArtwork(movieArtwork(movieId, BACKDROP));

      assertThat(summary.counts())
          .isEqualTo(FinishedImageCounts.builder().saved(1).skipped(1).build());
      assertThat(outcomesOf(movieId))
          .containsOnly(
              Map.entry(ImageType.POSTER, new ItemOutcome.Succeeded()),
              Map.entry(ImageType.BACKDROP, new ItemOutcome.Succeeded()));
    }

    @Test
    @DisplayName("Should keep stored artwork when a forced refresh finds no provider image")
    void shouldKeepStoredArtworkWhenAForcedRefreshFindsNoProviderImage() {
      var movieId = UUID.randomUUID();
      var stored =
          imageRepository.save(
              imageBuilder(movieId).key("/poster.jpg").path("movie/poster").build());

      var run = artworkRequestService.openRun("refresh", ImageRefreshMode.FORCE_REFRESH);
      artworkRequestService.requestPrimaryArtwork(run, movieArtwork(movieId));

      assertThat(artwork.closeAndAwait(run).counts())
          .isEqualTo(FinishedImageCounts.builder().unavailable(2).build());
      assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
          .extracting(Image::getId)
          .containsExactly(stored.getId());
    }

    @Test
    @DisplayName("Should fail the request when stored artwork cannot be read")
    void shouldFailTheRequestWhenStoredArtworkCannotBeRead() {
      var unreadableRepository =
          new FakeImageRepository() {
            @Override
            public List<Image> findByEntityIdAndEntityType(
                UUID entityId, ImageEntityType entityType) {
              throw new IllegalStateException("Simulated artwork lookup failure");
            }
          };
      var unreadable =
          ArtworkFixture.builder()
              .imageRepository(unreadableRepository)
              .imageDownloader(imageDownloader)
              .build();
      var unreadableArtworkRequestService = unreadable.artworkRequestService();
      var run = unreadableArtworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      var sources = movieArtwork(UUID.randomUUID(), POSTER);

      assertThatThrownBy(() -> unreadableArtworkRequestService.requestPrimaryArtwork(run, sources))
          .isInstanceOf(IllegalStateException.class);
      assertThat(unreadable.requests().requests()).isEmpty();
    }

    @Test
    @DisplayName(
        "Should store no result for the interrupted download and keep the step pending when an"
            + " attempt is interrupted")
    void shouldStoreNoResultForTheInterruptedDownloadAndKeepTheStepPendingWhenInterrupted()
        throws Exception {
      var downloader = new GatedImageDownloader(createTestImage(600, 900));
      downloader.holdPathsStartingWith("/");
      var interrupted = artworkWith(downloader);
      interrupted.scheduling().holdScheduledSteps();
      var movieId = UUID.randomUUID();
      var run = interrupted.artworkRequestService().openRun("scan", ImageRefreshMode.PRESERVE);
      interrupted.artworkRequestService().requestPrimaryArtwork(run, movieArtwork(movieId, POSTER));

      try (var attempt = BoundedTask.start(() -> interrupted.scheduling().runPendingSteps())) {
        downloader.awaitHeldDownloads(1, BOUND);
        attempt.interrupt();

        assertThat(attempt.await(BOUND))
            .singleElement()
            .isInstanceOf(ArtworkStepsOutcome.Interrupted.class);
      } finally {
        downloader.releaseHeldDownloads();
      }

      assertThat(itemResults.findByItem(movieId, ImageEntityType.MOVIE))
          .extracting(ItemResult::imageType)
          .containsExactly(ImageType.BACKDROP);
      assertThat(interrupted.scheduling().pendingTargets())
          .containsExactly(new ArtworkTarget(movieId, ImageEntityType.MOVIE));
    }
  }

  @Nested
  @DisplayName("Artwork generations")
  class ArtworkGenerations {

    @Test
    @DisplayName(
        "Should save nothing from an attempt when a forced refresh replaces its generation while it downloads")
    void shouldSaveNothingFromAnAttemptWhenAForcedRefreshReplacesItsGenerationWhileItDownloads()
        throws Exception {
      var downloader = new GatedImageDownloader(createTestImage(600, 900));
      downloader.holdPathsStartingWith("/poster");
      var raced = artworkWith(downloader);
      raced.scheduling().holdScheduledSteps();
      var movieId = UUID.randomUUID();
      var preserveRun = raced.artworkRequestService().openRun("scan", ImageRefreshMode.PRESERVE);
      raced
          .artworkRequestService()
          .requestPrimaryArtwork(preserveRun, movieArtwork(movieId, POSTER));
      var preserveGeneration = raced.requests().requests().getFirst().generation();

      try (var attempt = BoundedTask.start(() -> raced.scheduling().runPendingSteps())) {
        downloader.awaitHeldDownloads(1, BOUND);
        var forcedRun =
            raced.artworkRequestService().openRun("refresh", ImageRefreshMode.FORCE_REFRESH);
        raced
            .artworkRequestService()
            .requestPrimaryArtwork(forcedRun, movieArtwork(movieId, POSTER));
        downloader.releaseHeldDownloads();

        attempt.await(BOUND);
      } finally {
        downloader.releaseHeldDownloads();
      }

      assertThat(itemResults.findByItem(movieId, ImageEntityType.MOVIE))
          .isNotEmpty()
          .allSatisfy(
              result -> assertThat(result.artworkGeneration()).isGreaterThan(preserveGeneration));
      assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
          .extracting(Image::getImageType)
          .containsOnly(ImageType.POSTER);
      assertThat(raced.requests().requests()).isEmpty();
    }

    @Test
    @DisplayName("Should keep waiting when only a result of an older generation is stored")
    void shouldKeepWaitingWhenOnlyAResultOfAnOlderGenerationIsStored() {
      var movieId = UUID.randomUUID();
      var sleeper = new CountingSleeper();
      var waiting =
          ArtworkFixture.builder()
              .imageRepository(imageRepository)
              .imageDownloader(imageDownloader)
              .itemResults(itemResults)
              .clock(clock)
              .sleeper(sleeper)
              .build();
      waiting.scheduling().holdScheduledSteps();
      Stream.of(ImageType.POSTER, ImageType.BACKDROP)
          .forEach(type -> itemResults.overwrite(succeeded(movieId, type, 0)));
      sleeper.onSleep(_ -> waiting.scheduling().runPendingSteps());
      var run = waiting.artworkRequestService().openRun("scan", ImageRefreshMode.PRESERVE);
      waiting
          .artworkRequestService()
          .requestPrimaryArtwork(run, movieArtwork(movieId, POSTER, BACKDROP));

      var summary = waiting.closeAndAwait(run);

      assertThat(sleeper.sleeps()).isNotEmpty();
      assertThat(summary.counts()).isEqualTo(FinishedImageCounts.builder().saved(2).build());
    }

    @Test
    @DisplayName("Should stop waiting for a target when the target is deleted")
    void shouldStopWaitingForATargetWhenTheTargetIsDeleted() {
      artwork.scheduling().holdScheduledSteps();
      var movieId = UUID.randomUUID();
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      artworkRequestService.requestPrimaryArtwork(run, movieArtwork(movieId, POSTER));

      artwork.requests().deleteTarget(new ArtworkTarget(movieId, ImageEntityType.MOVIE));

      assertThat(artwork.closeAndAwait(run).counts()).isEqualTo(FinishedImageCounts.NONE);
    }
  }

  @Nested
  @DisplayName("Artwork runs")
  class ArtworkRunWaiter {

    @Test
    @DisplayName("Should count each source image once when results are recorded")
    void shouldCountEachSourceImageOnceWhenResultsAreRecorded() {
      imageDownloader.setFailOnPath("/other-poster.jpg");
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      var skippedMovieId = UUID.randomUUID();
      imageRepository.save(
          imageBuilder(skippedMovieId).key("/poster.jpg").path("movie/poster").build());

      artworkRequestService.requestPrimaryArtwork(
          run, movieArtwork(UUID.randomUUID(), POSTER, BACKDROP));
      artworkRequestService.requestPrimaryArtwork(run, movieArtwork(skippedMovieId, POSTER));
      artworkRequestService.requestPrimaryArtwork(
          run,
          movieArtwork(
              UUID.randomUUID(), new TmdbImageSource(ImageType.POSTER, "/other-poster.jpg")));

      assertThat(artwork.closeAndAwait(run).counts())
          .isEqualTo(
              FinishedImageCounts.builder().saved(2).skipped(1).unavailable(2).failed(1).build());
    }

    @Test
    @DisplayName("Should count the newest request's images once when a run requests a target twice")
    void shouldCountTheNewestRequestsImagesOnceWhenARunRequestsATargetTwice() {
      artwork.scheduling().holdScheduledSteps();
      var run = artworkRequestService.openRun("refresh", ImageRefreshMode.PRESERVE);
      var movieId = UUID.randomUUID();

      artworkRequestService.requestPrimaryArtwork(run, movieArtwork(movieId, POSTER));
      artworkRequestService.requestPrimaryArtwork(run, movieArtwork(movieId, POSTER, BACKDROP));
      artwork.scheduling().runPendingSteps();

      assertThat(artwork.closeAndAwait(run).counts())
          .isEqualTo(FinishedImageCounts.builder().saved(2).build());
    }

    @Test
    @DisplayName("Should finish a run with no counts when it is closed before any request")
    void shouldFinishARunWithNoCountsWhenItIsClosedBeforeAnyRequest() {
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);

      var summary = artwork.closeAndAwait(run);

      assertThat(summary.counts()).isEqualTo(FinishedImageCounts.NONE);
      assertThat(summary.elapsed()).isZero();
      assertThat(summary.description()).isEqualTo("scan");
    }

    @Test
    @DisplayName("Should measure a run from its first request when its last result is stored")
    void shouldMeasureARunFromItsFirstRequestWhenItsLastResultIsStored() {
      artwork.scheduling().holdScheduledSteps();
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      clock.advance(Duration.ofSeconds(10));
      artworkRequestService.requestPrimaryArtwork(run, movieArtwork(UUID.randomUUID(), POSTER));
      clock.advance(Duration.ofSeconds(4));

      artwork.scheduling().runPendingSteps();

      assertThat(artwork.closeAndAwait(run).elapsed()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    @DisplayName("Should reject primary artwork when the run is closed")
    void shouldRejectPrimaryArtworkWhenTheRunIsClosed() {
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      run.close();
      var sources = movieArtwork(UUID.randomUUID(), POSTER);

      assertThatThrownBy(() -> artworkRequestService.requestPrimaryArtwork(run, sources))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("scan");
    }

    @Test
    @DisplayName(
        "Should keep waiting while a result cannot be stored and finish when a retry stores it")
    void shouldKeepWaitingWhileAResultCannotBeStoredAndFinishWhenARetryStoresIt() throws Exception {
      itemResults.failArtworkResultWritesWith(
          new DataAccessResourceFailureException("database unavailable"), 1);
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      artworkRequestService.requestPrimaryArtwork(run, movieArtwork(UUID.randomUUID(), POSTER));

      try (var wait = BoundedTask.start(() -> artwork.closeAndAwait(run))) {
        artwork.scheduling().awaitUnfinishedAttempt(BOUND);

        artwork.scheduling().runPendingSteps();

        assertThat(wait.await(BOUND).counts())
            .isEqualTo(FinishedImageCounts.builder().saved(1).unavailable(1).build());
      }
    }
  }

  @Nested
  @DisplayName("Background artwork")
  class BackgroundArtwork {

    static Stream<Arguments> backgroundImageTypes() {
      return Stream.of(
          Arguments.of(ImageEntityType.PERSON, ImageType.PROFILE),
          Arguments.of(ImageEntityType.COMPANY, ImageType.LOGO));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backgroundImageTypes")
    @DisplayName("Should save background artwork and its result when the provider supplies one")
    void shouldSaveBackgroundArtworkAndItsResultWhenTheProviderSuppliesOne(
        ImageEntityType entityType, ImageType imageType) {
      var entityId = UUID.randomUUID();
      var sources =
          ArtworkSources.builder()
              .targetId(entityId)
              .targetType(entityType)
              .sources(List.of(new TmdbImageSource(imageType, "/background.jpg")))
              .build();

      artworkRequestService.requestBackgroundArtwork(sources, ImageRefreshMode.PRESERVE);

      assertThat(itemResults.findByItem(entityId, entityType))
          .extracting(ItemResult::imageType, ItemResult::outcome)
          .containsExactly(tuple(imageType, new ItemOutcome.Succeeded()));
      assertThat(imageRepository.findByEntityIdAndEntityType(entityId, entityType))
          .extracting(Image::getImageType)
          .containsOnly(imageType);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backgroundImageTypes")
    @DisplayName("Should store unavailable when the provider has no background image")
    void shouldStoreUnavailableWhenTheProviderHasNoBackgroundImage(
        ImageEntityType entityType, ImageType imageType) {
      var entityId = UUID.randomUUID();
      var sources =
          ArtworkSources.builder()
              .targetId(entityId)
              .targetType(entityType)
              .sources(List.of())
              .build();

      artworkRequestService.requestBackgroundArtwork(sources, ImageRefreshMode.PRESERVE);

      assertThat(itemResults.findByItem(entityId, entityType))
          .extracting(ItemResult::imageType, ItemResult::outcome)
          .containsExactly(tuple(imageType, new ItemOutcome.Unavailable()));
    }

    @Test
    @DisplayName("Should request nothing when preserve keeps every stored background image")
    void shouldRequestNothingWhenPreserveKeepsEveryStoredBackgroundImage() {
      var person = personArtwork("/profile.jpg");
      imageRepository.save(
          imageBuilder(person.targetId())
              .entityType(ImageEntityType.PERSON)
              .imageType(ImageType.PROFILE)
              .key("/old-profile.jpg")
              .path("person/profile")
              .build());

      artworkRequestService.requestBackgroundArtwork(person, ImageRefreshMode.PRESERVE);

      assertThat(artwork.requests().requests()).isEmpty();
      assertThat(imageDownloader.getDownloadCount()).isZero();
    }

    @Test
    @DisplayName(
        "Should count pending background images apart from primary images when both are pending")
    void shouldCountPendingBackgroundImagesApartFromPrimaryImagesWhenBothArePending() {
      artwork.scheduling().holdScheduledSteps();
      artworkRequestService.requestBackgroundArtwork(
          personArtwork("/profile.jpg"), ImageRefreshMode.PRESERVE);
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      artworkRequestService.requestPrimaryArtwork(run, movieArtwork(UUID.randomUUID(), POSTER));

      assertThat(artworkRequestService.pendingBackgroundImages()).isOne();
    }
  }

  @Nested
  @DisplayName("Server-wide progress")
  class ServerWideProgress {

    @Test
    @DisplayName("Should report nothing when no artwork is pending")
    void shouldReportNothingWhenNoArtworkIsPending() {
      assertThat(artwork.progressReporter().reportProgress()).isEmpty();
    }

    @Test
    @DisplayName(
        "Should measure the busy period from the earliest pending request when images are pending")
    void shouldMeasureTheBusyPeriodFromTheEarliestPendingRequestWhenImagesArePending() {
      artwork.scheduling().holdScheduledSteps();
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      artworkRequestService.requestPrimaryArtwork(
          run, movieArtwork(UUID.randomUUID(), POSTER, BACKDROP));
      artwork.progressReporter().reportProgress();
      clock.advance(Duration.ofSeconds(5));

      var reports = artwork.progressReporter().reportProgress();

      assertThat(reports)
          .containsExactly(
              ArtworkProgressReport.builder()
                  .priority(ArtworkPriority.PRIMARY)
                  .counts(FinishedImageCounts.NONE)
                  .pending(2)
                  .elapsed(Duration.ofSeconds(5))
                  .build());
    }

    @Test
    @DisplayName("Should finish the busy period with its stored results when nothing is pending")
    void shouldFinishTheBusyPeriodWithItsStoredResultsWhenNothingIsPending() {
      itemResults.overwrite(succeeded(UUID.randomUUID(), ImageType.POSTER, 1));
      clock.advance(Duration.ofSeconds(1));
      artwork.scheduling().holdScheduledSteps();
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      artworkRequestService.requestPrimaryArtwork(run, movieArtwork(UUID.randomUUID(), POSTER));
      artwork.progressReporter().reportProgress();
      clock.advance(Duration.ofSeconds(4));
      artwork.scheduling().runPendingSteps();

      var reports = artwork.progressReporter().reportProgress();

      assertThat(reports)
          .singleElement()
          .satisfies(
              report -> {
                assertThat(report.isFinished()).isTrue();
                assertThat(report.elapsed()).isEqualTo(Duration.ofSeconds(4));
                assertThat(report.counts())
                    .isEqualTo(FinishedImageCounts.builder().saved(1).unavailable(1).build());
              });
      assertThat(artwork.progressReporter().reportProgress()).isEmpty();
    }

    @Test
    @DisplayName(
        "Should count results stored before the first report when their request was made earlier")
    void shouldCountResultsStoredBeforeTheFirstReportWhenTheirRequestWasMadeEarlier() {
      requestMovieWithASavedPosterAndARetryingBackdrop();
      clock.advance(Duration.ofSeconds(20));

      var reports = artwork.progressReporter().reportProgress();

      assertThat(reports).containsExactly(savedPosterAndRetryingBackdropReport());
    }

    @Test
    @DisplayName("Should report the same progress when a new reporter starts after a restart")
    void shouldReportTheSameProgressWhenANewReporterStartsAfterARestart() {
      requestMovieWithASavedPosterAndARetryingBackdrop();
      clock.advance(Duration.ofSeconds(20));
      artwork.progressReporter().reportProgress();

      var afterRestart = new ArtworkProgressReporter(artwork.requests(), itemResults, clock);

      assertThat(afterRestart.reportProgress())
          .containsExactly(savedPosterAndRetryingBackdropReport());
    }

    // The movie is requested, and ten seconds later its poster is saved while its backdrop waits
    // to retry a temporary failure.
    private void requestMovieWithASavedPosterAndARetryingBackdrop() {
      artwork.scheduling().holdScheduledSteps();
      imageDownloader.setFailOnPath("/backdrop.jpg");
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      artworkRequestService.requestPrimaryArtwork(
          run, movieArtwork(UUID.randomUUID(), POSTER, BACKDROP));
      clock.advance(Duration.ofSeconds(10));
      artwork.scheduling().runPendingSteps();
    }

    private static ArtworkProgressReport savedPosterAndRetryingBackdropReport() {
      return ArtworkProgressReport.builder()
          .priority(ArtworkPriority.PRIMARY)
          .counts(FinishedImageCounts.builder().saved(1).failed(1).build())
          .pending(1)
          .elapsed(Duration.ofSeconds(30))
          .build();
    }

    @Test
    @DisplayName("Should report primary and background artwork separately when both are pending")
    void shouldReportPrimaryAndBackgroundArtworkSeparatelyWhenBothArePending() {
      artwork.scheduling().holdScheduledSteps();
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      artworkRequestService.requestPrimaryArtwork(
          run, movieArtwork(UUID.randomUUID(), POSTER, BACKDROP));
      artworkRequestService.requestBackgroundArtwork(
          personArtwork("/profile.jpg"), ImageRefreshMode.PRESERVE);
      artwork.progressReporter().reportProgress();
      artwork.scheduling().runPendingSteps();

      var reports = artwork.progressReporter().reportProgress();

      assertThat(reports)
          .extracting(ArtworkProgressReport::priority, ArtworkProgressReport::counts)
          .containsExactlyInAnyOrder(
              tuple(ArtworkPriority.PRIMARY, FinishedImageCounts.builder().saved(2).build()),
              tuple(ArtworkPriority.BACKGROUND, FinishedImageCounts.builder().saved(1).build()));
    }
  }

  @Nested
  @DisplayName("Task runs")
  class TaskRuns {

    private final TransactionTemplate transactionTemplate =
        new TransactionTemplate(new FakeTransactionManager());

    @Test
    @DisplayName(
        "Should finish a task run's wait when only the artwork that the task requested is recorded")
    void shouldFinishATaskRunsWaitWhenOnlyTheArtworkThatTheTaskRequestedIsRecorded()
        throws Exception {
      var operation = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      var finishedTask = operation.openTaskRun();
      artworkRequestService.requestPrimaryArtwork(
          finishedTask, movieArtwork(UUID.randomUUID(), POSTER));
      finishedTask.close();
      artwork.scheduling().holdScheduledSteps();
      var pendingTask = operation.openTaskRun();
      artworkRequestService.requestPrimaryArtwork(
          pendingTask, movieArtwork(UUID.randomUUID(), POSTER));

      BoundedTask.runWithin(BOUND, () -> artwork.artworkRunWaiter().awaitTaskResults(finishedTask));

      assertThat(artwork.scheduling().pendingTargets()).hasSize(1);
    }

    @Test
    @DisplayName("Should check a task run's results every task result check interval")
    void shouldCheckATaskRunsResultsEveryTaskResultCheckInterval() throws Exception {
      var sleeper = new CountingSleeper();
      var waiting =
          ArtworkFixture.builder()
              .imageRepository(imageRepository)
              .imageDownloader(imageDownloader)
              .itemResults(itemResults)
              .clock(clock)
              .sleeper(sleeper)
              .build();
      waiting.scheduling().holdScheduledSteps();
      sleeper.onSleep(_ -> waiting.scheduling().runPendingSteps());
      var task = waiting.artworkService().openRun("scan", ImageRefreshMode.PRESERVE).openTaskRun();
      waiting.artworkService().requestPrimaryArtwork(task, movieArtwork(UUID.randomUUID(), POSTER));
      task.close();

      BoundedTask.runWithin(BOUND, () -> waiting.artworkRuns().awaitTaskResults(task));

      assertThat(sleeper.sleeps()).isNotEmpty().containsOnly(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("Should count a task run's requests in the operation's run")
    void shouldCountATaskRunsRequestsInTheOperationsRun() {
      var operation = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      var keptMovieId = UUID.randomUUID();
      imageRepository.save(imageBuilder(keptMovieId).key("/poster.jpg").path("movie/p").build());

      try (var task = operation.openTaskRun()) {
        artworkRequestService.requestPrimaryArtwork(
            task, movieArtwork(UUID.randomUUID(), POSTER, BACKDROP));
        artworkRequestService.requestPrimaryArtwork(task, movieArtwork(keptMovieId, POSTER));
      }

      assertThat(artwork.closeAndAwait(operation).counts())
          .isEqualTo(FinishedImageCounts.builder().saved(2).skipped(1).unavailable(1).build());
    }

    @Test
    @DisplayName(
        "Should withdraw a task run's request from the operation's run when its transaction rolls back")
    void shouldWithdrawATaskRunsRequestFromTheOperationsRunWhenItsTransactionRollsBack() {
      artwork.scheduling().holdScheduledSteps();
      var operation = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      var task = operation.openTaskRun();
      var keptMovieId = UUID.randomUUID();
      imageRepository.save(imageBuilder(keptMovieId).key("/poster.jpg").path("movie/p").build());

      transactionTemplate.executeWithoutResult(
          status -> {
            status.setRollbackOnly();
            artworkRequestService.requestPrimaryArtwork(
                task, movieArtwork(UUID.randomUUID(), POSTER));
            artworkRequestService.requestPrimaryArtwork(task, movieArtwork(keptMovieId, POSTER));
          });
      task.close();

      assertThat(artwork.closeAndAwait(operation).counts()).isEqualTo(FinishedImageCounts.NONE);
    }

    @Test
    @DisplayName("Should return at once without reading results when the task requested no artwork")
    void shouldReturnAtOnceWithoutReadingResultsWhenTheTaskRequestedNoArtwork() throws Exception {
      var task = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE).openTaskRun();
      task.close();

      artwork.artworkRunWaiter().awaitTaskResults(task);

      assertThat(artwork.requests().artworkStateReads()).isZero();
    }
  }

  @Nested
  @DisplayName("Owning transactions")
  class OwningTransactions {

    private final TransactionTemplate transactionTemplate =
        new TransactionTemplate(new FakeTransactionManager());

    @Test
    @DisplayName("Should keep the request in the run when the owning transaction commits")
    void shouldKeepTheRequestInTheRunWhenTheOwningTransactionCommits() {
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);

      transactionTemplate.executeWithoutResult(
          _ ->
              artworkRequestService.requestPrimaryArtwork(
                  run, movieArtwork(UUID.randomUUID(), POSTER, BACKDROP)));

      assertThat(artwork.closeAndAwait(run).counts().saved()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should withdraw the request from the run when the owning transaction rolls back")
    void shouldWithdrawTheRequestFromTheRunWhenTheOwningTransactionRollsBack() {
      artwork.scheduling().holdScheduledSteps();
      var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
      var keptMovieId = UUID.randomUUID();
      imageRepository.save(imageBuilder(keptMovieId).key("/poster.jpg").path("movie/p").build());

      transactionTemplate.executeWithoutResult(
          status -> {
            status.setRollbackOnly();
            artworkRequestService.requestPrimaryArtwork(
                run, movieArtwork(UUID.randomUUID(), POSTER));
            artworkRequestService.requestPrimaryArtwork(run, movieArtwork(keptMovieId, POSTER));
          });

      assertThat(artwork.closeAndAwait(run).counts()).isEqualTo(FinishedImageCounts.NONE);
      assertThat(imageDownloader.getDownloadCount()).isZero();
    }
  }

  @Test
  @DisplayName("Should route steps to the scheduler of their priority when they are scheduled")
  void shouldRouteStepsToTheSchedulerOfTheirPriorityWhenTheyAreScheduled() {
    assertThat(
            Stream.of(ImageEntityType.values())
                .collect(Collectors.toMap(type -> type, ArtworkPriority::of)))
        .containsOnly(
            Map.entry(ImageEntityType.MOVIE, ArtworkPriority.PRIMARY),
            Map.entry(ImageEntityType.SERIES, ArtworkPriority.PRIMARY),
            Map.entry(ImageEntityType.SEASON, ArtworkPriority.PRIMARY),
            Map.entry(ImageEntityType.EPISODE, ArtworkPriority.PRIMARY),
            Map.entry(ImageEntityType.PERSON, ArtworkPriority.BACKGROUND),
            Map.entry(ImageEntityType.COMPANY, ArtworkPriority.BACKGROUND));
  }

  private ArtworkRunSummary requestPrimaryArtwork(ArtworkSources sources) {
    var run = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
    artworkRequestService.requestPrimaryArtwork(run, sources);
    return artwork.closeAndAwait(run);
  }

  private List<Path> storedFiles() throws IOException {
    try (var files = Files.walk(artwork.fileSystem().getPath("/data/images"))) {
      return files.filter(Files::isRegularFile).toList();
    }
  }

  private Map<ImageType, ItemOutcome> outcomesOf(UUID movieId) {
    return itemResults.findByItem(movieId, ImageEntityType.MOVIE).stream()
        .filter(result -> result.step() == ItemStep.ARTWORK)
        .collect(Collectors.toMap(ItemResult::imageType, ItemResult::outcome));
  }

  private ItemFailureReason failureReasonOf(UUID movieId, ImageType imageType) {
    var outcome = outcomesOf(movieId).get(imageType);
    assertThat(outcome).isInstanceOf(ItemOutcome.Failed.class);
    return ((ItemOutcome.Failed) outcome).reason();
  }

  private ItemResult succeeded(UUID movieId, ImageType imageType, long generation) {
    return ItemResult.builder()
        .itemId(movieId)
        .itemType(ImageEntityType.MOVIE)
        .step(ItemStep.ARTWORK)
        .imageType(imageType)
        .outcome(new ItemOutcome.Succeeded())
        .sourceKey("/stored.jpg")
        .attemptedAt(clock.instant())
        .artworkGeneration(generation)
        .build();
  }

  private ArtworkFixture artworkWith(TmdbImageDownloader downloader) {
    return ArtworkFixture.builder()
        .imageRepository(imageRepository)
        .imageDownloader(downloader)
        .itemResults(itemResults)
        .clock(clock)
        .build();
  }

  private static ArtworkSources movieArtwork(UUID movieId, ImageSource... sources) {
    return ArtworkSources.builder()
        .targetId(movieId)
        .targetType(ImageEntityType.MOVIE)
        .sources(List.of(sources))
        .build();
  }

  private static ArtworkSources personArtwork(String profilePath) {
    return ArtworkSources.builder()
        .targetId(UUID.randomUUID())
        .targetType(ImageEntityType.PERSON)
        .sources(List.of(new TmdbImageSource(ImageType.PROFILE, profilePath)))
        .build();
  }
}
