package com.streamarr.server.repositories;

import com.streamarr.server.domain.SkippedFile;
import java.util.UUID;

/** Stores the files that the latest scan of each library skipped. */
public interface SkippedFileRepository {

  /** Stores the skip, replacing the one that an earlier scan stored for the same file. */
  void save(SkippedFile skippedFile);

  /**
   * Deletes the library's skipped files that another library operation stored, so the library's
   * skipped files are those of its current scan.
   */
  void deleteSkippedFilesOfOtherLibraryOperations(UUID libraryId, UUID libraryOperationId);
}
