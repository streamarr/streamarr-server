package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.LibraryOperation.LIBRARY_OPERATION;
import static com.streamarr.server.jooq.generated.tables.SkippedFile.SKIPPED_FILE;

import com.streamarr.server.domain.SkippedFile;
import com.streamarr.server.domain.SkippedFileReason;
import com.streamarr.server.jooq.generated.tables.records.SkippedFileRecord;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.CommonTableExpression;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqSkippedFileRepository implements SkippedFileRepository {

  private final DSLContext dsl;

  @Override
  public boolean trySave(SkippedFile skippedFile) {
    var libraryOperation = heldLibraryOperation(skippedFile.libraryOperationId());
    return dsl.with(libraryOperation)
            .insertInto(
                SKIPPED_FILE,
                SKIPPED_FILE.ID,
                SKIPPED_FILE.LIBRARY_ID,
                SKIPPED_FILE.LIBRARY_OPERATION_ID,
                SKIPPED_FILE.FILEPATH_URI,
                SKIPPED_FILE.REASON)
            .select(
                DSL.select(
                        DSL.val(skippedFile.id(), SKIPPED_FILE.ID),
                        DSL.val(skippedFile.libraryId(), SKIPPED_FILE.LIBRARY_ID),
                        libraryOperation.field(LIBRARY_OPERATION.ID),
                        DSL.val(skippedFile.filepathUri(), SKIPPED_FILE.FILEPATH_URI),
                        DSL.val(toJooq(skippedFile.reason()), SKIPPED_FILE.REASON))
                    .from(libraryOperation))
            .onConflict(SKIPPED_FILE.LIBRARY_ID, SKIPPED_FILE.FILEPATH_URI)
            .doUpdate()
            .set(SKIPPED_FILE.LIBRARY_OPERATION_ID, DSL.excluded(SKIPPED_FILE.LIBRARY_OPERATION_ID))
            .set(SKIPPED_FILE.REASON, DSL.excluded(SKIPPED_FILE.REASON))
            .execute()
        == 1;
  }

  @Override
  public void deleteSkippedFilesOfOtherLibraryOperations(UUID libraryId, UUID libraryOperationId) {
    var libraryOperation = heldLibraryOperation(libraryOperationId);
    dsl.with(libraryOperation)
        .delete(SKIPPED_FILE)
        .where(SKIPPED_FILE.LIBRARY_ID.eq(libraryId))
        .and(SKIPPED_FILE.LIBRARY_OPERATION_ID.ne(libraryOperationId))
        .andExists(DSL.selectOne().from(libraryOperation))
        .execute();
  }

  // A write holds its library operation's row until it commits, so the transaction that ends the
  // operation and deletes the row waits for the write. A write whose operation has ended finds no
  // row and writes nothing: an execution that db-scheduler revived after its operation ended can
  // neither delete nor overwrite the skipped files of the library's next scan.
  private static CommonTableExpression<Record1<UUID>> heldLibraryOperation(
      UUID libraryOperationId) {
    return DSL.name("held_library_operation")
        .as(
            DSL.select(LIBRARY_OPERATION.ID)
                .from(LIBRARY_OPERATION)
                .where(LIBRARY_OPERATION.ID.eq(libraryOperationId))
                .forKeyShare());
  }

  @Override
  public List<Row<SkippedFile>> rowsBeyond(UUID libraryId, Seek seek) {
    var keyset =
        Keyset.<String>builder()
            .sortValue(SKIPPED_FILE.FILEPATH_URI)
            .itemId(SKIPPED_FILE.ID)
            .build();
    return keyset
        .rowsBeyond(dsl.selectFrom(SKIPPED_FILE), SKIPPED_FILE.LIBRARY_ID.eq(libraryId), seek)
        .fetch(row -> new Row<>(toDomain(row), keyset.rowOf(row).position()));
  }

  private static SkippedFile toDomain(SkippedFileRecord row) {
    return SkippedFile.builder()
        .libraryId(row.getLibraryId())
        .libraryOperationId(row.getLibraryOperationId())
        .filepathUri(row.getFilepathUri())
        .reason(SkippedFileReason.valueOf(row.getReason().getLiteral()))
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.SkippedFileReason toJooq(
      SkippedFileReason reason) {
    return com.streamarr.server.jooq.generated.enums.SkippedFileReason.lookupLiteral(reason.name());
  }
}
