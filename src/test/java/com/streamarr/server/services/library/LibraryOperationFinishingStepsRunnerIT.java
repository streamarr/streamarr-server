package com.streamarr.server.services.library;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryOperationRepository.StoreOutcome;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.TestFixtureWork;
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
  void shouldRemoveTheMediaFilesMissingFromDiskWhenAStoredScanFinishes() {
    var missing = saveMissingMediaFile();

    runner.runFinishingSteps(storeScan(), LibraryOperationConclusion.FINISHED);

    assertThat(mediaFileRepository.findById(missing.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should remove no media file when another execution ended the scan first")
  void shouldRemoveNoMediaFileWhenAnotherExecutionEndedTheScanFirst() {
    var missing = saveMissingMediaFile();
    var scan = storeScan();
    Boolean ended =
        transactionTemplate.execute(_ -> libraryOperations.tryEndLibraryOperation(endOf(scan)));
    assertThat(ended).isTrue();

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
                    Boolean.TRUE.equals(
                        transactionTemplate.execute(
                            _ -> libraryOperations.tryEndLibraryOperation(endOf(scan)))));
        awaitWaitersBehind(jdbcTemplate, stepPid.get(), 1);
        release.countDown();
        step.get(10, TimeUnit.SECONDS);

        assertThat(end.get(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
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

  private static LibraryOperationEnd endOf(LibraryOperation operation) {
    return LibraryOperationEnd.builder()
        .libraryOperationId(operation.id())
        .conclusion(LibraryOperationConclusion.FINISHED)
        .endedAt(Instant.now())
        .build();
  }
}
