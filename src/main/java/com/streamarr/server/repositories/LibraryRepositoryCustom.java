package com.streamarr.server.repositories;

import com.streamarr.server.domain.Library;
import java.util.List;

public interface LibraryRepositoryCustom {

  /**
   * Serializes addLibrary in the caller's transaction, then reads every library. A concurrent
   * addLibrary waits here until this transaction ends, so it reads the library this transaction
   * adds.
   *
   * @return every library, ordered by name and then by id
   * @throws org.springframework.dao.PessimisticLockingFailureException when another addLibrary
   *     holds the lock past the bounded lock wait
   */
  List<Library> lockLibraryPathsForAddLibrary();
}
