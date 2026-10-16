package com.streamarr.server.services.library;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryMetadataRepository;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryOperationRepository.StoreOutcome;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.support.TestFixtureWork;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Library operation finishing steps integration tests")
class LibraryOperationFinishingStepsRunnerIT extends AbstractIntegrationTest {

  @Autowired private LibraryOperationFinishingStepsRunner runner;
  @Autowired private LibraryOperationRepository libraryOperations;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryMetadataRepository libraryMetadataRepository;
  @Autowired private LibraryOperationExecution libraryOperationExecution;

  @TempDir Path libraryPath;

  private Library library;

  @BeforeEach
  void setup() {
    library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder()
                .filepathUri(FilepathCodec.encode(libraryPath))
                .build());
  }

  @Test
  @DisplayName("Should remove the media files missing from disk when a stored scan finishes")
  void shouldRemoveTheMediaFilesMissingFromDiskWhenAStoredScanFinishes() throws IOException {
    Files.createFile(libraryPath.resolve("Kept (2020).mkv"));
    var missing = saveMissingMediaFile();

    var conclusion = runner.runFinishingSteps(storeScan(), LibraryOperationConclusion.FINISHED);

    assertThat(conclusion).isEqualTo(LibraryOperationConclusion.FINISHED);
    assertThat(mediaFileRepository.findById(missing.getId())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should remove no media file and conclude the scan failed when the library folder is empty")
  void shouldRemoveNoMediaFileAndConcludeTheScanFailedWhenTheLibraryFolderIsEmpty() {
    var missing = saveMissingMediaFile();

    var conclusion = runner.runFinishingSteps(storeScan(), LibraryOperationConclusion.FINISHED);

    assertThat(conclusion).isEqualTo(LibraryOperationConclusion.FAILED);
    assertThat(mediaFileRepository.findById(missing.getId())).isPresent();
  }

  @Test
  @DisplayName("Should remove no media file when another execution ended the scan first")
  void shouldRemoveNoMediaFileWhenAnotherExecutionEndedTheScanFirst() {
    var missing = saveMissingMediaFile();
    var scan = storeScan();
    var ended =
        transactionTemplate.execute(_ -> libraryOperations.tryEndLibraryOperation(endOf(scan)));
    assertThat(ended).contains(LibraryOperationConclusion.FINISHED);

    runner.runFinishingSteps(scan, LibraryOperationConclusion.FINISHED);

    assertThat(mediaFileRepository.findById(missing.getId())).isPresent();
  }

  @Test
  @DisplayName("Should make an end wait until a finishing step of the operation commits")
  void shouldMakeAnEndWaitUntilAFinishingStepOfTheOperationCommits() throws Exception {
    var scan = storeScan();
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stepPid = new AtomicInteger();
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var step =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        assertThat(libraryOperations.tryLockLibraryOperation(scan.id())).isTrue();
                        stepPid.set(
                            jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        held.countDown();
                        awaitLatch(release);
                      }));
      try {
        awaitLatch(held);
        var end =
            executor.submit(
                () ->
                    transactionTemplate.execute(
                        _ -> libraryOperations.tryEndLibraryOperation(endOf(scan))));
        awaitWaitersBehind(jdbcTemplate, stepPid.get(), 1);
        release.countDown();
        step.get(10, TimeUnit.SECONDS);

        assertThat(end.get(10, TimeUnit.SECONDS)).contains(LibraryOperationConclusion.FINISHED);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @DisplayName(
      "Should store a stop without waiting while a finishing step of the operation is uncommitted")
  void shouldStoreAStopWithoutWaitingWhileAFinishingStepOfTheOperationIsUncommitted()
      throws Exception {
    var scan = storeScan();
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var step =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        assertThat(libraryOperations.tryLockLibraryOperation(scan.id())).isTrue();
                        held.countDown();
                        awaitLatch(release);
                      }));
      try {
        awaitLatch(held);

        var stop =
            executor.submit(
                () ->
                    transactionTemplate.execute(
                        _ ->
                            libraryOperations.stopLibraryOperation(
                                new LibraryOperationStop(
                                    library.getId(), LibraryOperationKind.SCAN))));

        assertThat(stop.get(10, TimeUnit.SECONDS))
            .isInstanceOf(LibraryOperationRepository.StopOutcome.Stored.class);
        assertThat(libraryOperations.hasStoredStop(scan.id())).isTrue();
      } finally {
        release.countDown();
      }

      step.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName(
      "Should leave a title out of the letter index when a finishing step of another execution"
          + " deletes the title")
  void shouldLeaveATitleOutOfTheLetterIndexWhenAFinishingStepOfAnotherExecutionDeletesTheTitle()
      throws Exception {
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    var refresh = storeRefresh();
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stepPid = new AtomicInteger();
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var step =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        assertThat(libraryOperations.tryLockLibraryOperation(refresh.id()))
                            .isTrue();
                        movieRepository.deleteById(movie.getId());
                        movieRepository.flush();
                        stepPid.set(
                            jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        held.countDown();
                        awaitLatch(release);
                      }));
      try {
        awaitLatch(held);
        var execution =
            executor.submit(() -> libraryOperationExecution.execute(refresh.id(), () -> false));
        awaitWaitersBehind(jdbcTemplate, stepPid.get(), 1);
        release.countDown();
        step.get(10, TimeUnit.SECONDS);

        assertThat(execution.get(10, TimeUnit.SECONDS))
            .isInstanceOf(LibraryOperationExecutionResult.Ended.class);
      } finally {
        release.countDown();
      }
    }

    assertThat(libraryMetadataRepository.findByLibraryIdOrderByLetterAsc(library.getId()))
        .isEmpty();
  }

  private LibraryOperation storeRefresh() {
    var outcome =
        transactionTemplate.execute(
            _ ->
                libraryOperations.storeLibraryOperation(
                    NewLibraryOperation.refresh(library.getId(), ImageRefreshMode.PRESERVE)));
    assertThat(outcome).isInstanceOf(StoreOutcome.Stored.class);
    return ((StoreOutcome.Stored) outcome).libraryOperation();
  }

  private LibraryOperation storeScan() {
    var outcome =
        transactionTemplate.execute(
            _ ->
                libraryOperations.storeLibraryOperation(NewLibraryOperation.scan(library.getId())));
    assertThat(outcome).isInstanceOf(StoreOutcome.Stored.class);
    return ((StoreOutcome.Stored) outcome).libraryOperation();
  }

  private MediaFile saveMissingMediaFile() {
    var path = libraryPath.resolve("Gone (2020).mkv");
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .libraryId(library.getId())
            .filepathUri(FilepathCodec.encode(path))
            .filename(path.getFileName().toString())
            .status(MediaFileStatus.MATCHED)
            .build());
  }

  private static LibraryOperationEnd endOf(LibraryOperation operation) {
    return LibraryOperationEnd.builder()
        .libraryOperationId(operation.id())
        .conclusion(LibraryOperationConclusion.FINISHED)
        .endedAt(Instant.now())
        .build();
  }
}
