package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.SkippedFile.SKIPPED_FILE;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.SkippedFile;
import com.streamarr.server.domain.SkippedFileReason;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.jooq.generated.tables.records.SkippedFileRecord;
import com.streamarr.server.services.library.LibraryOperationConclusion;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.server.support.PostgresLockTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.StoredLibraryOperations;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("IntegrationTest")
@DisplayName("jOOQ skipped file repository integration tests")
class JooqSkippedFileRepositoryIT extends AbstractIntegrationTest {

  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final String LINK = "file:///library/Linked%20(2002).mkv";

  @Autowired private SkippedFileRepository skippedFiles;
  @Autowired private LibraryOperationRepository libraryOperationRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;

  private StoredLibraryOperations libraryOperations;
  private Library library;

  @BeforeEach
  void saveALibrary() {
    libraryOperations = new StoredLibraryOperations(libraryOperationRepository, transactionManager);
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
  }

  @AfterEach
  void removeTheLibrary() {
    libraryRepository.deleteById(library.getId());
  }

  @Test
  @DisplayName("Should save nothing when the skipped file's library operation has ended")
  void shouldSaveNothingWhenTheSkippedFilesLibraryOperationHasEnded() {
    var endedScan = libraryOperations.storeScan(library.getId());
    libraryOperations.endAsSystemActor(endedScan);
    libraryOperations.storeScan(library.getId());

    var saved = skippedFiles.trySave(softLink(endedScan, LINK));

    assertThat(saved).isFalse();
    assertThat(storedSkippedFiles()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should delete no skipped file of the newer scan when the deleting library operation has"
          + " ended")
  void shouldDeleteNoSkippedFileOfTheNewerScanWhenTheDeletingLibraryOperationHasEnded() {
    var endedScan = libraryOperations.storeScan(library.getId());
    libraryOperations.endAsSystemActor(endedScan);
    var newerScan = libraryOperations.storeScan(library.getId());
    assertThat(skippedFiles.trySave(softLink(newerScan, LINK))).isTrue();

    skippedFiles.deleteSkippedFilesOfOtherLibraryOperations(library.getId(), endedScan);

    assertThat(storedSkippedFiles())
        .extracting(SkippedFileRecord::getFilepathUri, SkippedFileRecord::getLibraryOperationId)
        .containsExactly(tuple(LINK, newerScan));
  }

  @Test
  @DisplayName(
      "Should make the library operation's end wait until a skipped file save of the operation"
          + " commits")
  void shouldMakeTheLibraryOperationsEndWaitUntilASkippedFileSaveOfTheOperationCommits()
      throws Exception {
    var scan = libraryOperations.storeScan(library.getId());
    assertThat(skippedFiles.trySave(softLink(scan, LINK))).isTrue();

    // The held row parks the save after it has read its library operation.
    try (var observer = dataSource.getConnection();
        var heldSkippedFile = lockSkippedFileRow(storedSkippedFiles().getFirst().getId());
        var save = BoundedTask.start(() -> skippedFiles.trySave(softLink(scan, LINK)))) {
      var savePid = awaitBlockedBackendPid(observer, heldSkippedFile.backendPid(), null);

      try (var end = BoundedTask.start(() -> libraryOperations.endAsSystemActor(scan))) {
        awaitBlockedBackendPid(observer, savePid, null);
        heldSkippedFile.release();

        assertThat(save.await(BOUND)).isTrue();
        assertThat(end.await(BOUND)).contains(LibraryOperationConclusion.FINISHED);
      }
    }
  }

  private PostgresLockTestSupport.HeldRowLock lockSkippedFileRow(UUID skippedFileId)
      throws Exception {
    return PostgresLockTestSupport.lockRow(
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("skipped_file")
            .rowId(skippedFileId)
            .build());
  }

  private List<SkippedFileRecord> storedSkippedFiles() {
    return dsl.selectFrom(SKIPPED_FILE).where(SKIPPED_FILE.LIBRARY_ID.eq(library.getId())).fetch();
  }

  private SkippedFile softLink(UUID libraryOperationId, String filepathUri) {
    return SkippedFile.builder()
        .libraryId(library.getId())
        .libraryOperationId(libraryOperationId)
        .filepathUri(filepathUri)
        .reason(SkippedFileReason.SOFT_LINK)
        .build();
  }
}
