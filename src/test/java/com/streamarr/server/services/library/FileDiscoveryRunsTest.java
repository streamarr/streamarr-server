package com.streamarr.server.services.library;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.domain.task.RequestedProbeResult;
import com.streamarr.server.exceptions.LibraryScanFailedException;
import com.streamarr.server.fakes.CountingSleeper;
import com.streamarr.server.fakes.FakeItemResultRepository;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeProbeTaskRequests;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.fakes.GatedImageDownloader;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.ArtworkFixture;
import com.streamarr.server.fixtures.FileDiscoveryRunsFixture;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.ArtworkCounts;
import com.streamarr.server.services.ArtworkService;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.support.BoundedTask;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@DisplayName("File discovery runs")
class FileDiscoveryRunsTest {

  private static final Duration RESULTS_BOUND = Duration.ofSeconds(5);

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeItemResultRepository itemResults = new FakeItemResultRepository();
  private final GatedImageDownloader imageDownloader =
      new GatedImageDownloader(createTestImage(600, 900));
  private final ArtworkFixture artwork =
      ArtworkFixture.builder().imageDownloader(imageDownloader).itemResults(itemResults).build();
  private final ArtworkService artworkService = artwork.artworkService();
  private final Library library = LibraryFixtureCreator.buildFakeLibrary();
  private FakeMediaFileContainerInfoRepository outcomes =
      new FakeMediaFileContainerInfoRepository();
  private FakeProbeTaskRequests probeTaskRequests;
  private FileSystem fileSystem;

  @BeforeEach
  void setUp() {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    probeTaskRequests = new FakeProbeTaskRequests(outcomes);
    probeTaskRequests.succeedEachRequest();
  }

  @AfterEach
  void tearDown() throws IOException {
    imageDownloader.releaseHeldDownloads();
    fileSystem.close();
  }

  @Test
  @DisplayName("Should report required results when background artwork is still pending")
  void shouldReportRequiredResultsWhenBackgroundArtworkIsStillPending() throws Exception {
    artwork.scheduling().holdBackgroundSteps();
    artworkService.requestBackgroundArtwork(personArtwork(), ImageRefreshMode.PRESERVE);
    var runs = fileDiscoveryRuns();
    var discovery = runs.open("scan of", library);
    try (discovery) {
      discovery.probeRun().request(mediaFile().getId());
      artworkService.requestPrimaryArtwork(discovery.artworkRun(), movieArtwork());
    }

    var results = runs.awaitResults(discovery);

    assertThat(results.files()).isEqualTo(Map.of(MediaFileStatus.MATCHED, 1L));
    assertThat(results.artwork().counts())
        .isEqualTo(ArtworkCounts.builder().saved(1).unavailable(1).build());
    assertThat(results.probes().count(RequestedProbeResult.READY)).isOne();
    assertThat(results.backgroundImagesPending()).isOne();
  }

  @Test
  @DisplayName(
      "Should finish the scan with one saved and one failed image when a later attempt stores the"
          + " poster failure that the first attempt could not store")
  void shouldFinishTheScanWithOneSavedAndOneFailedImageWhenALaterAttemptStoresThePosterFailure()
      throws Exception {
    var downloader = new FakeTmdbHttpService();
    downloader.setImageData(createTestImage(600, 900));
    downloader.failDownloadWith("/poster.jpg", new IOException("connection reset"));
    var results = new FakeItemResultRepository();
    results.failArtworkResultWritesWith(
        new DataAccessResourceFailureException("database unavailable"), 1);
    var retried = ArtworkFixture.builder().imageDownloader(downloader).itemResults(results).build();
    var runs = fileDiscoveryRunsBuilder().artwork(retried).build();
    var movie = movieArtworkWithBackdrop();
    var discovery = runs.open("scan of", library);
    try (discovery) {
      retried.artworkService().requestPrimaryArtwork(discovery.artworkRun(), movie);
    }

    try (var scan = BoundedTask.start(() -> runs.awaitResults(discovery))) {
      retried.scheduling().awaitUnfinishedAttempt(RESULTS_BOUND);
      assertThat(results.findByItem(movie.targetId(), ImageEntityType.MOVIE))
          .extracting(ItemResult::imageType)
          .containsExactly(ImageType.BACKDROP);

      retried.scheduling().runPendingSteps();

      assertThat(scan.await(RESULTS_BOUND).artwork().counts())
          .isEqualTo(ArtworkCounts.builder().saved(1).failed(1).build());
    }
  }

  @Test
  @DisplayName("Should fail the scan when probe results cannot be read")
  void shouldFailTheScanWhenProbeResultsCannotBeRead() throws IOException {
    var readFailure = new DataAccessResourceFailureException("database unavailable");
    outcomes =
        new FakeMediaFileContainerInfoRepository() {
          @Override
          public List<ProbeState> findProbeStates(Collection<UUID> mediaFileIds) {
            throw readFailure;
          }
        };
    probeTaskRequests = new FakeProbeTaskRequests(outcomes);
    var runs = fileDiscoveryRuns();
    var discovery = runs.open("scan of", library);
    try (discovery) {
      discovery.probeRun().request(mediaFile().getId());
    }

    assertThatThrownBy(() -> runs.awaitResults(discovery))
        .isInstanceOf(LibraryScanFailedException.class)
        .hasCause(readFailure);
  }

  @Test
  @DisplayName("Should stop the probe timer when probes finish before primary artwork")
  void shouldStopTheProbeTimerWhenProbesFinishBeforePrimaryArtwork() throws Exception {
    var clock = new MutableClock();
    var timedArtwork =
        ArtworkFixture.builder()
            .imageDownloader(imageDownloader)
            .itemResults(itemResults)
            .clock(clock)
            .build();
    timedArtwork.scheduling().holdScheduledSteps();
    var probeWaitThread = new CompletableFuture<Thread>();
    var sleeper = new CountingSleeper();
    probeTaskRequests.dispatchWith(_ -> {});
    sleeper.onSleep(
        _ -> {
          probeTaskRequests.succeed(probeTaskRequests.requests().getFirst());
          probeWaitThread.complete(Thread.currentThread());
        });
    var runs =
        fileDiscoveryRunsBuilder().artwork(timedArtwork).clock(clock).sleeper(sleeper).build();
    var discovery = runs.open("scan of", library);
    try (discovery) {
      discovery.probeRun().request(mediaFile().getId());
      timedArtwork.artworkService().requestPrimaryArtwork(discovery.artworkRun(), movieArtwork());
    }

    try (var results = BoundedTask.start(() -> runs.awaitResults(discovery))) {
      assertThat(probeWaitThread.get(5, TimeUnit.SECONDS).join(RESULTS_BOUND))
          .as("probe wait finished")
          .isTrue();
      clock.advance(Duration.ofSeconds(60));
      timedArtwork.scheduling().runPendingSteps();

      var finished = results.await(RESULTS_BOUND);
      assertThat(finished.probes().elapsed()).isZero();
      assertThat(finished.artwork().elapsed()).isEqualTo(Duration.ofSeconds(60));
    }
  }

  @Test
  @DisplayName("Should fail the scan and keep the interrupt when waiting is interrupted")
  void shouldFailTheScanAndKeepTheInterruptWhenWaitingIsInterrupted() throws IOException {
    probeTaskRequests.dispatchWith(_ -> {});
    var runs = fileDiscoveryRuns();
    var discovery = runs.open("scan of", library);
    try (discovery) {
      discovery.probeRun().request(mediaFile().getId());
    }

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> runs.awaitResults(discovery))
          .isInstanceOf(LibraryScanFailedException.class)
          .hasCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private FileDiscoveryRuns fileDiscoveryRuns() {
    return fileDiscoveryRunsBuilder().build();
  }

  private FileDiscoveryRunsFixture.FileDiscoveryRunsBuilder fileDiscoveryRunsBuilder() {
    return FileDiscoveryRunsFixture.fileDiscoveryRunsBuilder()
        .artwork(artwork)
        .mediaFiles(mediaFiles)
        .outcomes(outcomes)
        .probeTaskRequests(probeTaskRequests)
        .fileSystem(fileSystem);
  }

  private MediaFile mediaFile() throws IOException {
    var path = Files.writeString(fileSystem.getPath("/movie.mkv"), "media");
    return mediaFiles.save(
        MediaFile.builder()
            .libraryId(UUID.randomUUID())
            .filename("movie.mkv")
            .filepathUri(FilepathCodec.encode(path))
            .status(MediaFileStatus.MATCHED)
            .build());
  }

  private static ArtworkSources movieArtwork() {
    return ArtworkSources.builder()
        .targetId(UUID.randomUUID())
        .targetType(ImageEntityType.MOVIE)
        .sources(List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")))
        .build();
  }

  private static ArtworkSources movieArtworkWithBackdrop() {
    return ArtworkSources.builder()
        .targetId(UUID.randomUUID())
        .targetType(ImageEntityType.MOVIE)
        .sources(
            List.of(
                new TmdbImageSource(ImageType.POSTER, "/poster.jpg"),
                new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg")))
        .build();
  }

  private static ArtworkSources personArtwork() {
    return ArtworkSources.builder()
        .targetId(UUID.randomUUID())
        .targetType(ImageEntityType.PERSON)
        .sources(List.of(new TmdbImageSource(ImageType.PROFILE, "/profile.jpg")))
        .build();
  }
}
