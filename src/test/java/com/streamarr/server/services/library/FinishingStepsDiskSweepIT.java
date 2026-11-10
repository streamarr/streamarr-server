package com.streamarr.server.services.library;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Finishing Steps Disk Sweep Integration Tests")
@SpringBootTest(properties = "DATABASE_IDLE_IN_TRANSACTION_TIMEOUT=2s")
class FinishingStepsDiskSweepIT extends AbstractIntegrationTest {

  @Autowired private LibraryOperationRepository libraryOperations;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private LibraryFolderCheck libraryFolderCheck;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private MovieService movieService;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  @TempDir Path libraryPath;

  private final CountDownLatch sweepParked = new CountDownLatch(1);
  private final CountDownLatch releaseSweep = new CountDownLatch(1);

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
      "Should remove the missing media files when the disk sweep outlasts the idle transaction"
          + " bound")
  void shouldRemoveTheMissingMediaFilesWhenTheDiskSweepOutlastsTheIdleTransactionBound()
      throws Exception {
    var missing = saveMissingMediaFile();
    var scan = storeScan();

    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var finishing =
          executor.submit(
              () -> runner.runFinishingSteps(scan, LibraryOperationConclusion.FINISHED));
      try {
        awaitLatch(sweepParked);
        awaitTheIdleTransactionBound();
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

  // PostgreSQL's own timer is the clock: a transaction opened now and left idle is ended once the
  // idle transaction bound has passed.
  private void awaitTheIdleTransactionBound() throws SQLException {
    try (var idle = dataSource.getConnection()) {
      idle.setAutoCommit(false);
      var idlePid = backendPid(idle);

      await().atMost(Duration.ofSeconds(10)).until(() -> !isBackendConnected(idlePid));

      assertThatThrownBy(() -> backendPid(idle)).isInstanceOf(SQLException.class);
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

  private boolean isBackendConnected(int pid) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ?)", Boolean.class, pid));
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
}
