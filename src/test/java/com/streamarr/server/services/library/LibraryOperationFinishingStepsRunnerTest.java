package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.fakes.FakeLibraryOperationRepository;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryOperationRepository.StoreOutcome;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

@Tag("UnitTest")
@DisplayName("Library Operation Finishing Steps Runner Tests")
class LibraryOperationFinishingStepsRunnerTest {

  private final FakeLibraryRepository libraries = new FakeLibraryRepository();
  private final FakeLibraryOperationRepository libraryOperations =
      new FakeLibraryOperationRepository(libraries);
  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final MovieService movieService =
      new MovieService(
          new FakeMovieRepository(),
          null,
          null,
          null,
          mock(ImageService.class),
          null,
          null,
          null,
          null,
          null,
          null,
          _ -> {});
  private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LibraryOperationFinishingStepsRunner runner = runnerWith(mediaFiles);

  private Library library;

  @BeforeEach
  void setup() throws IOException {
    library = libraries.save(LibraryFixtureCreator.buildFakeLibrary());
    Files.createDirectories(FilepathCodec.decode(fileSystem, library.getFilepathUri()));
  }

  @AfterEach
  void tearDown() throws IOException {
    fileSystem.close();
  }

  @Test
  @DisplayName("Should remove the media files missing from disk when a scan finishes")
  void shouldRemoveTheMediaFilesMissingFromDiskWhenAScanFinishes() throws IOException {
    var kept = saveMediaFile("Kept (2020).mkv");
    Files.createFile(FilepathCodec.decode(fileSystem, kept.getFilepathUri()));
    saveMediaFile("Gone (2020).mkv");

    var conclusion = runner.runFinishingSteps(storeScan(), LibraryOperationConclusion.FINISHED);

    assertThat(conclusion).isEqualTo(LibraryOperationConclusion.FINISHED);
    assertThat(mediaFiles.findByLibraryId(library.getId())).containsExactly(kept);
  }

  @Test
  @DisplayName(
      "Should remove no media file and conclude the scan failed when the library folder is empty")
  void shouldRemoveNoMediaFileAndConcludeTheScanFailedWhenTheLibraryFolderIsEmpty() {
    saveMediaFile("Gone (2020).mkv");

    var conclusion = runner.runFinishingSteps(storeScan(), LibraryOperationConclusion.FINISHED);

    assertThat(conclusion).isEqualTo(LibraryOperationConclusion.FAILED);
    assertThat(mediaFiles.findByLibraryId(library.getId())).hasSize(1);
    assertThat(
            meterRegistry
                .get(LibraryFolderCheck.FAILURES_METRIC)
                .tag("reason", "empty")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should remove no media file and conclude the scan failed when the library folder check"
          + " cannot read the recorded files")
  void
      shouldRemoveNoMediaFileAndConcludeTheScanFailedWhenTheFolderCheckCannotReadTheRecordedFiles() {
    saveMediaFile("Gone (2020).mkv");
    var failingRunner =
        runnerWith(
            new FakeMediaFileRepository() {
              @Override
              public boolean existsByLibraryId(UUID libraryId) {
                throw new DataAccessResourceFailureException("simulated connection loss");
              }
            });

    var conclusion =
        failingRunner.runFinishingSteps(storeScan(), LibraryOperationConclusion.FINISHED);

    assertThat(conclusion).isEqualTo(LibraryOperationConclusion.FAILED);
    assertThat(mediaFiles.findByLibraryId(library.getId())).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should conclude the scan failed when the operation's row cannot be shared before the library"
          + " folder check")
  void shouldConcludeTheScanFailedWhenTheOperationsRowCannotBeSharedBeforeTheFolderCheck() {
    var scan = storeScan();
    var lockTimingOutRunner =
        runnerBuilder(mediaFiles)
            .libraryOperations(
                new FakeLibraryOperationRepository(libraries) {
                  @Override
                  public boolean tryLockLibraryOperation(UUID libraryOperationId) {
                    throw new PessimisticLockingFailureException("simulated lock timeout");
                  }
                })
            .build();

    var conclusion =
        lockTimingOutRunner.runFinishingSteps(scan, LibraryOperationConclusion.FINISHED);

    assertThat(conclusion).isEqualTo(LibraryOperationConclusion.FAILED);
  }

  @Test
  @DisplayName(
      "Should conclude the scan failed when the rollback after a failed library folder check fails")
  void shouldConcludeTheScanFailedWhenTheRollbackAfterAFailedFolderCheckFails() {
    saveMediaFile("Gone (2020).mkv");
    var rollbackFailingRunner =
        runnerBuilder(mediaFiles)
            .transactionManager(new RollbackFailingTransactionManager())
            .build();

    var conclusion =
        rollbackFailingRunner.runFinishingSteps(storeScan(), LibraryOperationConclusion.FINISHED);

    assertThat(conclusion).isEqualTo(LibraryOperationConclusion.FAILED);
    assertThat(mediaFiles.findByLibraryId(library.getId())).hasSize(1);
  }

  @Test
  @DisplayName("Should have the same effect when a scan's finishing steps run again")
  void shouldHaveTheSameEffectWhenAScansFinishingStepsRunAgain() throws IOException {
    var kept = saveMediaFile("Kept (2020).mkv");
    Files.createFile(FilepathCodec.decode(fileSystem, kept.getFilepathUri()));
    saveMediaFile("Gone (2020).mkv");
    var scan = storeScan();
    runner.runFinishingSteps(scan, LibraryOperationConclusion.FINISHED);

    runner.runFinishingSteps(scan, LibraryOperationConclusion.FINISHED);

    assertThat(mediaFiles.findByLibraryId(library.getId())).containsExactly(kept);
  }

  @Test
  @DisplayName("Should remove no media file when a scan fails")
  void shouldRemoveNoMediaFileWhenAScanFails() {
    saveMediaFile("Gone (2020).mkv");

    var conclusion = runner.runFinishingSteps(storeScan(), LibraryOperationConclusion.FAILED);

    assertThat(conclusion).isEqualTo(LibraryOperationConclusion.FAILED);
    assertThat(mediaFiles.findByLibraryId(library.getId())).hasSize(1);
  }

  @Test
  @DisplayName("Should remove no media file when a refresh finishes")
  void shouldRemoveNoMediaFileWhenARefreshFinishes() {
    saveMediaFile("Gone (2020).mkv");
    var refresh =
        storedOperation(
            libraryOperations.storeLibraryOperation(
                NewLibraryOperation.refresh(library.getId(), ImageRefreshMode.PRESERVE)));

    var conclusion = runner.runFinishingSteps(refresh, LibraryOperationConclusion.FINISHED);

    assertThat(conclusion).isEqualTo(LibraryOperationConclusion.FINISHED);
    assertThat(mediaFiles.findByLibraryId(library.getId())).hasSize(1);
  }

  @Test
  @DisplayName("Should remove no media file when the scan has already ended")
  void shouldRemoveNoMediaFileWhenTheScanHasAlreadyEnded() {
    saveMediaFile("Gone (2020).mkv");
    var scan = storeScan();
    libraryOperations.tryEndLibraryOperation(
        LibraryOperationEnd.builder()
            .libraryOperationId(scan.id())
            .conclusion(LibraryOperationConclusion.FINISHED)
            .endedAt(Instant.now())
            .build());

    runner.runFinishingSteps(scan, LibraryOperationConclusion.FINISHED);

    assertThat(mediaFiles.findByLibraryId(library.getId())).hasSize(1);
  }

  @Test
  @DisplayName("Should keep the scan finished when the removal of missing media files fails")
  void shouldKeepTheScanFinishedWhenTheRemovalOfMissingMediaFilesFails() throws IOException {
    var kept = saveMediaFile("Kept (2020).mkv");
    Files.createFile(FilepathCodec.decode(fileSystem, kept.getFilepathUri()));
    saveMediaFile("Gone (2020).mkv");
    var failingRunner =
        runnerWith(
            new FakeMediaFileRepository() {
              @Override
              public List<MediaFile> findByLibraryId(UUID libraryId) {
                throw new DataAccessResourceFailureException("simulated connection loss");
              }
            });
    var scan = storeScan();

    var conclusion = failingRunner.runFinishingSteps(scan, LibraryOperationConclusion.FINISHED);

    assertThat(conclusion).isEqualTo(LibraryOperationConclusion.FINISHED);
    assertThat(mediaFiles.findByLibraryId(library.getId())).hasSize(2);
  }

  private LibraryOperationFinishingStepsRunner runnerWith(MediaFileRepository mediaFileRepository) {
    return runnerBuilder(mediaFileRepository).build();
  }

  private LibraryOperationFinishingStepsRunner.LibraryOperationFinishingStepsRunnerBuilder
      runnerBuilder(MediaFileRepository mediaFileRepository) {
    return LibraryOperationFinishingStepsRunner.builder()
        .libraryOperations(libraryOperations)
        .libraryRepository(libraries)
        .libraryFolderCheck(new LibraryFolderCheck(fileSystem, mediaFileRepository, meterRegistry))
        .orphanedMediaFileCleanupService(
            new OrphanedMediaFileCleanupService(
                mediaFileRepository, movieService, fileSystem, _ -> {}))
        .transactionManager(new FakeTransactionManager());
  }

  private static final class RollbackFailingTransactionManager
      implements PlatformTransactionManager {

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      // This transaction manager has no resource to commit.
    }

    @Override
    public void rollback(TransactionStatus status) {
      throw new TransactionSystemException("simulated rollback failure");
    }
  }

  private LibraryOperation storeScan() {
    return storedOperation(
        libraryOperations.storeLibraryOperation(NewLibraryOperation.scan(library.getId())));
  }

  private static LibraryOperation storedOperation(StoreOutcome outcome) {
    assertThat(outcome).isInstanceOf(StoreOutcome.Stored.class);
    return ((StoreOutcome.Stored) outcome).libraryOperation();
  }

  private MediaFile saveMediaFile(String filename) {
    var path = FilepathCodec.decode(fileSystem, library.getFilepathUri()).resolve(filename);
    return mediaFiles.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .filepathUri(FilepathCodec.encode(path))
            .filename(filename)
            .status(MediaFileStatus.MATCHED)
            .build());
  }
}
