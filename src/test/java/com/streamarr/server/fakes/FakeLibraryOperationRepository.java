package com.streamarr.server.fakes;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.library.LibraryOperation;
import com.streamarr.server.services.library.LibraryOperationEnd;
import com.streamarr.server.services.library.LibraryOperationKind;
import com.streamarr.server.services.library.LibraryOperationRejection;
import com.streamarr.server.services.library.NewLibraryOperation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps library operations in memory beside the libraries of a {@link LibraryRepository}. Like the
 * PostgreSQL repository, it decides on the library's status, and a start or an end changes the
 * library's status together with the operation. Each method runs under one lock, which stands in
 * for the library's row lock.
 */
public class FakeLibraryOperationRepository implements LibraryOperationRepository {

  private final LibraryRepository libraries;
  private final Clock clock;
  private final Map<UUID, LibraryOperation> operations = new LinkedHashMap<>();
  private UUID requester = SystemActor.ID;

  public FakeLibraryOperationRepository(LibraryRepository libraries, Clock clock) {
    this.libraries = libraries;
    this.clock = clock;
  }

  public FakeLibraryOperationRepository(LibraryRepository libraries) {
    this(libraries, Clock.systemUTC());
  }

  /** Names the account that the next stored operations record as their requester. */
  public synchronized void requestAs(UUID accountId) {
    requester = accountId;
  }

  @Override
  public synchronized StoreOutcome storeLibraryOperation(NewLibraryOperation newLibraryOperation) {
    var library = libraries.findById(newLibraryOperation.libraryId());
    if (library.isEmpty()) {
      return new StoreOutcome.Rejected(new LibraryOperationRejection.LibraryNotFound());
    }

    var rejection = busyRejection(library.get());
    if (rejection.isPresent()) {
      return new StoreOutcome.Rejected(rejection.get());
    }

    var statusBeforeOperation = library.get().getStatus();
    var now = clock.instant();
    var stored = library.get();
    stored.setStatus(newLibraryOperation.kind().busyStatus());
    if (newLibraryOperation.kind() == LibraryOperationKind.SCAN) {
      stored.setScanStartedOn(now);
    }

    libraries.save(stored);
    var operation =
        LibraryOperation.builder()
            .id(UUID.randomUUID())
            .libraryId(newLibraryOperation.libraryId())
            .kind(newLibraryOperation.kind())
            .imageRefreshMode(newLibraryOperation.imageRefreshMode())
            .statusBeforeOperation(statusBeforeOperation)
            .createdBy(requester)
            .createdOn(now)
            .build();
    operations.put(operation.id(), operation);
    return new StoreOutcome.Stored(operation, stored.toBuilder().build());
  }

  @Override
  public synchronized Optional<LibraryOperationRejection> lockLibraryForLibraryRemoval(
      UUID libraryId) {
    var library = libraries.findById(libraryId);
    if (library.isEmpty()) {
      return Optional.of(new LibraryOperationRejection.LibraryNotFound());
    }

    return busyRejection(library.get());
  }

  @Override
  public synchronized Optional<LibraryOperation> findLibraryOperation(UUID libraryOperationId) {
    return Optional.ofNullable(operations.get(libraryOperationId));
  }

  @Override
  public synchronized boolean tryLockLibraryOperation(UUID libraryOperationId) {
    return operations.containsKey(libraryOperationId);
  }

  @Override
  public synchronized boolean tryLockLibraryOfLibraryOperation(UUID libraryOperationId) {
    return operations.containsKey(libraryOperationId);
  }

  @Override
  public synchronized boolean tryLockLibraryWithoutLibraryOperation(UUID libraryId) {
    return libraries.existsById(libraryId) && operationFor(libraryId).isEmpty();
  }

  @Override
  public synchronized boolean tryEndLibraryOperation(LibraryOperationEnd end) {
    var operation = operations.remove(end.libraryOperationId());
    if (operation == null) {
      return false;
    }

    var library = libraries.findById(operation.libraryId()).orElseThrow();
    library.setStatus(end.conclusion().libraryStatus());
    if (operation.kind() == LibraryOperationKind.SCAN) {
      library.setScanCompletedOn(end.endedAt());
    }

    libraries.save(library);
    return true;
  }

  public synchronized List<LibraryOperation> libraryOperations() {
    return new ArrayList<>(operations.values());
  }

  public synchronized Optional<LibraryOperation> operationFor(UUID libraryId) {
    return operations.values().stream()
        .filter(operation -> operation.libraryId().equals(libraryId))
        .findFirst();
  }

  // Like the database, it decides on the status. A busy library always has its operation.
  private Optional<LibraryOperationRejection> busyRejection(Library library) {
    return switch (library.getStatus()) {
      case HEALTHY, UNHEALTHY -> Optional.empty();
      case SCANNING, REFRESHING ->
          Optional.of(
              new LibraryOperationRejection.LibraryBusy(
                  library.getStatus(), operationFor(library.getId()).orElseThrow().kind()));
    };
  }
}
