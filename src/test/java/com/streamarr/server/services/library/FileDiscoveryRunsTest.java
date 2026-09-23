package com.streamarr.server.services.library;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.domain.task.RequestedProbeResult;
import com.streamarr.server.exceptions.ArtworkResultNotRecordedException;
import com.streamarr.server.exceptions.LibraryScanFailedException;
import com.streamarr.server.fakes.FakeItemResultRepository;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeProbeTaskRequests;
import com.streamarr.server.fakes.GatedImageDownloader;
import com.streamarr.server.fixtures.ArtworkServiceFixture;
import com.streamarr.server.fixtures.FileDiscoveryRunsFixture;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.ArtworkCounts;
import com.streamarr.server.services.ArtworkService;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
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

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeItemResultRepository itemResults = new FakeItemResultRepository();
  private final GatedImageDownloader imageDownloader =
      new GatedImageDownloader(createTestImage(600, 900));
  private final ArtworkService artworkService =
      ArtworkServiceFixture.artworkServiceBuilder()
          .imageDownloader(imageDownloader)
          .itemResults(itemResults)
          .build();
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
  @DisplayName("Should report required results while secondary artwork continues")
  void shouldReportRequiredResultsWhileSecondaryArtworkContinues() throws IOException {
    imageDownloader.holdPathsStartingWith("/profile");
    artworkService.fetchSecondary(personArtwork(), ImageRefreshMode.PRESERVE);
    await().atMost(Duration.ofSeconds(5)).until(() -> imageDownloader.heldDownloads() == 1);
    var runs = fileDiscoveryRuns();
    var discovery = runs.open("scan of", library);
    try (discovery) {
      discovery.probeRun().request(mediaFile().getId());
      artworkService.fetchRequired(discovery.artworkRun(), movieArtwork());
    }

    var results = runs.awaitResults(discovery);

    assertThat(results.files()).isEqualTo(Map.of(MediaFileStatus.MATCHED, 1L));
    assertThat(results.artwork().counts())
        .isEqualTo(ArtworkCounts.builder().saved(1).unavailable(1).build());
    assertThat(results.probes().count(RequestedProbeResult.READY)).isOne();
    assertThat(results.secondaryImagesPending()).isOne();
  }

  @Test
  @DisplayName("Should fail the scan when a required artwork result cannot be recorded")
  void shouldFailTheScanWhenARequiredArtworkResultCannotBeRecorded() {
    itemResults.failWritesWith(new DataAccessResourceFailureException("database unavailable"));
    var runs = fileDiscoveryRuns();
    var discovery = runs.open("scan of", library);
    try (discovery) {
      artworkService.fetchRequired(discovery.artworkRun(), movieArtwork());
    }

    assertThatThrownBy(() -> runs.awaitResults(discovery))
        .isInstanceOf(LibraryScanFailedException.class)
        .hasCauseInstanceOf(ArtworkResultNotRecordedException.class);
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
  @DisplayName("Should fail the scan when reading probe results throws unexpectedly")
  void shouldFailTheScanWhenReadingProbeResultsThrowsUnexpectedly() throws IOException {
    var unexpected = new IllegalStateException("unexpected probe state");
    outcomes =
        new FakeMediaFileContainerInfoRepository() {
          @Override
          public List<ProbeState> findProbeStates(Collection<UUID> mediaFileIds) {
            throw unexpected;
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
        .hasCause(unexpected);
  }

  @Test
  @DisplayName("Should fail the scan when artwork cannot be recorded while a probe is pending")
  void shouldFailTheScanWhenArtworkCannotBeRecordedWhileAProbeIsPending() throws Exception {
    probeTaskRequests.dispatchWith(_ -> {});
    itemResults.failWritesWith(new DataAccessResourceFailureException("database unavailable"));
    var runs = fileDiscoveryRuns();
    var discovery = runs.open("scan of", library);
    try (discovery) {
      discovery.probeRun().request(mediaFile().getId());
      artworkService.fetchRequired(discovery.artworkRun(), movieArtwork());
    }

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertThat(executor.submit(() -> runs.awaitResults(discovery)))
          .failsWithin(Duration.ofSeconds(5))
          .withThrowableOfType(ExecutionException.class)
          .withCauseInstanceOf(LibraryScanFailedException.class);
    }
  }

  @Test
  @DisplayName("Should time the probes without the wait for required artwork")
  void shouldTimeTheProbesWithoutTheWaitForRequiredArtwork() throws Exception {
    imageDownloader.holdPathsStartingWith("/poster");
    var runs = fileDiscoveryRuns();
    var discovery = runs.open("scan of", library);
    try (discovery) {
      discovery.probeRun().request(mediaFile().getId());
      artworkService.fetchRequired(discovery.artworkRun(), movieArtwork());
    }

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results = executor.submit(() -> runs.awaitResults(discovery));
      await().atMost(Duration.ofSeconds(5)).until(() -> imageDownloader.heldDownloads() == 1);
      await().pollDelay(Duration.ofMillis(300)).until(() -> true);
      imageDownloader.releaseHeldDownloads();

      var finished = results.get(5, TimeUnit.SECONDS);
      assertThat(finished.probes().elapsed()).isLessThan(finished.artwork().elapsed());
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
    return FileDiscoveryRunsFixture.fileDiscoveryRunsBuilder()
        .artworkService(artworkService)
        .mediaFiles(mediaFiles)
        .outcomes(outcomes)
        .probeTaskRequests(probeTaskRequests)
        .fileSystem(fileSystem)
        .build();
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
        .entityId(UUID.randomUUID())
        .entityType(ImageEntityType.MOVIE)
        .sources(List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")))
        .build();
  }

  private static ArtworkSources personArtwork() {
    return ArtworkSources.builder()
        .entityId(UUID.randomUUID())
        .entityType(ImageEntityType.PERSON)
        .sources(List.of(new TmdbImageSource(ImageType.PROFILE, "/profile.jpg")))
        .build();
  }
}
