package com.streamarr.server.services.library;

import static com.streamarr.server.jooq.generated.tables.SkippedFile.SKIPPED_FILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.SkippedFile;
import com.streamarr.server.domain.SkippedFileReason;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.SkippedFileRepository;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import com.streamarr.server.support.StoredLibraryOperations;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("IntegrationTest")
@DisplayName("Library skipped file paging")
class LibrarySkippedFilesPagedListIT
    extends PagedListContract<SkippedFile, LibrarySkippedFilesPagedListIT.Scope> {

  @Autowired private LibraryAdministrationService libraryAdministration;
  @Autowired private SkippedFileRepository skippedFiles;
  @Autowired private LibraryRepository libraries;
  @Autowired private AuthTestSupport auth;
  @Autowired private DSLContext dsl;
  @Autowired private LibraryOperationRepository libraryOperationRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private final Map<String, UUID> idByFilepathUri = new HashMap<>();

  /**
   * A ServerAdmin, a library, the filepaths of the library's skipped files, and the scan whose
   * skipped files the library shows.
   */
  record Scope(
      AuthTestSupport.TestIdentity admin,
      Library library,
      Set<String> filepathUris,
      AtomicReference<UUID> latestScan) {}

  @Override
  protected Scope createScope() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return new Scope(
        auth.createAdminIdentity(),
        library,
        new HashSet<>(),
        new AtomicReference<>(libraryOperations().storeScan(library.getId())));
  }

  @Override
  protected void deleteScope(Scope scope) {
    libraries.deleteById(scope.library().getId());
    auth.deleteIdentity(scope.admin());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var filepathUri = "file:///skipped/%03d-%s.mkv".formatted(rank, UUID.randomUUID());
    skippedFiles.trySave(softLink(scope, scope.latestScan().get(), filepathUri));
    var id =
        dsl.select(SKIPPED_FILE.ID)
            .from(SKIPPED_FILE)
            .where(SKIPPED_FILE.LIBRARY_ID.eq(scope.library().getId()))
            .and(SKIPPED_FILE.FILEPATH_URI.eq(filepathUri))
            .fetchSingle(SKIPPED_FILE.ID);
    scope.filepathUris().add(filepathUri);
    idByFilepathUri.put(filepathUri, id);
    return id;
  }

  // A later scan no longer finds the item's soft link.
  @Override
  protected void remove(Scope scope, UUID item) {
    scope.filepathUris().removeIf(filepathUri -> idByFilepathUri.get(filepathUri).equals(item));
    rescan(scope);
  }

  @Override
  protected ListPage<SkippedFile> page(Scope scope, PageRequest request) {
    return libraryAdministration.skippedFiles(
        auth.identityOf(scope.admin()), scope.library().getId(), request);
  }

  @Test
  @DisplayName(
      "Should continue after a skipped file's position when a rescan stores the file again")
  void shouldContinueAfterASkippedFilesPositionWhenARescanStoresTheFileAgain() {
    var scope = createScope();
    try {
      add(scope, 0);
      var second = add(scope, 1);
      var firstPage = page(scope, firstPageOf(1));
      rescan(scope);

      var nextPage =
          page(
              scope,
              PageRequest.builder()
                  .position(Optional.of(firstPage.rows().getFirst().position()))
                  .direction(PaginationDirection.FORWARD)
                  .limit(2)
                  .build());

      assertThat(page(scope, firstPageOf(2)).rows())
          .extracting(Row::position)
          .first()
          .isEqualTo(firstPage.rows().getFirst().position());
      assertThat(nextPage.rows()).extracting(row -> idOf(row.item())).containsExactly(second);
    } finally {
      deleteScope(scope);
    }
  }

  // The earlier scan ends, and a rescan that finds the same soft links deletes the earlier scan's
  // skips as its walk starts, then stores each link again.
  private void rescan(Scope scope) {
    var libraryOperations = libraryOperations();
    libraryOperations.endAsSystemActor(scope.latestScan().get());
    var laterScan = libraryOperations.storeScan(scope.library().getId());
    skippedFiles.deleteSkippedFilesOfOtherLibraryOperations(scope.library().getId(), laterScan);
    scope
        .filepathUris()
        .forEach(filepathUri -> skippedFiles.trySave(softLink(scope, laterScan, filepathUri)));
    scope.latestScan().set(laterScan);
  }

  private StoredLibraryOperations libraryOperations() {
    return new StoredLibraryOperations(libraryOperationRepository, transactionManager);
  }

  private static PageRequest firstPageOf(int limit) {
    return PageRequest.builder()
        .position(Optional.empty())
        .direction(PaginationDirection.FORWARD)
        .limit(limit)
        .build();
  }

  @Override
  protected UUID idOf(SkippedFile skippedFile) {
    return idByFilepathUri.get(skippedFile.filepathUri());
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.ASC;
  }

  @Override
  protected boolean sortValuesCanTie() {
    return false;
  }

  @Override
  protected Optional<Position> positionBeforeArrangedItems(Scope scope) {
    return Optional.empty();
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.empty();
  }

  private static SkippedFile softLink(Scope scope, UUID scanId, String filepathUri) {
    return SkippedFile.builder()
        .libraryId(scope.library().getId())
        .libraryOperationId(scanId)
        .filepathUri(filepathUri)
        .reason(SkippedFileReason.SOFT_LINK)
        .build();
  }
}
