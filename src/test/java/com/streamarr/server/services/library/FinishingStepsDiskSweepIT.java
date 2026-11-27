package com.streamarr.server.services.library;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryOperation;
import com.streamarr.server.domain.LibraryOperationConclusion;
import com.streamarr.server.domain.NewLibraryOperation;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.fakes.ThrowingFileSystemWrapper;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryOperationRepository.StoreOutcome;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.TestFixtureWork;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Finishing Steps Disk Sweep Integration Tests")
class FinishingStepsDiskSweepIT extends AbstractIntegrationTest {

  private static final DatabaseResourcesHeld NOTHING_HELD = new DatabaseResourcesHeld(false, false);

  @Autowired private LibraryOperationRepository libraryOperations;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private LibraryFolderCheck libraryFolderCheck;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private MovieService movieService;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;

  @TempDir Path libraryPath;

  private final CountDownLatch sweepParked = new CountDownLatch(1);
  private final CountDownLatch releaseSweep = new CountDownLatch(1);
  private final List<DatabaseResourcesHeld> heldAtAccessChecks = new CopyOnWriteArrayList<>();

  private Library library;
  private LibraryOperationFinishingStepsRunner runner;

  @BeforeEach
  void setup() throws IOException {
    library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder()
                .filepathUri(FilepathCodec.encode(libraryPath))
                .build());
    Files.createFile(libraryPath.resolve("Kept (2020).mkv"));
    var sweepFileSystem =
        ThrowingFileSystemWrapper.runningBeforeEachAccessCheck(
            FileSystems.getDefault(),
            _ -> {
              heldAtAccessChecks.add(DatabaseResourcesHeld.onThisThread(dataSource));
              sweepParked.countDown();
              awaitLatch(releaseSweep);
            });
    runner =
        LibraryOperationFinishingStepsRunner.builder()
            .libraryOperations(libraryOperations)
            .libraryRepository(libraryRepository)
            .libraryFolderCheck(libraryFolderCheck)
            .orphanedMediaFileCleanupService(
                new OrphanedMediaFileCleanupService(
                    mediaFileRepository, movieService, sweepFileSystem, events))
            .transactionManager(transactionManager)
            .build();
  }

  @Test
  @DisplayName(
      "Should hold no transaction or database connection when the disk sweep checks the files")
  void shouldHoldNoTransactionOrDatabaseConnectionWhenTheDiskSweepChecksTheFiles()
      throws Exception {
    var missing = saveMissingMediaFile();
    var scan = storeScan();

    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var finishing =
          executor.submit(
              () -> runner.runFinishingSteps(scan, LibraryOperationConclusion.FINISHED));
      try {
        awaitLatch(sweepParked);
        assertThat(heldAtAccessChecks)
            .as("what the sweep's thread holds of the database while it checks a file")
            .containsExactly(NOTHING_HELD);
      } finally {
        releaseSweep.countDown();
      }

      assertThat(finishing.get(10, TimeUnit.SECONDS))
          .isEqualTo(LibraryOperationConclusion.FINISHED);
    }

    assertThat(mediaFileRepository.findById(missing.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should leave the operation's row unlocked while the disk sweep checks the files")
  void shouldLeaveTheOperationsRowUnlockedWhileTheDiskSweepChecksTheFiles() throws Exception {
    saveMissingMediaFile();
    var scan = storeScan();

    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var finishing =
          executor.submit(
              () -> runner.runFinishingSteps(scan, LibraryOperationConclusion.FINISHED));
      try {
        awaitLatch(sweepParked);

        lockOperationRowWithoutWaiting(scan);
      } finally {
        releaseSweep.countDown();
      }

      finishing.get(10, TimeUnit.SECONDS);
    }
  }

  private void lockOperationRowWithoutWaiting(LibraryOperation operation) throws SQLException {
    try (var contender = dataSource.getConnection()) {
      contender.setAutoCommit(false);
      try (var statement =
          contender.prepareStatement(
              "SELECT id FROM library_operation WHERE id = ? FOR UPDATE NOWAIT")) {
        statement.setObject(1, operation.id());
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      } finally {
        contender.rollback();
      }
    }
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

  // Spring binds a transaction's connection to the thread under its data source. A sweep holding
  // either while the disk answers would idle in a transaction or keep a pooled connection.
  private record DatabaseResourcesHeld(boolean transaction, boolean connection) {

    static DatabaseResourcesHeld onThisThread(DataSource dataSource) {
      return new DatabaseResourcesHeld(
          TransactionSynchronizationManager.isActualTransactionActive(),
          TransactionSynchronizationManager.hasResource(dataSource));
    }
  }
}
