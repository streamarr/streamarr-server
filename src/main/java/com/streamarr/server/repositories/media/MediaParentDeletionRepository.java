package com.streamarr.server.repositories.media;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaParentDeletionRepository {

  Optional<LibraryDeletionTarget> prepareLibraryDeletion(UUID libraryId);

  Optional<LibraryDeletionTarget> resumeLibraryDeletion(UUID libraryId);

  List<MediaFileDeletionTarget> prepareMediaFileDeletions(
      UUID libraryId, Collection<UUID> mediaFileIds);

  Optional<MediaFileDeletionTarget> resumeMediaFileDeletion(UUID mediaFileId);

  List<DeletionIntentEntry> findLibraryDeletionIntents(int limit);

  List<DeletionIntentEntry> findLibraryDeletionIntentsAfter(DeletionIntentEntry cursor, int limit);

  List<DeletionIntentEntry> findStandaloneMediaFileDeletionIntents(int limit);

  List<DeletionIntentEntry> findStandaloneMediaFileDeletionIntentsAfter(
      DeletionIntentEntry cursor, int limit);

  boolean hasLibraryDeletionIntent(UUID libraryId);

  List<UUID> findReferencingStreamIds(Collection<UUID> mediaFileIds);

  boolean hasReferencingStreams(Collection<UUID> mediaFileIds);
}
