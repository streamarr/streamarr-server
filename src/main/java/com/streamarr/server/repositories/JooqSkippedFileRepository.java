package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.SkippedFile.SKIPPED_FILE;

import com.streamarr.server.domain.SkippedFile;
import com.streamarr.server.domain.SkippedFileReason;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqSkippedFileRepository implements SkippedFileRepository {

  private final DSLContext dsl;

  @Override
  public void save(SkippedFile skippedFile) {
    dsl.insertInto(SKIPPED_FILE)
        .set(SKIPPED_FILE.LIBRARY_ID, skippedFile.libraryId())
        .set(SKIPPED_FILE.LIBRARY_OPERATION_ID, skippedFile.libraryOperationId())
        .set(SKIPPED_FILE.FILEPATH_URI, skippedFile.filepathUri())
        .set(SKIPPED_FILE.REASON, toJooq(skippedFile.reason()))
        .onConflict(SKIPPED_FILE.LIBRARY_ID, SKIPPED_FILE.FILEPATH_URI)
        .doUpdate()
        .set(SKIPPED_FILE.LIBRARY_OPERATION_ID, DSL.excluded(SKIPPED_FILE.LIBRARY_OPERATION_ID))
        .set(SKIPPED_FILE.REASON, DSL.excluded(SKIPPED_FILE.REASON))
        .execute();
  }

  @Override
  public void deleteSkippedFilesOfOtherLibraryOperations(UUID libraryId, UUID libraryOperationId) {
    dsl.deleteFrom(SKIPPED_FILE)
        .where(SKIPPED_FILE.LIBRARY_ID.eq(libraryId))
        .and(SKIPPED_FILE.LIBRARY_OPERATION_ID.ne(libraryOperationId))
        .execute();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.SkippedFileReason toJooq(
      SkippedFileReason reason) {
    return com.streamarr.server.jooq.generated.enums.SkippedFileReason.lookupLiteral(reason.name());
  }
}
