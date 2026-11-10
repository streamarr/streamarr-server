package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.SkippedFile;
import com.streamarr.server.domain.media.ImageRefreshMode;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LibraryAdministrationService {

  private final AuthorizationService authorizationService;
  private final LibraryManagementService libraryManagementService;
  private final LibraryOperationService libraryOperationService;
  private final LibrarySkippedFilesList librarySkippedFilesList;

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

  /**
   * Refuses unless the identity may view library administration. A Library's administration field
   * checks it each time it resolves. libraryStatusChanged checks it once, when it registers, and
   * access-token expiry bounds the authority afterwards (ADR 0016).
   */
  public void requireViewLibraryAdministration(AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.ViewLibraryAdministration());
  }

  /**
   * A page of the files that the library's latest scan skipped, in filepath order, for an identity
   * that may view library administration.
   */
  public ListPage<SkippedFile> skippedFiles(
      AuthenticatedIdentity identity, UUID libraryId, PageRequest request) {
    authorizationService.requireAllowed(identity, new Intent.ViewLibraryAdministration());
    return Pager.page(librarySkippedFilesList, libraryId, request);
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
