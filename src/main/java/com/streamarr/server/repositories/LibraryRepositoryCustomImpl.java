package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.Library.LIBRARY;

import com.streamarr.server.domain.Library;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class LibraryRepositoryCustomImpl implements LibraryRepositoryCustom {

  // Only addLibrary takes this key. The set of library paths has no row to lock.
  private static final long ADD_LIBRARY_LOCK_KEY = 0x5354524D_4C494252L;

  // addLibrary holds the lock for one insert and the first scan's storage, so a longer wait
  // means a stuck transaction, and the request fails as busy instead of hanging.
  private static final Duration ADD_LIBRARY_LOCK_TIMEOUT = Duration.ofSeconds(5);

  private final DSLContext dsl;
  private final EntityManager entityManager;
  private final PostgresTransactionLocks transactionLocks;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<Library> lockLibraryPathsForAddLibrary() {
    return transactionLocks.withLockWaitLimit(
        ADD_LIBRARY_LOCK_TIMEOUT,
        () -> {
          transactionLocks.lock(ADD_LIBRARY_LOCK_KEY);
          var query =
              dsl.select(LIBRARY.asterisk()).from(LIBRARY).orderBy(LIBRARY.NAME, LIBRARY.ID);
          return JooqQueryHelper.nativeQuery(entityManager, query, Library.class);
        });
  }
}
