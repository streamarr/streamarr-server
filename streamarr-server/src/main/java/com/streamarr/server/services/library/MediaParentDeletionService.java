package com.streamarr.server.services.library;

import java.util.Set;
import java.util.UUID;

public interface MediaParentDeletionService {

  void deleteLibrary(UUID libraryId);

  void resumeLibraryDeletion(UUID libraryId);

  void deleteMediaFiles(UUID libraryId, Set<UUID> mediaFileIds);

  void resumeMediaFileDeletion(UUID mediaFileId);

  boolean isLibraryDeletionPending(UUID libraryId);
}
