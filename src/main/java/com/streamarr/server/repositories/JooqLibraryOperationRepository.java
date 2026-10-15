package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.Library.LIBRARY;
import static com.streamarr.server.jooq.generated.tables.LibraryOperation.LIBRARY_OPERATION;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.jooq.generated.tables.records.LibraryOperationRecord;
import com.streamarr.server.jooq.generated.tables.records.LibraryRecord;
import com.streamarr.server.services.library.LibraryOperation;
import com.streamarr.server.services.library.LibraryOperationEnd;
import com.streamarr.server.services.library.LibraryOperationKind;
import com.streamarr.server.services.library.LibraryOperationRejection;
import com.streamarr.server.services.library.NewLibraryOperation;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JooqLibraryOperationRepository implements LibraryOperationRepository {

  // A start, an end and removeLibrary each hold the library's row for a few statements, so a
  // longer wait means a stuck transaction, and the request fails as busy instead of hanging.
  private static final Duration LIBRARY_ROW_LOCK_TIMEOUT = Duration.ofSeconds(5);

  private final DSLContext dsl;
  private final PostgresTransactionLocks transactionLocks;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public StoreOutcome storeLibraryOperation(NewLibraryOperation newLibraryOperation) {
    return transactionLocks.withLockWaitLimit(
        LIBRARY_ROW_LOCK_TIMEOUT, () -> storeUnderLibraryLock(newLibraryOperation));
  }

  private StoreOutcome storeUnderLibraryLock(NewLibraryOperation newLibraryOperation) {
    var libraryId = newLibraryOperation.libraryId();
    var status = lockLibraryStatus(libraryId);
    if (status.isEmpty()) {
      return new StoreOutcome.Rejected(new LibraryOperationRejection.LibraryNotFound());
    }

    if (isBusy(status.get())) {
      return new StoreOutcome.Rejected(busy(libraryId, status.get()));
    }

    var library = markLibraryBusy(newLibraryOperation);
    var operation =
        dsl.insertInto(LIBRARY_OPERATION)
            .set(LIBRARY_OPERATION.ID, UUID.randomUUID())
            .set(LIBRARY_OPERATION.LIBRARY_ID, libraryId)
            .set(LIBRARY_OPERATION.KIND, toJooq(newLibraryOperation.kind()))
            .set(
                LIBRARY_OPERATION.IMAGE_REFRESH_MODE,
                newLibraryOperation
                    .imageRefreshMode()
                    .map(JooqLibraryOperationRepository::toJooq)
                    .orElse(null))
            .set(LIBRARY_OPERATION.STATUS_BEFORE_OPERATION, toJooq(status.get()))
            .returning()
            .fetchSingle();
    return new StoreOutcome.Stored(toLibraryOperation(operation), library);
  }

  private Library markLibraryBusy(NewLibraryOperation newLibraryOperation) {
    var update =
        dsl.update(LIBRARY).set(LIBRARY.STATUS, toJooq(newLibraryOperation.kind().busyStatus()));
    if (newLibraryOperation.kind() == LibraryOperationKind.SCAN) {
      update = update.set(LIBRARY.SCAN_STARTED_ON, DSL.currentOffsetDateTime());
    }

    return toLibrary(
        update.where(LIBRARY.ID.eq(newLibraryOperation.libraryId())).returning().fetchSingle());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<LibraryOperationRejection> lockLibraryForLibraryRemoval(UUID libraryId) {
    return transactionLocks.withLockWaitLimit(
        LIBRARY_ROW_LOCK_TIMEOUT,
        () -> {
          var status = lockLibraryStatus(libraryId);
          if (status.isEmpty()) {
            return Optional.of(new LibraryOperationRejection.LibraryNotFound());
          }

          if (isBusy(status.get())) {
            return Optional.of(busy(libraryId, status.get()));
          }

          return Optional.empty();
        });
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<LibraryOperation> findLibraryOperation(UUID libraryOperationId) {
    return dsl.selectFrom(LIBRARY_OPERATION)
        .where(LIBRARY_OPERATION.ID.eq(libraryOperationId))
        .fetchOptional()
        .map(JooqLibraryOperationRepository::toLibraryOperation);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsLibraryOperationForLibrary(UUID libraryId) {
    return dsl.fetchExists(LIBRARY_OPERATION, LIBRARY_OPERATION.LIBRARY_ID.eq(libraryId));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean tryEndLibraryOperation(LibraryOperationEnd end) {
    return transactionLocks.withLockWaitLimit(
        LIBRARY_ROW_LOCK_TIMEOUT, () -> tryEndUnderLibraryLock(end));
  }

  private boolean tryEndUnderLibraryLock(LibraryOperationEnd end) {
    var libraryId =
        dsl.select(LIBRARY_OPERATION.LIBRARY_ID)
            .from(LIBRARY_OPERATION)
            .where(LIBRARY_OPERATION.ID.eq(end.libraryOperationId()))
            .fetchOptional(LIBRARY_OPERATION.LIBRARY_ID);
    if (libraryId.isEmpty()) {
      return false;
    }

    lockLibraryStatus(libraryId.get());
    var kind =
        dsl.deleteFrom(LIBRARY_OPERATION)
            .where(LIBRARY_OPERATION.ID.eq(end.libraryOperationId()))
            .returning(LIBRARY_OPERATION.KIND)
            .fetchOptional(LIBRARY_OPERATION.KIND);
    if (kind.isEmpty()) {
      return false;
    }

    var update = dsl.update(LIBRARY).set(LIBRARY.STATUS, toJooq(end.conclusion().libraryStatus()));
    if (toDomain(kind.get()) == LibraryOperationKind.SCAN) {
      update = update.set(LIBRARY.SCAN_COMPLETED_ON, end.endedAt().atOffset(ZoneOffset.UTC));
    }

    update.where(LIBRARY.ID.eq(libraryId.get())).execute();
    return true;
  }

  private Optional<LibraryStatus> lockLibraryStatus(UUID libraryId) {
    return dsl.select(LIBRARY.STATUS)
        .from(LIBRARY)
        .where(LIBRARY.ID.eq(libraryId))
        .forUpdate()
        .fetchOptional(LIBRARY.STATUS)
        .map(status -> LibraryStatus.valueOf(status.getLiteral()));
  }

  // A busy library always has its operation row, because the start and the end change both in
  // one transaction under the library's row lock.
  private LibraryOperationRejection busy(UUID libraryId, LibraryStatus status) {
    var runningKind =
        dsl.select(LIBRARY_OPERATION.KIND)
            .from(LIBRARY_OPERATION)
            .where(LIBRARY_OPERATION.LIBRARY_ID.eq(libraryId))
            .fetchSingle(LIBRARY_OPERATION.KIND);
    return new LibraryOperationRejection.LibraryBusy(status, toDomain(runningKind));
  }

  private static boolean isBusy(LibraryStatus status) {
    return switch (status) {
      case SCANNING, REFRESHING -> true;
      case HEALTHY, UNHEALTHY -> false;
    };
  }

  private static LibraryOperation toLibraryOperation(LibraryOperationRecord row) {
    return LibraryOperation.builder()
        .id(row.getId())
        .libraryId(row.getLibraryId())
        .kind(toDomain(row.getKind()))
        .imageRefreshMode(
            Optional.ofNullable(row.getImageRefreshMode())
                .map(mode -> ImageRefreshMode.valueOf(mode.getLiteral())))
        .statusBeforeOperation(LibraryStatus.valueOf(row.getStatusBeforeOperation().getLiteral()))
        .createdBy(row.getCreatedBy())
        .createdOn(row.getCreatedOn().toInstant())
        .build();
  }

  // The mapping leaves out the audit columns. The library builder refuses audit values, and the
  // audit trigger fills the audit columns.
  private static Library toLibrary(LibraryRecord row) {
    return Library.builder()
        .id(row.getId())
        .filepathUri(row.getFilepathUri())
        .name(row.getName())
        .scanStartedOn(toInstant(row.getScanStartedOn()))
        .scanCompletedOn(toInstant(row.getScanCompletedOn()))
        .status(LibraryStatus.valueOf(row.getStatus().getLiteral()))
        .backend(LibraryBackend.valueOf(row.getBackend().getLiteral()))
        .type(MediaType.valueOf(row.getType().getLiteral()))
        .externalAgentStrategy(
            ExternalAgentStrategy.valueOf(row.getExternalAgentStrategy().getLiteral()))
        .build();
  }

  private static Instant toInstant(OffsetDateTime time) {
    if (time == null) {
      return null;
    }

    return time.toInstant();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static LibraryOperationKind toDomain(
      com.streamarr.server.jooq.generated.enums.LibraryOperationKind kind) {
    return LibraryOperationKind.valueOf(kind.getLiteral());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.LibraryOperationKind toJooq(
      LibraryOperationKind kind) {
    return com.streamarr.server.jooq.generated.enums.LibraryOperationKind.lookupLiteral(
        kind.name());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.LibraryStatus toJooq(
      LibraryStatus status) {
    return com.streamarr.server.jooq.generated.enums.LibraryStatus.lookupLiteral(status.name());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.ImageRefreshMode toJooq(
      ImageRefreshMode mode) {
    return com.streamarr.server.jooq.generated.enums.ImageRefreshMode.lookupLiteral(mode.name());
  }
}
