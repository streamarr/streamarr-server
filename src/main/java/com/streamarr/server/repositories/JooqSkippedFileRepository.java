package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.Library.LIBRARY;
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
    var library = heldLibrary(skippedFile.libraryId());
    var libraryOperation = heldLibraryOperation(library, skippedFile.libraryOperationId());
    return dsl.with(library, libraryOperation)
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
    var library = heldLibrary(libraryId);
    var libraryOperation = heldLibraryOperation(library, libraryOperationId);
    dsl.with(library, libraryOperation)
        .delete(SKIPPED_FILE)
        .where(SKIPPED_FILE.LIBRARY_ID.eq(libraryId))
        .and(SKIPPED_FILE.LIBRARY_OPERATION_ID.ne(libraryOperationId))
        .andExists(DSL.selectOne().from(libraryOperation))
        .execute();
  }

  // A write holds its library operation's row until it commits, so the transaction that ends the
  // operation and deletes the row waits for the write. A write whose operation has ended finds no
  // row and writes nothing: an execution that db-scheduler revived after its operation ended can
  // neither delete nor overwrite the skipped files of the library's next scan. The end locks the
  // library's row before the operation's row, and a new skipped file's foreign key locks the
  // library's row, so a write takes the library's row first too; the operation's row joins it,
  // which locks it only after the library's row.
  private static CommonTableExpression<Record1<UUID>> heldLibrary(UUID libraryId) {
    return DSL.name("held_library")
        .asMaterialized(
            DSL.select(LIBRARY.ID).from(LIBRARY).where(LIBRARY.ID.eq(libraryId)).forKeyShare());
  }

  private static CommonTableExpression<Record1<UUID>> heldLibraryOperation(
      CommonTableExpression<Record1<UUID>> heldLibrary, UUID libraryOperationId) {
    return DSL.name("held_library_operation")
        .asMaterialized(
            DSL.select(LIBRARY_OPERATION.ID)
                .from(LIBRARY_OPERATION)
                .join(heldLibrary)
                .on(heldLibrary.field(LIBRARY.ID).eq(LIBRARY_OPERATION.LIBRARY_ID))
                .where(LIBRARY_OPERATION.ID.eq(libraryOperationId))
                .forKeyShare()
                .of(LIBRARY_OPERATION));
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
