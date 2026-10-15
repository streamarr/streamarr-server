package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.LibraryOperationScheduler;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.exceptions.LibraryOperationNotStartedException;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.LibraryOperationRejection.LibraryBusy;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.server.support.security.SignedInAccount;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Library operation service integration tests")
class LibraryOperationServiceIT extends AbstractIntegrationTest {

  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path tempDir;

  @Autowired private LibraryOperationService libraryOperationService;
  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private LibraryOperationScheduler contextScheduler;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private LibraryOperationRepository libraryOperations;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JdbcTemplate jdbcTemplate;

  private AuthTestSupport.TestIdentity admin;
  private AuthenticatedIdentity identity;
  private final List<UUID> createdLibraries = new ArrayList<>();

  // Stored operations must stay stored while a test inspects them, so nothing executes them.
  @BeforeEach
  void setUp() {
    contextScheduler.stop();
    deleteLibraryOperations();
    admin = authTestSupport.createAdminIdentity();
    identity = authTestSupport.identityOf(admin);
  }

  @AfterEach
  void tearDown() {
    dropFailureTriggers();
    deleteLibraryOperations();
    createdLibraries.forEach(libraryRepository::deleteById);
    authTestSupport.deleteIdentity(admin);
    contextScheduler.start();
  }

  @Test
  @DisplayName(
      "Should store exactly one operation and schedule exactly one task when scans and refreshes"
          + " start concurrently on an idle library")
  void shouldStoreExactlyOneOperationWhenScansAndRefreshesStartConcurrently() throws Exception {
    var library = createLibrary();
    var requests = 10;
    var barrier = new CyclicBarrier(requests);
    var starts = new ArrayList<BoundedTask<Outcome<Library, LibraryOperationRejection>>>();

    try {
      for (var request = 0; request < requests; request++) {
        var refresh = request % 2 == 1;
        starts.add(
            BoundedTask.start(
                () -> {
                  barrier.await(BOUND.toSeconds(), TimeUnit.SECONDS);
                  return asAdmin(
                      () ->
                          refresh
                              ? libraryOperationService.startLibraryRefresh(
                                  identity, library.getId(), ImageRefreshMode.FORCE_REFRESH)
                              : libraryOperationService.startLibraryScan(
                                  identity, library.getId()));
                }));
      }

      var outcomes = new ArrayList<Outcome<Library, LibraryOperationRejection>>();
      for (var start : starts) {
        outcomes.add(start.await(BOUND));
      }

      var stored = outcomes.stream().filter(Outcome.Accepted.class::isInstance).toList();
      assertThat(stored).hasSize(1);
      var runningKind = libraryOperation(library).kind();
      assertThat(outcomes)
          .filteredOn(Outcome.Rejected.class::isInstance)
          .hasSize(requests - 1)
          .allSatisfy(
              outcome ->
                  assertThat(outcome)
                      .isEqualTo(
                          Outcome.rejected(
                              new LibraryBusy(runningKind.busyStatus(), runningKind))));
      assertThat(statusOf(library)).isEqualTo(runningKind.busyStatus());
      assertThat(storedLibraryOperations()).isOne();
      assertThat(scheduledLibraryOperations()).isOne();
    } finally {
      starts.forEach(BoundedTask::close);
    }
  }

  @Test
  @DisplayName(
      "Should return the library SCANNING and name the ServerAdmin as requester when a scan starts")
  void shouldReturnTheLibraryScanningAndNameTheServerAdminAsRequesterWhenAScanStarts() {
    var library = createLibrary();

    var outcome =
        asAdmin(() -> libraryOperationService.startLibraryScan(identity, library.getId()));

    assertThat(outcome.map(Library::getStatus)).isEqualTo(Outcome.accepted(LibraryStatus.SCANNING));
    assertThat(statusOf(library)).isEqualTo(LibraryStatus.SCANNING);
    var operation = libraryOperation(library);
    assertThat(operation.kind()).isEqualTo(LibraryOperationKind.SCAN);
    assertThat(operation.createdBy()).isEqualTo(admin.account().getId());
    assertThat(operation.statusBeforeOperation()).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(scheduledLibraryOperations()).isOne();
  }

  @Test
  @DisplayName(
      "Should leave no operation, no scheduled task and the status unchanged when the task cannot"
          + " be scheduled")
  void shouldLeaveNoOperationWhenTheTaskCannotBeScheduled() {
    var library = createLibrary();
    rejectInsertsInto(LibraryOperationScheduler.TABLE_NAME);

    assertThatThrownBy(
            () ->
                asAdmin(() -> libraryOperationService.startLibraryScan(identity, library.getId())))
        .isInstanceOf(LibraryOperationNotStartedException.class);

    assertThatNothingStarted(library);
  }

  @Test
  @DisplayName(
      "Should leave no operation, no scheduled task and the status unchanged when the operation"
          + " cannot be stored")
  void shouldLeaveNoOperationWhenTheOperationCannotBeStored() {
    var library = createLibrary();
    rejectInsertsInto("library_operation");

    assertThatThrownBy(
            () ->
                asAdmin(
                    () ->
                        libraryOperationService.startLibraryRefresh(
                            identity, library.getId(), ImageRefreshMode.PRESERVE)))
        .isInstanceOf(LibraryOperationNotStartedException.class);

    assertThatNothingStarted(library);
  }

  @Test
  @DisplayName(
      "Should leave no operation, no scheduled task and the status unchanged when the start fails"
          + " at commit")
  void shouldLeaveNoOperationWhenTheStartFailsAtCommit() {
    var library = createLibrary();
    rejectCommitsOfLibraryOperations();

    assertThatThrownBy(
            () ->
                asAdmin(() -> libraryOperationService.startLibraryScan(identity, library.getId())))
        .isInstanceOf(LibraryOperationNotStartedException.class);

    assertThatNothingStarted(library);
  }

  @Test
  @DisplayName("Should return the added library SCANNING with its first scan stored")
  void shouldReturnTheAddedLibraryScanningWithItsFirstScanStored() {
    var outcome = asAdmin(() -> libraryManagementService.addLibrary(identity, unsavedLibrary()));

    var added = outcome.fold(library -> library, _ -> null);
    assertThat(added).isNotNull();
    createdLibraries.add(added.getId());
    assertThat(added.getStatus()).isEqualTo(LibraryStatus.SCANNING);
    assertThat(statusOf(added)).isEqualTo(LibraryStatus.SCANNING);
    assertThat(libraryOperation(added).kind()).isEqualTo(LibraryOperationKind.SCAN);
    assertThat(libraryOperation(added).createdBy()).isEqualTo(admin.account().getId());
    assertThat(scheduledLibraryOperations()).isOne();
  }

  @Test
  @DisplayName("Should store no scan and schedule nothing when an add rolls back")
  void shouldStoreNoScanAndScheduleNothingWhenAnAddRollsBack() {
    var existing = createLibrary();

    var outcome =
        asAdmin(
            () ->
                libraryManagementService.addLibrary(
                    identity,
                    unsavedLibrary().toBuilder().filepathUri(tempDir.toString()).build()));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new AddLibraryRejection.PathAlreadyRegistered()));
    assertThat(statusOf(existing)).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(storedLibraryOperations()).isZero();
    assertThat(scheduledLibraryOperations()).isZero();
  }

  @Test
  @DisplayName("Should refuse removeLibrary and keep the library while a scan runs")
  void shouldRefuseRemoveLibraryAndKeepTheLibraryWhileAScanRuns() {
    var library = createLibrary();
    asAdmin(() -> libraryOperationService.startLibraryScan(identity, library.getId()));

    var outcome = asAdmin(() -> libraryManagementService.removeLibrary(identity, library.getId()));

    assertThat(outcome)
        .isEqualTo(
            Outcome.rejected(new LibraryBusy(LibraryStatus.SCANNING, LibraryOperationKind.SCAN)));
    assertThat(libraryRepository.existsById(library.getId())).isTrue();
  }

  private <T> T asAdmin(Supplier<T> work) {
    return SignedInAccount.callSignedInAs(admin.account().getId(), work);
  }

  private void assertThatNothingStarted(Library library) {
    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(storedLibraryOperations()).isZero();
    assertThat(scheduledLibraryOperations()).isZero();
  }

  private Library createLibrary() {
    var library =
        libraryRepository.saveAndFlush(
            unsavedLibrary().toBuilder().filepathUri(FilepathCodec.encode(tempDir)).build());
    createdLibraries.add(library.getId());
    return library;
  }

  private Library unsavedLibrary() {
    return Library.builder()
        .name("Movies")
        .filepathUri(tempDir.toString())
        .backend(LibraryBackend.LOCAL)
        .status(LibraryStatus.HEALTHY)
        .type(MediaType.MOVIE)
        .externalAgentStrategy(ExternalAgentStrategy.TMDB)
        .build();
  }

  private LibraryStatus statusOf(Library library) {
    return libraryRepository.findById(library.getId()).orElseThrow().getStatus();
  }

  private LibraryOperation libraryOperation(Library library) {
    var id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM library_operation WHERE library_id = ?", UUID.class, library.getId());
    return libraryOperations.findLibraryOperation(id).orElseThrow();
  }

  private int storedLibraryOperations() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM library_operation", Integer.class);
  }

  private int scheduledLibraryOperations() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM " + LibraryOperationScheduler.TABLE_NAME, Integer.class);
  }

  private void deleteLibraryOperations() {
    jdbcTemplate.update("DELETE FROM library_operation");
    jdbcTemplate.update("DELETE FROM " + LibraryOperationScheduler.TABLE_NAME);
  }

  private void rejectInsertsInto(String table) {
    createFailingFunction();
    jdbcTemplate.execute(
        "CREATE TRIGGER reject_library_operation_start BEFORE INSERT ON "
            + table
            + " FOR EACH ROW EXECUTE FUNCTION reject_library_operation_start()");
  }

  private void rejectCommitsOfLibraryOperations() {
    createFailingFunction();
    jdbcTemplate.execute(
        """
        CREATE CONSTRAINT TRIGGER reject_library_operation_start AFTER INSERT ON library_operation
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION reject_library_operation_start()
        """);
  }

  // Spring leaves the default SQLSTATE of RAISE untranslated, so the trigger raises one that a
  // real resource failure would.
  private void createFailingFunction() {
    jdbcTemplate.execute(
        """
        CREATE OR REPLACE FUNCTION reject_library_operation_start() RETURNS trigger AS $$
        BEGIN
          RAISE EXCEPTION 'simulated library operation start failure' USING ERRCODE = '53000';
        END
        $$ LANGUAGE plpgsql
        """);
  }

  private void dropFailureTriggers() {
    jdbcTemplate.execute(
        "DROP TRIGGER IF EXISTS reject_library_operation_start ON "
            + LibraryOperationScheduler.TABLE_NAME);
    jdbcTemplate.execute(
        "DROP TRIGGER IF EXISTS reject_library_operation_start ON library_operation");
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_library_operation_start()");
  }
}
