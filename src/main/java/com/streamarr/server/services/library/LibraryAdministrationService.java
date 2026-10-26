package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.mutation.Outcome;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LibraryAdministrationService {

  private final AuthorizationService authorizationService;
  private final LibraryManagementService libraryManagementService;
  private final LibraryOperationService libraryOperationService;

  public Outcome<Library, AddLibraryRejection> addLibrary(
      AuthenticatedIdentity identity, Library library) {
    authorizationService.requireAllowed(identity, new Intent.AddLibrary());
    return libraryManagementService.addLibrary(identity, library);
  }

  public Outcome<UUID, LibraryOperationRejection> removeLibrary(
      AuthenticatedIdentity identity, UUID libraryId) {
    authorizationService.requireAllowed(identity, new Intent.RemoveLibrary(libraryId));
    return libraryManagementService.removeLibrary(identity, libraryId);
  }

  public Outcome<Library, LibraryOperationRejection> startLibraryScan(
      AuthenticatedIdentity identity, UUID libraryId) {
    authorizationService.requireAllowed(identity, new Intent.ScanLibrary(libraryId));
    return libraryOperationService.startLibraryScan(identity, libraryId);
  }

  public Outcome<Library, LibraryOperationRejection> startLibraryRefresh(
      AuthenticatedIdentity identity, UUID libraryId, ImageRefreshMode imageRefreshMode) {
    authorizationService.requireAllowed(identity, new Intent.RefreshLibrary(libraryId));
    return libraryOperationService.startLibraryRefresh(identity, libraryId, imageRefreshMode);
  }

  public Outcome<Library, LibraryOperationStopRejection> stopLibraryScan(
      AuthenticatedIdentity identity, UUID libraryId) {
    authorizationService.requireAllowed(identity, new Intent.StopLibraryScan(libraryId));
    return libraryOperationService.stopLibraryScan(identity, libraryId);
  }

  public Outcome<Library, LibraryOperationStopRejection> stopLibraryRefresh(
      AuthenticatedIdentity identity, UUID libraryId) {
    authorizationService.requireAllowed(identity, new Intent.StopLibraryRefresh(libraryId));
    return libraryOperationService.stopLibraryRefresh(identity, libraryId);
  }
}
