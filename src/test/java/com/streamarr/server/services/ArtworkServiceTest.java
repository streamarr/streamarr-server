package com.streamarr.server.services;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.GatedImageDownloader;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.ArtworkServiceFixture;
import com.streamarr.server.services.ArtworkResult.Failed;
import com.streamarr.server.services.ArtworkResult.Saved;
import com.streamarr.server.services.ArtworkResult.Skipped;
import com.streamarr.server.services.ArtworkResult.Unavailable;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.TmdbImageDownloader;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Artwork Service Tests")
class ArtworkServiceTest {

  private static final ImageSource POSTER = new TmdbImageSource(ImageType.POSTER, "/poster.jpg");
  private static final ImageSource BACKDROP =
      new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg");

  private final FakeImageRepository imageRepository = new FakeImageRepository();
  private final FakeTmdbHttpService imageDownloader = new FakeTmdbHttpService();
  private final MutableClock clock = new MutableClock();
  private final ArtworkProgress progress = new ArtworkProgress(clock);
  private ArtworkService artworkService;

  @BeforeEach
  void setUp() {
    imageDownloader.setImageData(createTestImage(600, 900));
    artworkService = artworkServiceWith(imageDownloader);
  }

  @Nested
  @DisplayName("Required artwork results")
  class RequiredArtworkResults {

    static Stream<Arguments> requiredImageTypes() {
      return Stream.of(
          Arguments.of(ImageEntityType.MOVIE, List.of(ImageType.POSTER, ImageType.BACKDROP)),
          Arguments.of(ImageEntityType.SERIES, List.of(ImageType.POSTER, ImageType.BACKDROP)),
          Arguments.of(ImageEntityType.SEASON, List.of(ImageType.POSTER)),
          Arguments.of(ImageEntityType.EPISODE, List.of(ImageType.STILL)));
    }

    @Test
    @DisplayName("Should save each source image when required artwork is fetched")
    void shouldSaveEachSourceImageWhenRequiredArtworkIsFetched() {
      var movieId = UUID.randomUUID();

      var results = fetchRequired(movieArtwork(movieId, POSTER, BACKDROP));

      assertThat(results)
          .containsExactlyInAnyOrder(new Saved(ImageType.POSTER), new Saved(ImageType.BACKDROP));
      assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
          .extracting(Image::getImageType)
          .containsOnly(ImageType.POSTER, ImageType.BACKDROP);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredImageTypes")
    @DisplayName("Should report unavailable when provider has no required image")
    void shouldReportUnavailableWhenProviderHasNoRequiredImage(
        ImageEntityType entityType, List<ImageType> requiredImageTypes) {
      var artwork =
          ArtworkSources.builder()
              .entityId(UUID.randomUUID())
              .entityType(entityType)
              .sources(List.of())
              .build();

      var results = fetchRequired(artwork);

      assertThat(results)
          .extracting(ArtworkResult::imageType)
          .containsExactlyInAnyOrderElementsOf(requiredImageTypes);
      assertThat(results).allMatch(Unavailable.class::isInstance);
      assertThat(imageDownloader.getDownloadCount()).isZero();
    }

    @Test
    @DisplayName("Should report failure with cause when a download fails")
    void shouldReportFailureWithCauseWhenDownloadFails() {
      imageDownloader.setFailOnPath("/poster.jpg");

      var results = fetchRequired(movieArtwork(UUID.randomUUID(), POSTER, BACKDROP));

      assertThat(results).contains(new Saved(ImageType.BACKDROP));
      assertThat(results)
          .filteredOn(Failed.class::isInstance)
          .singleElement()
          .isInstanceOfSatisfying(
              Failed.class,
              failed -> {
                assertThat(failed.imageType()).isEqualTo(ImageType.POSTER);
                assertThat(failed.cause()).isInstanceOf(IOException.class);
              });
    }

    @Test
    @DisplayName("Should report failure when processed images cannot be saved")
    void shouldReportFailureWhenProcessedImagesCannotBeSaved() {
      imageRepository.setFailOnInsertAllIfAbsent(true);
      var movieId = UUID.randomUUID();

      var results = fetchRequired(movieArtwork(movieId, POSTER));

      assertThat(results)
          .filteredOn(Failed.class::isInstance)
          .extracting(ArtworkResult::imageType)
          .containsExactly(ImageType.POSTER);
      assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
          .isEmpty();
    }

    @Test
    @DisplayName("Should report skipped when stored artwork is preserved")
    void shouldReportSkippedWhenStoredArtworkIsPreserved() {
      var movieId = UUID.randomUUID();
      imageRepository.save(imageBuilder(movieId).key("/poster.jpg").path("movie/poster").build());

      var results = fetchRequired(movieArtwork(movieId, POSTER, BACKDROP));

      assertThat(results)
          .containsExactlyInAnyOrder(new Skipped(ImageType.POSTER), new Saved(ImageType.BACKDROP));
      assertThat(imageDownloader.getDownloadCount()).isOne();
    }

    @Test
    @DisplayName("Should report skipped when missing source has stored artwork that is preserved")
    void shouldReportSkippedWhenMissingSourceHasStoredArtworkThatIsPreserved() {
      var movieId = UUID.randomUUID();
      imageRepository.save(imageBuilder(movieId).key("/poster.jpg").path("movie/poster").build());

      var results = fetchRequired(movieArtwork(movieId, BACKDROP));

      assertThat(results)
          .containsExactlyInAnyOrder(new Skipped(ImageType.POSTER), new Saved(ImageType.BACKDROP));
    }

    @Test
    @DisplayName("Should keep stored artwork when forced refresh finds no provider image")
    void shouldKeepStoredArtworkWhenForcedRefreshFindsNoProviderImage() {
      var movieId = UUID.randomUUID();
      var stored =
          imageRepository.save(
              imageBuilder(movieId).key("/poster.jpg").path("movie/poster").build());

      List<ArtworkResult> results;
      try (var run = artworkService.openRun("refresh", ImageRefreshMode.FORCE_REFRESH)) {
        results = awaitResult(artworkService.fetchRequired(run, movieArtwork(movieId)));
      }

      assertThat(results)
          .containsExactlyInAnyOrder(
              new Unavailable(ImageType.POSTER), new Unavailable(ImageType.BACKDROP));
      assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
          .extracting(Image::getId)
          .containsExactly(stored.getId());
    }

    @Test
    @DisplayName("Should report failure when the service stops during a download")
    void shouldReportFailureWhenServiceStopsDuringDownload() {
      var downloader = new GatedImageDownloader(createTestImage(600, 900));
      downloader.holdPathsStartingWith("/");
      var service = artworkServiceWith(downloader);
      var run = service.openRun("scan", ImageRefreshMode.PRESERVE);
      var request = service.fetchRequired(run, movieArtwork(UUID.randomUUID(), POSTER, BACKDROP));
      await().atMost(Duration.ofSeconds(5)).until(() -> downloader.heldDownloads() == 2);

      service.shutdown();
      run.close();

      assertThat(awaitResult(request))
          .allMatch(Failed.class::isInstance)
          .extracting(ArtworkResult::imageType)
          .containsExactlyInAnyOrder(ImageType.POSTER, ImageType.BACKDROP);
      assertThat(awaitResult(run.completion()).counts())
          .isEqualTo(ArtworkCounts.builder().failed(2).build());
    }

    @Test
    @DisplayName("Should report failure when required artwork is requested after the service stops")
    void shouldReportFailureWhenRequiredArtworkIsRequestedAfterServiceStops() {
      artworkService.shutdown();

      var results = fetchRequired(movieArtwork(UUID.randomUUID(), POSTER));

      assertThat(results)
          .allMatch(Failed.class::isInstance)
          .extracting(ArtworkResult::imageType)
          .containsExactlyInAnyOrder(ImageType.POSTER, ImageType.BACKDROP);
    }
  }

  @Nested
  @DisplayName("Artwork runs")
  class ArtworkRuns {

    @Test
    @DisplayName("Should keep run pending when finished requests leave discovery open")
    void shouldKeepRunPendingWhenFinishedRequestsLeaveDiscoveryOpen() {
      var run = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);

      awaitResult(artworkService.fetchRequired(run, movieArtwork(UUID.randomUUID(), POSTER)));

      assertThat(run.completion()).isNotDone();

      run.close();

      assertThat(awaitResult(run.completion()).counts())
          .isEqualTo(ArtworkCounts.builder().saved(1).unavailable(1).build());
    }

    @Test
    @DisplayName("Should count each source image once when requests finish")
    void shouldCountEachSourceImageOnceWhenRequestsFinish() {
      imageDownloader.setFailOnPath("/other-poster.jpg");
      var run = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);
      var skippedMovieId = UUID.randomUUID();
      imageRepository.save(
          imageBuilder(skippedMovieId).key("/poster.jpg").path("movie/poster").build());

      var requests =
          List.of(
              artworkService.fetchRequired(run, movieArtwork(UUID.randomUUID(), POSTER, BACKDROP)),
              artworkService.fetchRequired(run, movieArtwork(skippedMovieId, POSTER)),
              artworkService.fetchRequired(
                  run,
                  movieArtwork(
                      UUID.randomUUID(),
                      new TmdbImageSource(ImageType.POSTER, "/other-poster.jpg"))));
      requests.forEach(ArtworkServiceTest::awaitResult);
      run.close();

      assertThat(awaitResult(run.completion()).counts())
          .isEqualTo(ArtworkCounts.builder().saved(2).skipped(1).unavailable(2).failed(1).build());
    }

    @Test
    @DisplayName("Should complete run with no counts when closed before any request")
    void shouldCompleteRunWithNoCountsWhenClosedBeforeAnyRequest() {
      var run = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);

      run.close();

      var summary = awaitResult(run.completion());
      assertThat(summary.counts()).isEqualTo(ArtworkCounts.builder().build());
      assertThat(summary.elapsed()).isZero();
    }

    @Test
    @DisplayName("Should time run from first request until discovery closes")
    void shouldTimeRunFromFirstRequestUntilDiscoveryCloses() {
      var run = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);
      clock.advance(Duration.ofSeconds(10));

      awaitResult(artworkService.fetchRequired(run, movieArtwork(UUID.randomUUID(), POSTER)));
      clock.advance(Duration.ofSeconds(3));
      run.close();
      clock.advance(Duration.ofSeconds(30));

      assertThat(awaitResult(run.completion()).elapsed()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("Should time run until the last request finishes after discovery closes")
    void shouldTimeRunUntilLastRequestFinishesAfterDiscoveryCloses() {
      var downloader = new GatedImageDownloader(createTestImage(600, 900));
      downloader.holdPathsStartingWith("/poster");
      var service = artworkServiceWith(downloader);
      var run = service.openRun("scan", ImageRefreshMode.PRESERVE);
      var request = service.fetchRequired(run, movieArtwork(UUID.randomUUID(), POSTER));
      await().atMost(Duration.ofSeconds(5)).until(() -> downloader.heldDownloads() == 1);

      clock.advance(Duration.ofSeconds(4));
      run.close();
      clock.advance(Duration.ofSeconds(8));
      downloader.releaseHeldDownloads();
      awaitResult(request);
      clock.advance(Duration.ofSeconds(30));

      assertThat(awaitResult(run.completion()).elapsed()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    @DisplayName("Should reject required artwork when run is closed")
    void shouldRejectRequiredArtworkWhenRunIsClosed() {
      var run = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);
      run.close();
      var artwork = movieArtwork(UUID.randomUUID(), POSTER);

      assertThatThrownBy(() -> artworkService.fetchRequired(run, artwork))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("Secondary artwork")
  class SecondaryArtwork {

    static Stream<Arguments> secondaryImageTypes() {
      return Stream.of(
          Arguments.of(ImageEntityType.PERSON, ImageType.PROFILE),
          Arguments.of(ImageEntityType.COMPANY, ImageType.LOGO));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("secondaryImageTypes")
    @DisplayName("Should save secondary artwork when provider supplies an image")
    void shouldSaveSecondaryArtworkWhenProviderSuppliesImage(
        ImageEntityType entityType, ImageType imageType) {
      var entityId = UUID.randomUUID();
      var artwork =
          ArtworkSources.builder()
              .entityId(entityId)
              .entityType(entityType)
              .sources(List.of(new TmdbImageSource(imageType, "/secondary.jpg")))
              .build();

      var results = awaitResult(artworkService.fetchSecondary(artwork, ImageRefreshMode.PRESERVE));

      assertThat(results).containsExactly(new Saved(imageType));
      assertThat(imageRepository.findByEntityIdAndEntityType(entityId, entityType))
          .extracting(Image::getImageType)
          .containsOnly(imageType);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("secondaryImageTypes")
    @DisplayName("Should report unavailable when provider has no secondary image")
    void shouldReportUnavailableWhenProviderHasNoSecondaryImage(
        ImageEntityType entityType, ImageType imageType) {
      var artwork =
          ArtworkSources.builder()
              .entityId(UUID.randomUUID())
              .entityType(entityType)
              .sources(List.of())
              .build();

      var results = awaitResult(artworkService.fetchSecondary(artwork, ImageRefreshMode.PRESERVE));

      assertThat(results).containsExactly(new Unavailable(imageType));
    }

    @Test
    @DisplayName("Should finish required artwork while secondary downloads are held open")
    void shouldFinishRequiredArtworkWhileSecondaryDownloadsAreHeldOpen() {
      var downloader = new GatedImageDownloader(createTestImage(600, 900));
      downloader.holdPathsStartingWith("/profile");
      var service =
          ArtworkServiceFixture.artworkServiceBuilder()
              .imageRepository(imageRepository)
              .imageDownloader(downloader)
              .secondaryConcurrency(2)
              .build();
      var secondaryRequests =
          IntStream.range(0, 20)
              .mapToObj(
                  index ->
                      service.fetchSecondary(
                          personArtwork("/profile-" + index + ".jpg"), ImageRefreshMode.PRESERVE))
              .toList();
      await().atMost(Duration.ofSeconds(5)).until(() -> downloader.heldDownloads() == 2);

      List<ArtworkResult> requiredResults;
      try (var run = service.openRun("scan", ImageRefreshMode.PRESERVE)) {
        requiredResults =
            awaitResult(
                service.fetchRequired(run, movieArtwork(UUID.randomUUID(), POSTER, BACKDROP)));
      }

      assertThat(requiredResults)
          .containsExactlyInAnyOrder(new Saved(ImageType.POSTER), new Saved(ImageType.BACKDROP));
      assertThat(downloader.heldDownloads()).isEqualTo(2);
      assertThat(secondaryRequests).noneMatch(CompletableFuture::isDone);

      downloader.releaseHeldDownloads();

      assertThat(secondaryRequests)
          .allSatisfy(
              request ->
                  assertThat(awaitResult(request)).containsExactly(new Saved(ImageType.PROFILE)));
    }

    @Test
    @DisplayName(
        "Should report failure when secondary artwork is requested after the service stops")
    void shouldReportFailureWhenSecondaryArtworkIsRequestedAfterServiceStops() {
      artworkService.shutdown();

      var results =
          awaitResult(
              artworkService.fetchSecondary(
                  personArtwork("/profile.jpg"), ImageRefreshMode.PRESERVE));

      assertThat(results)
          .singleElement()
          .isInstanceOfSatisfying(
              Failed.class, failed -> assertThat(failed.imageType()).isEqualTo(ImageType.PROFILE));
    }
  }

  @Nested
  @DisplayName("Server-wide progress")
  class ServerWideProgress {

    @Test
    @DisplayName("Should report nothing when no artwork was requested")
    void shouldReportNothingWhenNoArtworkWasRequested() {
      assertThat(progress.reportProgress()).isEmpty();
    }

    @Test
    @DisplayName("Should report pending source images when required artwork is in flight")
    void shouldReportPendingSourceImagesWhenRequiredArtworkIsInFlight() {
      var downloader = new GatedImageDownloader(createTestImage(600, 900));
      downloader.holdPathsStartingWith("/");
      var service = artworkServiceWith(downloader);
      var run = service.openRun("scan", ImageRefreshMode.PRESERVE);
      service.fetchRequired(run, movieArtwork(UUID.randomUUID(), POSTER, BACKDROP));
      await().atMost(Duration.ofSeconds(5)).until(() -> downloader.heldDownloads() == 2);
      clock.advance(Duration.ofSeconds(5));

      var reports = progress.reportProgress();

      downloader.releaseHeldDownloads();
      run.close();
      assertThat(reports)
          .containsExactly(
              ArtworkProgressReport.builder()
                  .priority(ArtworkPriority.REQUIRED)
                  .counts(ArtworkCounts.builder().build())
                  .pending(2)
                  .elapsed(Duration.ofSeconds(5))
                  .build());
    }

    @Test
    @DisplayName("Should stop the lane timer when work finishes rather than when it is reported")
    void shouldStopLaneTimerWhenWorkFinishesRatherThanWhenReported() {
      var downloader = new GatedImageDownloader(createTestImage(600, 900));
      downloader.holdPathsStartingWith("/");
      var service = artworkServiceWith(downloader);
      List<ArtworkResult> results;
      try (var run = service.openRun("scan", ImageRefreshMode.PRESERVE)) {
        var request = service.fetchRequired(run, movieArtwork(UUID.randomUUID(), POSTER));
        await().atMost(Duration.ofSeconds(5)).until(() -> downloader.heldDownloads() == 1);
        clock.advance(Duration.ofSeconds(4));
        downloader.releaseHeldDownloads();
        results = awaitResult(request);
      }
      clock.advance(Duration.ofSeconds(30));

      var reports = progress.reportProgress();

      assertThat(results).hasSize(2);
      assertThat(reports)
          .singleElement()
          .satisfies(
              report -> {
                assertThat(report.isFinished()).isTrue();
                assertThat(report.elapsed()).isEqualTo(Duration.ofSeconds(4));
                assertThat(report.counts())
                    .isEqualTo(ArtworkCounts.builder().saved(1).unavailable(1).build());
              });
      assertThat(progress.reportProgress()).isEmpty();
    }

    @Test
    @DisplayName("Should keep one busy period when work resumes before the next report")
    void shouldKeepOneBusyPeriodWhenWorkResumesBeforeNextReport() {
      fetchRequired(movieArtwork(UUID.randomUUID(), POSTER, BACKDROP));
      clock.advance(Duration.ofSeconds(2));
      fetchRequired(movieArtwork(UUID.randomUUID(), POSTER, BACKDROP));

      var reports = progress.reportProgress();

      assertThat(reports)
          .singleElement()
          .satisfies(
              report -> {
                assertThat(report.isFinished()).isTrue();
                assertThat(report.counts()).isEqualTo(ArtworkCounts.builder().saved(4).build());
                assertThat(report.elapsed()).isEqualTo(Duration.ofSeconds(2));
              });
    }

    @Test
    @DisplayName("Should count source images and report secondary work separately")
    void shouldCountSourceImagesAndReportSecondaryWorkSeparately() {
      var movieId = UUID.randomUUID();
      fetchRequired(movieArtwork(movieId, POSTER, BACKDROP));
      awaitResult(
          artworkService.fetchSecondary(personArtwork("/profile.jpg"), ImageRefreshMode.PRESERVE));

      var reports = progress.reportProgress();

      assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
          .hasSize(2 * ImageSize.values().length);
      assertThat(reports)
          .extracting(ArtworkProgressReport::priority, ArtworkProgressReport::counts)
          .containsExactlyInAnyOrder(
              tuple(ArtworkPriority.REQUIRED, ArtworkCounts.builder().saved(2).build()),
              tuple(ArtworkPriority.SECONDARY, ArtworkCounts.builder().saved(1).build()));
    }
  }

  @Nested
  @DisplayName("Owning transactions")
  class OwningTransactions {

    private final TransactionTemplate transactionTemplate =
        new TransactionTemplate(new FakeTransactionManager());

    @Test
    @DisplayName("Should register required request before owning transaction commits")
    void shouldRegisterRequiredRequestBeforeOwningTransactionCommits() {
      var run = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);

      var request =
          transactionTemplate.execute(
              _ -> {
                var pending =
                    artworkService.fetchRequired(
                        run, movieArtwork(UUID.randomUUID(), POSTER, BACKDROP));
                run.close();
                assertThat(run.completion()).isNotDone();
                return pending;
              });

      assertThat(awaitResult(request))
          .containsExactlyInAnyOrder(new Saved(ImageType.POSTER), new Saved(ImageType.BACKDROP));
      assertThat(awaitResult(run.completion()).counts().saved()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should withdraw required request when owning transaction rolls back")
    void shouldWithdrawRequiredRequestWhenOwningTransactionRollsBack() {
      var run = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);
      var movieId = UUID.randomUUID();

      var request =
          transactionTemplate.execute(
              status -> {
                status.setRollbackOnly();
                return artworkService.fetchRequired(run, movieArtwork(movieId, POSTER));
              });
      run.close();

      assertThat(awaitResult(request)).isEmpty();
      assertThat(awaitResult(run.completion()).counts()).isEqualTo(ArtworkCounts.builder().build());
      assertThat(imageDownloader.getDownloadCount()).isZero();
      assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
          .isEmpty();
    }
  }

  private List<ArtworkResult> fetchRequired(ArtworkSources artwork) {
    try (var run = artworkService.openRun("scan", ImageRefreshMode.PRESERVE)) {
      return awaitResult(artworkService.fetchRequired(run, artwork));
    }
  }

  private ArtworkService artworkServiceWith(TmdbImageDownloader downloader) {
    return ArtworkServiceFixture.artworkServiceBuilder()
        .imageRepository(imageRepository)
        .imageDownloader(downloader)
        .clock(clock)
        .progress(progress)
        .build();
  }

  private static ArtworkSources movieArtwork(UUID movieId, ImageSource... sources) {
    return ArtworkSources.builder()
        .entityId(movieId)
        .entityType(ImageEntityType.MOVIE)
        .sources(List.of(sources))
        .build();
  }

  private static ArtworkSources personArtwork(String profilePath) {
    return ArtworkSources.builder()
        .entityId(UUID.randomUUID())
        .entityType(ImageEntityType.PERSON)
        .sources(List.of(new TmdbImageSource(ImageType.PROFILE, profilePath)))
        .build();
  }

  private static <T> T awaitResult(CompletableFuture<T> future) {
    return future.orTimeout(5, TimeUnit.SECONDS).join();
  }
}
