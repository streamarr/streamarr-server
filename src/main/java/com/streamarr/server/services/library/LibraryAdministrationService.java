package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LibraryAdministrationService {

  private final AuthorizationService authorizationService;
  private final LibraryManagementService libraryManagementService;

  public Library addLibrary(AuthenticatedIdentity identity, Library library) {
    authorizationService.requireAllowed(identity, new Intent.AddLibrary());
    return libraryManagementService.addLibrary(identity, library);
  }

  public void removeLibrary(AuthenticatedIdentity identity, UUID libraryId) {
    authorizationService.requireAllowed(identity, new Intent.RemoveLibrary(libraryId));
    libraryManagementService.removeLibrary(identity, libraryId);
  }

  public void scanLibrary(AuthenticatedIdentity identity, UUID libraryId) {
    authorizationService.requireAllowed(identity, new Intent.ScanLibrary(libraryId));
    libraryManagementService.triggerAsyncScan(libraryId);
  }

  public void refreshLibrary(
      AuthenticatedIdentity identity, UUID libraryId, ImageRefreshMode imageRefreshMode) {
    authorizationService.requireAllowed(identity, new Intent.RefreshLibrary(libraryId));
    libraryManagementService.triggerAsyncRefresh(libraryId, imageRefreshMode);
  }
}
