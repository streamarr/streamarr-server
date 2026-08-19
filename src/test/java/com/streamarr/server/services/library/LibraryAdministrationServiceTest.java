package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.Library;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.mutation.Outcome;
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
  private final LibraryAdministrationService libraryAdministrationService =
      new LibraryAdministrationService(authorizationService, libraryManagementService);

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

    libraryAdministrationService.removeLibrary(identity, libraryId);

    assertThat(authorizationService.recordedIntents())
        .containsExactly(new Intent.RemoveLibrary(libraryId));
    assertThat(libraryManagementService.removedLibraryId()).contains(libraryId);
  }

  @Test
  @DisplayName("Should authorize and start a Library scan through the application service")
  void shouldAuthorizeAndStartLibraryScanThroughApplicationService() {
    var libraryId = UUID.randomUUID();

    libraryAdministrationService.scanLibrary(identity, libraryId);

    assertThat(authorizationService.recordedIntents())
        .containsExactly(new Intent.ScanLibrary(libraryId));
    assertThat(libraryManagementService.scannedLibraryId()).contains(libraryId);
  }

  @Test
  @DisplayName("Should authorize and start a Library refresh through the application service")
  void shouldAuthorizeAndStartLibraryRefreshThroughApplicationService() {
    var libraryId = UUID.randomUUID();

    libraryAdministrationService.refreshLibrary(
        identity, libraryId, ImageRefreshMode.FORCE_REFRESH);

    assertThat(authorizationService.recordedIntents())
        .containsExactly(new Intent.RefreshLibrary(libraryId));
    assertThat(libraryManagementService.refreshRequest())
        .contains(new RefreshRequest(libraryId, ImageRefreshMode.FORCE_REFRESH));
  }

  private static final class RecordingLibraryManagementService extends LibraryManagementService {

    private AuthenticatedIdentity addedIdentity;
    private Library addedLibrary;
    private UUID removedLibraryId;
    private UUID scannedLibraryId;
    private RefreshRequest refreshRequest;

    private RecordingLibraryManagementService() {
      super(
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          new MutexFactoryProvider(),
          null,
          null,
          null);
    }

    @Override
    public Outcome<Library, AddLibraryRejection> addLibrary(
        AuthenticatedIdentity identity, Library library) {
      addedIdentity = identity;
      addedLibrary = library;
      return Outcome.accepted(library);
    }

    @Override
    public void removeLibrary(AuthenticatedIdentity identity, UUID libraryId) {
      removedLibraryId = libraryId;
    }

    @Override
    public void triggerAsyncScan(UUID libraryId) {
      scannedLibraryId = libraryId;
    }

    @Override
    public void triggerAsyncRefresh(UUID libraryId, ImageRefreshMode imageRefreshMode) {
      refreshRequest = new RefreshRequest(libraryId, imageRefreshMode);
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

    private Optional<UUID> scannedLibraryId() {
      return Optional.ofNullable(scannedLibraryId);
    }

    private Optional<RefreshRequest> refreshRequest() {
      return Optional.ofNullable(refreshRequest);
    }
  }

  private record RefreshRequest(UUID libraryId, ImageRefreshMode imageRefreshMode) {}
}
