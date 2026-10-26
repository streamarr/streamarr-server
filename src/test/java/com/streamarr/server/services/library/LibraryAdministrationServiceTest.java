package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeLibraryMutationTransaction;
import com.streamarr.server.fakes.FakeLibraryOperationRepository;
import com.streamarr.server.fakes.FakeLibraryOperationScheduling;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
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
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Library Administration Service Tests")
class LibraryAdministrationServiceTest {

  private final AuthenticatedIdentity identity = defaultIdentityBuilder().build();
  private final FakeAuthorizationService authorizationService =
      new FakeAuthorizationService(identity);
  private final RecordingLibraryManagementService libraryManagementService =
      new RecordingLibraryManagementService();
  private final FakeLibraryRepository libraries = new FakeLibraryRepository();
  private final FakeLibraryOperationRepository libraryOperations =
      new FakeLibraryOperationRepository(libraries);
  private final FakeLibraryMutationTransaction libraryMutationTransaction =
      new FakeLibraryMutationTransaction();
  private final LibraryOperationService libraryOperationService =
      LibraryOperationService.builder()
          .libraryOperations(libraryOperations)
          .scheduling(new FakeLibraryOperationScheduling())
          .mutationTransactions(
              new MutationTransactions(
                  new FakeTransactionManager(), new ConstraintViolationTranslator()))
          .libraryMutationTransaction(libraryMutationTransaction)
          .eventPublisher(new CapturingEventPublisher())
          .metrics(new LibraryOperationMetrics(new SimpleMeterRegistry()))
          .build();
  private final LibraryAdministrationService libraryAdministrationService =
      new LibraryAdministrationService(
          authorizationService, libraryManagementService, libraryOperationService);

  @Test
  @DisplayName("Should authorize and add a Library through the application service")
  void shouldAuthorizeAndAddLibraryThroughApplicationService() {
    var library = Library.builder().name("Movies").build();

    var outcome = libraryAdministrationService.addLibrary(identity, library);

    assertThat(outcome).isEqualTo(Outcome.accepted(library));
    assertThat(authorizationService.recordedIntents()).containsExactly(new Intent.AddLibrary());
    assertThat(libraryManagementService.addedIdentity()).contains(identity);
    assertThat(libraryManagementService.addedLibrary()).contains(library);
  }

  @Test
  @DisplayName("Should reject removal before mutating a Library when authorization denies")
  void shouldRejectRemovalBeforeMutatingLibraryWhenAuthorizationDenies() {
    var libraryId = UUID.randomUUID();
    authorizationService.denyAll();

    assertThatThrownBy(() -> libraryAdministrationService.removeLibrary(identity, libraryId))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(libraryManagementService.removedLibraryId()).isEmpty();
  }

  @Test
  @DisplayName("Should authorize and remove a Library through the application service")
  void shouldAuthorizeAndRemoveLibraryThroughApplicationService() {
    var libraryId = UUID.randomUUID();

    var outcome = libraryAdministrationService.removeLibrary(identity, libraryId);

    assertThat(outcome).isEqualTo(Outcome.accepted(libraryId));
    assertThat(authorizationService.recordedIntents())
        .containsExactly(new Intent.RemoveLibrary(libraryId));
    assertThat(libraryManagementService.removedLibraryId()).contains(libraryId);
  }

  @Test
  @DisplayName("Should authorize and start a Library scan through the application service")
  void shouldAuthorizeAndStartLibraryScanThroughApplicationService() {
    var libraryId = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();

    var outcome = libraryAdministrationService.startLibraryScan(identity, libraryId);

    assertThat(outcome.map(Library::getStatus)).isEqualTo(Outcome.accepted(LibraryStatus.SCANNING));
    assertThat(authorizationService.recordedIntents())
        .containsExactly(new Intent.ScanLibrary(libraryId));
    assertThat(libraryMutationTransaction.executions())
        .extracting(FakeLibraryMutationTransaction.Execution::intent)
        .containsExactly(new Intent.ScanLibrary(libraryId));
    assertThat(libraryOperations.libraryOperations())
        .extracting(LibraryOperation::libraryId, LibraryOperation::kind)
        .containsExactly(tuple(libraryId, LibraryOperationKind.SCAN));
  }

  @Test
  @DisplayName("Should refuse a Library scan before storing it when authorization denies")
  void shouldRefuseALibraryScanBeforeStoringItWhenAuthorizationDenies() {
    var libraryId = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();
    authorizationService.denyAll();

    assertThatThrownBy(() -> libraryAdministrationService.startLibraryScan(identity, libraryId))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(libraryOperations.libraryOperations()).isEmpty();
  }

  @Test
  @DisplayName("Should authorize and start a Library refresh through the application service")
  void shouldAuthorizeAndStartLibraryRefreshThroughApplicationService() {
    var libraryId = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();

    var outcome =
        libraryAdministrationService.startLibraryRefresh(
            identity, libraryId, ImageRefreshMode.FORCE_REFRESH);

    assertThat(outcome.map(Library::getStatus))
        .isEqualTo(Outcome.accepted(LibraryStatus.REFRESHING));
    assertThat(authorizationService.recordedIntents())
        .containsExactly(new Intent.RefreshLibrary(libraryId));
    assertThat(libraryOperations.libraryOperations())
        .extracting(LibraryOperation::kind, LibraryOperation::imageRefreshMode)
        .containsExactly(
            tuple(LibraryOperationKind.REFRESH, Optional.of(ImageRefreshMode.FORCE_REFRESH)));
  }

  @Test
  @DisplayName("Should authorize and stop a Library scan through the application service")
  void shouldAuthorizeAndStopLibraryScanThroughApplicationService() {
    var libraryId = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();
    libraryAdministrationService.startLibraryScan(identity, libraryId);

    var outcome = libraryAdministrationService.stopLibraryScan(identity, libraryId);

    assertThat(outcome.map(Library::getStatus)).isEqualTo(Outcome.accepted(LibraryStatus.STOPPING));
    assertThat(authorizationService.recordedIntents())
        .endsWith(new Intent.StopLibraryScan(libraryId));
    assertThat(libraryOperations.operationFor(libraryId).orElseThrow().stopRequested()).isTrue();
  }

  @Test
  @DisplayName("Should authorize and stop a Library refresh through the application service")
  void shouldAuthorizeAndStopLibraryRefreshThroughApplicationService() {
    var libraryId = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();
    libraryAdministrationService.startLibraryRefresh(
        identity, libraryId, ImageRefreshMode.PRESERVE);

    var outcome = libraryAdministrationService.stopLibraryRefresh(identity, libraryId);

    assertThat(outcome.map(Library::getStatus)).isEqualTo(Outcome.accepted(LibraryStatus.STOPPING));
    assertThat(authorizationService.recordedIntents())
        .endsWith(new Intent.StopLibraryRefresh(libraryId));
  }

  @Test
  @DisplayName("Should refuse a stop before storing it when authorization denies")
  void shouldRefuseAStopBeforeStoringItWhenAuthorizationDenies() {
    var libraryId = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();
    libraryAdministrationService.startLibraryScan(identity, libraryId);
    authorizationService.denyAll();

    assertThatThrownBy(() -> libraryAdministrationService.stopLibraryScan(identity, libraryId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> libraryAdministrationService.stopLibraryRefresh(identity, libraryId))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(libraryOperations.operationFor(libraryId).orElseThrow().stopRequested()).isFalse();
    assertThat(libraries.findById(libraryId).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.SCANNING);
  }

  private static final class RecordingLibraryManagementService extends LibraryManagementService {

    private AuthenticatedIdentity addedIdentity;
    private Library addedLibrary;
    private UUID removedLibraryId;

    private RecordingLibraryManagementService() {
      super(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public Outcome<Library, AddLibraryRejection> addLibrary(
        AuthenticatedIdentity identity, Library library) {
      addedIdentity = identity;
      addedLibrary = library;
      return Outcome.accepted(library);
    }

    @Override
    public Outcome<UUID, LibraryOperationRejection> removeLibrary(
        AuthenticatedIdentity identity, UUID libraryId) {
      removedLibraryId = libraryId;
      return Outcome.accepted(libraryId);
    }

    private Optional<AuthenticatedIdentity> addedIdentity() {
      return Optional.ofNullable(addedIdentity);
    }

    private Optional<Library> addedLibrary() {
      return Optional.ofNullable(addedLibrary);
    }

    private Optional<UUID> removedLibraryId() {
      return Optional.ofNullable(removedLibraryId);
    }
  }
}
