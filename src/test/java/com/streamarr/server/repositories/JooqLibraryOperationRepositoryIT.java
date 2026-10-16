package com.streamarr.server.repositories;

import static com.streamarr.server.services.library.LibraryOperationKind.SCAN;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.repositories.LibraryOperationRepository.StopOutcome;
import com.streamarr.server.repositories.LibraryOperationRepository.StoreOutcome;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.library.LibraryOperation;
import com.streamarr.server.services.library.LibraryOperationConclusion;
import com.streamarr.server.services.library.LibraryOperationEnd;
import com.streamarr.server.services.library.LibraryOperationKind;
import com.streamarr.server.services.library.LibraryOperationStop;
import com.streamarr.server.services.library.LibraryOperationStopRejection;
import com.streamarr.server.services.library.NewLibraryOperation;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.server.support.PostgresLockTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.HeldRowLock;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("jOOQ library operation repository integration tests")
class JooqLibraryOperationRepositoryIT extends AbstractIntegrationTest {

  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path tempDir;

  @Autowired private LibraryOperationRepository libraryOperations;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  private Library library;

  @AfterEach
  void tearDown() {
    if (library != null) {
      libraryRepository.deleteById(library.getId());
    }
  }

  @Test
  @DisplayName("Should end nothing and leave the library unchanged when the operation is unknown")
  void shouldEndNothingAndLeaveTheLibraryUnchangedWhenTheOperationIsUnknown() {
    library = createLibrary();

    var ended = endAsSystemActor(finishedEnd(UUID.randomUUID()));

    assertThat(ended).isEmpty();
    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName(
      "Should end the operation exactly once when two executions end it behind the library's row")
  void shouldEndTheOperationExactlyOnceWhenTwoExecutionsEndItBehindTheLibrarysRow()
      throws Exception {
    library = createLibrary();
    var operation = storeScan(library);
    var end = finishedEnd(operation.id());

    try (var heldLibrary = lockLibraryRow(library);
        var firstEnd = BoundedTask.start(() -> endAsSystemActor(end));
        var secondEnd = startBehind(heldLibrary, () -> endAsSystemActor(end))) {
      awaitWaitersBehind(jdbcTemplate, heldLibrary.backendPid(), 2);
      heldLibrary.release();

      assertThat(firstEnd.await(BOUND)).contains(LibraryOperationConclusion.FINISHED);
      assertThat(secondEnd.await(BOUND)).isEmpty();
    }

    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(libraryOperations.findLibraryOperation(operation.id())).isEmpty();
    assertThat(libraryRepository.findById(library.getId()).orElseThrow().getScanCompletedOn())
        .isNotNull();
  }

  @Test
  @DisplayName(
      "Should store the stop, show the library STOPPING and keep the status before the scan when a"
          + " running scan is stopped")
  void shouldStoreTheStopAndShowTheLibraryStoppingWhenARunningScanIsStopped() {
    library = createLibrary(LibraryStatus.UNHEALTHY);
    var scan = storeScan(library);

    var outcome = stopAsSystemActor(new LibraryOperationStop(library.getId(), SCAN));

    assertThat(outcome).isInstanceOf(StopOutcome.Stored.class);
    assertThat(((StopOutcome.Stored) outcome).library().getStatus())
        .isEqualTo(LibraryStatus.STOPPING);
    assertThat(statusOf(library)).isEqualTo(LibraryStatus.STOPPING);
    assertThat(libraryOperations.findLibraryOperation(scan.id()))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.stopRequested()).isTrue();
              assertThat(stored.statusBeforeOperation()).isEqualTo(LibraryStatus.UNHEALTHY);
            });
    assertThat(libraryOperations.hasStoredStop(scan.id())).isTrue();
  }

  @Test
  @DisplayName("Should return the library unchanged as AlreadyStored when a stop repeats")
  void shouldReturnTheLibraryUnchangedAsAlreadyStoredWhenAStopRepeats() {
    library = createLibrary();
    storeScan(library);
    stopAsSystemActor(new LibraryOperationStop(library.getId(), SCAN));
    var stopped = libraryRepository.findById(library.getId()).orElseThrow();

    var outcome = stopAsSystemActor(new LibraryOperationStop(library.getId(), SCAN));

    assertThat(outcome).isInstanceOf(StopOutcome.AlreadyStored.class);
    var returned = ((StopOutcome.AlreadyStored) outcome).library();
    assertThat(returned.getStatus()).isEqualTo(LibraryStatus.STOPPING);
    assertThat(returned.getLastModifiedOn()).isEqualTo(stopped.getLastModifiedOn());
  }

  @Test
  @DisplayName(
      "Should reject the stop naming the running kind and store nothing when the stop names the"
          + " other kind")
  void shouldRejectTheStopNamingTheRunningKindWhenTheStopNamesTheOtherKind() {
    library = createLibrary();
    var refresh = storeRefresh(library);

    var outcome = stopAsSystemActor(new LibraryOperationStop(library.getId(), SCAN));

    assertThat(outcome)
        .isEqualTo(
            new StopOutcome.Rejected(
                new LibraryOperationStopRejection.NotInProgress(
                    LibraryStatus.REFRESHING, Optional.of(LibraryOperationKind.REFRESH))));
    assertThat(libraryOperations.hasStoredStop(refresh.id())).isFalse();
    assertThat(statusOf(library)).isEqualTo(LibraryStatus.REFRESHING);
  }

  @Test
  @DisplayName("Should reject the stop when the library is idle or missing")
  void shouldRejectTheStopWhenTheLibraryIsIdleOrMissing() {
    library = createLibrary();

    var idle = stopAsSystemActor(new LibraryOperationStop(library.getId(), SCAN));
    var missing = stopAsSystemActor(new LibraryOperationStop(UUID.randomUUID(), SCAN));

    assertThat(idle)
        .isEqualTo(
            new StopOutcome.Rejected(
                new LibraryOperationStopRejection.NotInProgress(
                    LibraryStatus.HEALTHY, Optional.empty())));
    assertThat(missing)
        .isEqualTo(new StopOutcome.Rejected(new LibraryOperationStopRejection.LibraryNotFound()));
    assertThat(libraryOperations.hasStoredStop(UUID.randomUUID())).isFalse();
  }

  @Test
  @DisplayName(
      "Should conclude STOPPED and return the library to its status before the scan when a"
          + " stopped scan finishes")
  void shouldConcludeStoppedAndReturnTheLibraryToItsStatusBeforeTheScanWhenAStoppedScanFinishes() {
    library = createLibrary(LibraryStatus.UNHEALTHY);
    var scan = storeScan(library);
    stopAsSystemActor(new LibraryOperationStop(library.getId(), SCAN));

    var ended = endAsSystemActor(finishedEnd(scan.id()));

    assertThat(ended).contains(LibraryOperationConclusion.STOPPED);
    assertThat(statusOf(library)).isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(libraryOperations.findLibraryOperation(scan.id())).isEmpty();
  }

  @Test
  @DisplayName("Should conclude FAILED with the library UNHEALTHY when a stopped scan fails")
  void shouldConcludeFailedWithTheLibraryUnhealthyWhenAStoppedScanFails() {
    library = createLibrary();
    var scan = storeScan(library);
    stopAsSystemActor(new LibraryOperationStop(library.getId(), SCAN));

    var ended =
        endAsSystemActor(
            LibraryOperationEnd.builder()
                .libraryOperationId(scan.id())
                .conclusion(LibraryOperationConclusion.FAILED)
                .endedAt(Instant.now())
                .build());

    assertThat(ended).contains(LibraryOperationConclusion.FAILED);
    assertThat(statusOf(library)).isEqualTo(LibraryStatus.UNHEALTHY);
  }

  @Test
  @DisplayName(
      "Should conclude STOPPED when a stop waits behind the library's row and commits before the"
          + " end reads the operation")
  void shouldConcludeStoppedWhenAStopCommitsBeforeTheEndReadsTheOperation() throws Exception {
    library = createLibrary();
    var scan = storeScan(library);

    try (var heldLibrary = lockLibraryRow(library);
        var stop =
            BoundedTask.start(
                () -> stopAsSystemActor(new LibraryOperationStop(library.getId(), SCAN)));
        var end = startBehind(heldLibrary, () -> endAsSystemActor(finishedEnd(scan.id())))) {
      awaitWaitersBehind(jdbcTemplate, heldLibrary.backendPid(), 2);
      heldLibrary.release();

      assertThat(stop.await(BOUND)).isInstanceOf(StopOutcome.Stored.class);
      assertThat(end.await(BOUND)).contains(LibraryOperationConclusion.STOPPED);
    }

    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName("Should return the library with its row's audit columns when a scan is stored")
  void shouldReturnTheLibraryWithItsRowsAuditColumnsWhenAScanIsStored() {
    library = createLibrary();

    var stored = storedLibrary(() -> libraryOperations.storeLibraryOperation(scanOf(library)));

    var row = libraryRepository.findById(library.getId()).orElseThrow();
    assertThat(stored.getCreatedBy()).isNotNull().isEqualTo(row.getCreatedBy());
    assertThat(stored.getCreatedOn()).isNotNull().isEqualTo(row.getCreatedOn());
    assertThat(stored.getLastModifiedBy()).isNotNull().isEqualTo(row.getLastModifiedBy());
    assertThat(stored.getLastModifiedOn())
        .isEqualTo(row.getLastModifiedOn())
        .isAfter(row.getCreatedOn());
  }

  @Test
  @DisplayName(
      "Should return the library as the scan's start left it when the transaction saved the"
          + " library first")
  void shouldReturnTheLibraryAsTheScansStartLeftItWhenTheTransactionSavedTheLibraryFirst() {
    var stored =
        storedLibrary(
            () -> {
              library = createLibrary();
              return libraryOperations.storeLibraryOperation(scanOf(library));
            });

    var row = libraryRepository.findById(library.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(LibraryStatus.SCANNING);
    assertThat(stored.getScanStartedOn()).isNotNull().isEqualTo(row.getScanStartedOn());
    assertThat(stored.getCreatedOn()).isNotNull().isEqualTo(row.getCreatedOn());
    assertThat(stored.getLastModifiedOn()).isNotNull().isEqualTo(row.getLastModifiedOn());
  }

  private Library storedLibrary(Supplier<StoreOutcome> store) {
    var outcome = new TransactionTemplate(transactionManager).execute(_ -> store.get());
    return switch (outcome) {
      case StoreOutcome.Stored(_, var stored) -> stored;
      case StoreOutcome.Rejected(var rejection) ->
          throw new AssertionError("The scan was not stored: " + rejection);
      case null -> throw new AssertionError("The store returned nothing");
    };
  }

  private static NewLibraryOperation scanOf(Library scanned) {
    return NewLibraryOperation.scan(scanned.getId());
  }

  private Optional<LibraryOperationConclusion> endAsSystemActor(LibraryOperationEnd end) {
    var ended = new AtomicReference<Optional<LibraryOperationConclusion>>();
    SystemWork.runAsSystemActor(
        () ->
            ended.set(
                new TransactionTemplate(transactionManager)
                    .execute(_ -> libraryOperations.tryEndLibraryOperation(end))));
    return ended.get();
  }

  private StopOutcome stopAsSystemActor(LibraryOperationStop stop) {
    var outcome = new AtomicReference<StopOutcome>();
    SystemWork.runAsSystemActor(
        () ->
            outcome.set(
                new TransactionTemplate(transactionManager)
                    .execute(_ -> libraryOperations.stopLibraryOperation(stop))));
    return outcome.get();
  }

  private LibraryOperation storeRefresh(Library refreshed) {
    var outcome =
        new TransactionTemplate(transactionManager)
            .execute(
                _ ->
                    libraryOperations.storeLibraryOperation(
                        NewLibraryOperation.refresh(refreshed.getId(), ImageRefreshMode.PRESERVE)));
    return switch (outcome) {
      case StoreOutcome.Stored(var stored, _) -> stored;
      case StoreOutcome.Rejected(var rejection) ->
          throw new AssertionError("The refresh was not stored: " + rejection);
      case null -> throw new AssertionError("The store returned nothing");
    };
  }

  private LibraryOperation storeScan(Library scanned) {
    var outcome =
        new TransactionTemplate(transactionManager)
            .execute(
                _ ->
                    libraryOperations.storeLibraryOperation(
                        NewLibraryOperation.scan(scanned.getId())));
    return switch (outcome) {
      case StoreOutcome.Stored(var stored, _) -> stored;
      case StoreOutcome.Rejected(var rejection) ->
          throw new AssertionError("The scan was not stored: " + rejection);
      case null -> throw new AssertionError("The store returned nothing");
    };
  }

  private static LibraryOperationEnd finishedEnd(UUID libraryOperationId) {
    return LibraryOperationEnd.builder()
        .libraryOperationId(libraryOperationId)
        .conclusion(LibraryOperationConclusion.FINISHED)
        .endedAt(Instant.now())
        .build();
  }

  // PostgreSQL queues row-lock waiters in arrival order, so the second end starts only after the
  // first end waits behind the held row.
  private <T> BoundedTask<T> startBehind(HeldRowLock heldLibrary, Callable<T> end) {
    awaitWaitersBehind(jdbcTemplate, heldLibrary.backendPid(), 1);
    return BoundedTask.start(end);
  }

  private HeldRowLock lockLibraryRow(Library locked) throws SQLException {
    return PostgresLockTestSupport.lockRow(
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("library")
            .rowId(locked.getId())
            .build());
  }

  private Library createLibrary() {
    return createLibrary(LibraryStatus.HEALTHY);
  }

  private Library createLibrary(LibraryStatus status) {
    return libraryRepository.saveAndFlush(
        Library.builder()
            .name("Movies")
            .filepathUri(tempDir.toUri().toString())
            .backend(LibraryBackend.LOCAL)
            .status(status)
            .type(MediaType.MOVIE)
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build());
  }

  private LibraryStatus statusOf(Library inspected) {
    return libraryRepository.findById(inspected.getId()).orElseThrow().getStatus();
  }
}
