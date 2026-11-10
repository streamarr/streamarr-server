package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.SkippedFile.SKIPPED_FILE;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryOperationConclusion;
import com.streamarr.server.domain.SkippedFile;
import com.streamarr.server.domain.SkippedFileReason;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.jooq.generated.tables.records.SkippedFileRecord;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValueKind;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.server.support.PostgresLockTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.StoredLibraryOperations;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

  @ParameterizedTest
  @EnumSource(SkippedFileReason.class)
  @DisplayName("Should read each skipped file reason back when a scan saves it")
  void shouldReadEachSkippedFileReasonBackWhenAScanSavesIt(SkippedFileReason reason) {
    var scan = libraryOperations.storeScan(library.getId());
    var skippedFile =
        SkippedFile.builder()
            .libraryId(library.getId())
            .libraryOperationId(scan)
            .filepathUri(LINK)
            .reason(reason)
            .build();

    var saved = skippedFiles.trySave(skippedFile);

    assertThat(saved).isTrue();
    assertThat(skippedFiles.rowsBeyond(library.getId(), firstRows()))
        .extracting(Row::item)
        .containsExactly(skippedFile);
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

  @Test
  @DisplayName(
      "Should save nothing and let the end commit when a new skipped file waits behind the end's"
          + " library lock")
  void shouldSaveNothingAndLetTheEndCommitWhenANewSkippedFileWaitsBehindTheEndsLibraryLock()
      throws Exception {
    var scan = libraryOperations.storeScan(library.getId());

    // The end locks the library's row and then deletes the operation's row, in that order.
    try (var observer = dataSource.getConnection();
        var end = dataSource.getConnection()) {
      end.setAutoCommit(false);
      executeUpdate(end, "SELECT id FROM library WHERE id = ? FOR UPDATE", library.getId());

      try (var save = BoundedTask.start(() -> skippedFiles.trySave(softLink(scan, LINK)))) {
        awaitBlockedBackendPid(observer, PostgresLockTestSupport.backendPid(end), null);
        executeUpdate(end, "DELETE FROM library_operation WHERE id = ?", scan);
        end.commit();

        assertThat(save.await(BOUND)).isFalse();
      }
    }

    assertThat(storedSkippedFiles()).isEmpty();
  }

  private static void executeUpdate(Connection connection, String sql, UUID id)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.execute();
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

  private static Seek firstRows() {
    return Seek.builder()
        .order(
            ListOrder.builder().valueKind(SortValueKind.TEXT).direction(SortDirection.ASC).build())
        .position(Optional.empty())
        .direction(PaginationDirection.FORWARD)
        .limit(10)
        .build();
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
