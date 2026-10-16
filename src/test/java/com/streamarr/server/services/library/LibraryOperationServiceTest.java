package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.exceptions.LibraryOperationNotScheduledException;
import com.streamarr.server.exceptions.LibraryOperationNotStartedException;
import com.streamarr.server.exceptions.ResourceBusyException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeLibraryMutationTransaction;
import com.streamarr.server.fakes.FakeLibraryOperationRepository;
import com.streamarr.server.fakes.FakeLibraryOperationScheduling;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import com.streamarr.server.services.library.LibraryOperationRejection.LibraryBusy;
import com.streamarr.server.services.library.LibraryOperationRejection.LibraryNotFound;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

@Tag("UnitTest")
@DisplayName("Library operation service")
class LibraryOperationServiceTest {

  private final AuthenticatedIdentity identity = defaultIdentityBuilder().build();
  private final FakeLibraryRepository libraries = new FakeLibraryRepository();
  private final FakeLibraryOperationRepository libraryOperations =
      new FakeLibraryOperationRepository(libraries);
  private final FakeLibraryOperationScheduling scheduling = new FakeLibraryOperationScheduling();
  private final FakeLibraryMutationTransaction libraryMutationTransaction =
      new FakeLibraryMutationTransaction();
  private final FakeTransactionManager transactionManager = new FakeTransactionManager();
  private final CapturingEventPublisher eventPublisher = new CapturingEventPublisher();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LibraryOperationService service = serviceStoringWith(libraryOperations);
  private final UUID libraryId = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();

  @Test
  @DisplayName(
      "Should return the library SCANNING, schedule its operation and publish its status when a"
          + " scan starts on an idle library")
  void shouldReturnTheLibraryScanningWhenAScanStartsOnAnIdleLibrary() {
    var outcome = service.startLibraryScan(identity, libraryId);

    assertThat(outcome.map(Library::getStatus)).isEqualTo(Outcome.accepted(LibraryStatus.SCANNING));
    var operation = libraryOperations.operationFor(libraryId).orElseThrow();
    assertThat(operation.kind()).isEqualTo(LibraryOperationKind.SCAN);
    assertThat(operation.imageRefreshMode()).isEmpty();
    assertThat(scheduling.scheduledExecutions()).containsExactly(operation.id());
    assertThat(eventPublisher.getEventsOfType(LibraryStatusChangedEvent.class))
        .containsExactly(new LibraryStatusChangedEvent(libraryId));
    assertThat(libraryMutationTransaction.executions())
        .extracting(FakeLibraryMutationTransaction.Execution::intent)
        .containsExactly(new Intent.ScanLibrary(libraryId));
    assertThat(transactionManager.commits()).isOne();
    assertThat(requests(LibraryOperationKind.SCAN, "stored")).isOne();
  }

  @Test
  @DisplayName("Should store the refresh's image refresh mode when a refresh starts")
  void shouldStoreTheRefreshsImageRefreshModeWhenARefreshStarts() {
    var outcome = service.startLibraryRefresh(identity, libraryId, ImageRefreshMode.FORCE_REFRESH);

    assertThat(outcome.map(Library::getStatus))
        .isEqualTo(Outcome.accepted(LibraryStatus.REFRESHING));
    assertThat(libraryOperations.operationFor(libraryId).orElseThrow().imageRefreshMode())
        .isEqualTo(Optional.of(ImageRefreshMode.FORCE_REFRESH));
    assertThat(libraryMutationTransaction.executions())
        .extracting(FakeLibraryMutationTransaction.Execution::intent)
        .containsExactly(new Intent.RefreshLibrary(libraryId));
    assertThat(requests(LibraryOperationKind.REFRESH, "stored")).isOne();
  }

  @Test
  @DisplayName(
      "Should refuse with LibraryNotFound and schedule nothing when the library is missing")
  void shouldRefuseWithLibraryNotFoundWhenTheLibraryIsMissing() {
    var outcome = service.startLibraryScan(identity, UUID.randomUUID());

    assertThat(outcome).isEqualTo(Outcome.rejected(new LibraryNotFound()));
    assertThat(scheduling.scheduledExecutions()).isEmpty();
    assertThat(eventPublisher.getEventsOfType(LibraryStatusChangedEvent.class)).isEmpty();
    assertThat(transactionManager.rollbacks()).isOne();
    assertThat(requests(LibraryOperationKind.SCAN, "not_found")).isOne();
  }

  @Test
  @DisplayName("Should refuse an identical scan with LibraryBusy naming the running scan")
  void shouldRefuseAnIdenticalScanWithLibraryBusyNamingTheRunningScan() {
    service.startLibraryScan(identity, libraryId);

    var outcome = service.startLibraryScan(identity, libraryId);

    assertThat(outcome)
        .isEqualTo(
            Outcome.rejected(new LibraryBusy(LibraryStatus.SCANNING, LibraryOperationKind.SCAN)));
    assertThat(libraryOperations.libraryOperations()).hasSize(1);
    assertThat(scheduling.scheduledExecutions()).hasSize(1);
    assertThat(requests(LibraryOperationKind.SCAN, "busy")).isOne();
  }

  @Test
  @DisplayName("Should refuse a refresh with LibraryBusy naming the running scan")
  void shouldRefuseARefreshWithLibraryBusyNamingTheRunningScan() {
    service.startLibraryScan(identity, libraryId);

    var outcome = service.startLibraryRefresh(identity, libraryId, ImageRefreshMode.PRESERVE);

    assertThat(outcome)
        .isEqualTo(
            Outcome.rejected(new LibraryBusy(LibraryStatus.SCANNING, LibraryOperationKind.SCAN)));
    assertThat(requests(LibraryOperationKind.REFRESH, "busy")).isOne();
  }

  @Test
  @DisplayName(
      "Should refuse a stronger refresh with LibraryBusy and keep the running refresh's mode")
  void shouldRefuseAStrongerRefreshWithLibraryBusyAndKeepTheRunningRefreshsMode() {
    service.startLibraryRefresh(identity, libraryId, ImageRefreshMode.PRESERVE);

    var outcome = service.startLibraryRefresh(identity, libraryId, ImageRefreshMode.FORCE_REFRESH);

    assertThat(outcome)
        .isEqualTo(
            Outcome.rejected(
                new LibraryBusy(LibraryStatus.REFRESHING, LibraryOperationKind.REFRESH)));
    assertThat(libraryOperations.operationFor(libraryId).orElseThrow().imageRefreshMode())
        .contains(ImageRefreshMode.PRESERVE);
  }

  @Test
  @DisplayName("Should throw LibraryOperationNotStartedException when scheduling fails")
  void shouldThrowLibraryOperationNotStartedExceptionWhenSchedulingFails() {
    var failure =
        new LibraryOperationNotScheduledException(
            UUID.randomUUID(), new IllegalStateException("simulated insert failure"));
    scheduling.failNextWith(failure);

    assertThatThrownBy(() -> service.startLibraryScan(identity, libraryId))
        .isInstanceOf(LibraryOperationNotStartedException.class)
        .hasCause(failure);
    assertThat(transactionManager.rollbacks()).isOne();
    assertThat(eventPublisher.getEventsOfType(LibraryStatusChangedEvent.class)).isEmpty();
    assertThat(requests(LibraryOperationKind.SCAN, "failed")).isOne();
  }

  @Test
  @DisplayName("Should throw LibraryOperationNotStartedException when storing fails")
  void shouldThrowLibraryOperationNotStartedExceptionWhenStoringFails() {
    var failure = new DataAccessResourceFailureException("simulated connection loss");
    var failingService = serviceStoringWith(throwingRepository(failure));

    assertThatThrownBy(
            () ->
                failingService.startLibraryRefresh(identity, libraryId, ImageRefreshMode.PRESERVE))
        .isInstanceOf(LibraryOperationNotStartedException.class)
        .hasCause(failure);
    assertThat(requests(LibraryOperationKind.REFRESH, "failed")).isOne();
  }

  @Test
  @DisplayName("Should throw LibraryOperationNotStartedException when the transaction fails")
  void shouldThrowLibraryOperationNotStartedExceptionWhenTheTransactionFails() {
    var failure = new TransactionSystemException("simulated commit failure");
    var failingService = serviceStoringWith(throwingRepository(failure));

    assertThatThrownBy(() -> failingService.startLibraryScan(identity, libraryId))
        .isInstanceOf(LibraryOperationNotStartedException.class)
        .hasCause(failure);
  }

  @Test
  @DisplayName("Should throw ResourceBusyException when the library's row stays locked")
  void shouldThrowResourceBusyExceptionWhenTheLibrarysRowStaysLocked() {
    var failingService =
        serviceStoringWith(throwingRepository(new CannotAcquireLockException("lock timeout")));

    assertThatThrownBy(() -> failingService.startLibraryScan(identity, libraryId))
        .isInstanceOf(ResourceBusyException.class);
    assertThat(requests(LibraryOperationKind.SCAN, "failed")).isOne();
  }

  @Test
  @DisplayName("Should store the scan without a mutation intent when the System actor starts it")
  void shouldStoreTheScanWithoutAMutationIntentWhenTheSystemActorStartsIt() {
    var outcome = service.startLibraryScanAsSystemActor(libraryId);

    assertThat(outcome.map(Library::getStatus)).isEqualTo(Outcome.accepted(LibraryStatus.SCANNING));
    assertThat(libraryMutationTransaction.executions()).isEmpty();
    assertThat(scheduling.scheduledExecutions()).hasSize(1);
  }

  @Test
  @DisplayName("Should schedule the first scan of a library added in the caller's transaction")
  void shouldScheduleTheFirstScanOfALibraryAddedInTheCallersTransaction() {
    var library = service.storeFirstLibraryScan(libraryId);

    assertThat(library.getStatus()).isEqualTo(LibraryStatus.SCANNING);
    assertThat(scheduling.scheduledExecutions())
        .containsExactly(libraryOperations.operationFor(libraryId).orElseThrow().id());
    assertThat(eventPublisher.getEventsOfType(LibraryStatusChangedEvent.class))
        .containsExactly(new LibraryStatusChangedEvent(libraryId));
  }

  @Test
  @DisplayName("Should throw IllegalStateException when the added library refuses its first scan")
  void shouldThrowIllegalStateExceptionWhenTheAddedLibraryRefusesItsFirstScan() {
    service.storeFirstLibraryScan(libraryId);

    assertThatThrownBy(() -> service.storeFirstLibraryScan(libraryId))
        .isInstanceOf(IllegalStateException.class);
  }

  private LibraryOperationService serviceStoringWith(LibraryOperationRepository repository) {
    return LibraryOperationService.builder()
        .libraryOperations(repository)
        .scheduling(scheduling)
        .mutationTransactions(
            new MutationTransactions(transactionManager, new ConstraintViolationTranslator()))
        .libraryMutationTransaction(libraryMutationTransaction)
        .eventPublisher(eventPublisher)
        .metrics(new LibraryOperationMetrics(meterRegistry))
        .build();
  }

  private FakeLibraryOperationRepository throwingRepository(RuntimeException failure) {
    return new FakeLibraryOperationRepository(libraries) {
      @Override
      public StoreOutcome storeLibraryOperation(NewLibraryOperation newLibraryOperation) {
        throw failure;
      }
    };
  }

  private double requests(LibraryOperationKind kind, String outcome) {
    var counter =
        meterRegistry
            .find("streamarr.library.operations.requests")
            .tag("kind", kind.tagValue())
            .tag("outcome", outcome)
            .counter();
    if (counter == null) {
      return 0;
    }

    return counter.count();
  }
}
