package com.streamarr.server.repositories;

import java.util.UUID;

public interface LibraryMetadataRepositoryCustom {

  /**
   * Counts the library's items by the first letter of their sort title and replaces the library's
   * stored letter counts with those counts, in the caller's transaction or a new one.
   *
   * @return how many distinct letters the library's items start with
   */
  int replaceLetterCounts(UUID libraryId);
}
