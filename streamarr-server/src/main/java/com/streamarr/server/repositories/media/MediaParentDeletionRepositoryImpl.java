package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.Library.LIBRARY;
import static com.streamarr.server.jooq.generated.tables.LibraryDeletionIntent.LIBRARY_DELETION_INTENT;
import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.MediaFileDeletionIntent.MEDIA_FILE_DELETION_INTENT;
import static com.streamarr.server.jooq.generated.tables.StreamSession.STREAM_SESSION;

import com.streamarr.server.domain.LibraryStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MediaParentDeletionRepositoryImpl implements MediaParentDeletionRepository {

  private final DSLContext dsl;

  @Override
  public Optional<LibraryDeletionTarget> prepareLibraryDeletion(UUID libraryId) {
    var target = lockLibrary(libraryId);
    if (target.isEmpty()) {
      return Optional.empty();
    }

    var library = target.orElseThrow();
    dsl.insertInto(
            LIBRARY_DELETION_INTENT,
            LIBRARY_DELETION_INTENT.LIBRARY_ID,
            LIBRARY_DELETION_INTENT.FILEPATH_URI)
        .values(library.libraryId(), library.filepathUri())
        .onConflict(LIBRARY_DELETION_INTENT.LIBRARY_ID)
        .doNothing()
        .execute();
    recordMediaDeletionIntents(library.mediaFileIds());
    return Optional.of(library);
  }

  @Override
  public Optional<LibraryDeletionTarget> resumeLibraryDeletion(UUID libraryId) {
    var target = lockLibrary(libraryId);
    if (target.isEmpty() || !lockDeletionIntent(libraryId)) {
      return Optional.empty();
    }
    return target;
  }

  @Override
  public List<MediaFileDeletionTarget> prepareMediaFileDeletions(
      UUID libraryId, Collection<UUID> mediaFileIds) {
    if (mediaFileIds.isEmpty() || !lockLibraryRow(libraryId)) {
      return List.of();
    }
    if (hasLibraryDeletionIntent(libraryId)) {
      return List.of();
    }

    var targets =
        dsl.select(MEDIA_FILE.ID, MEDIA_FILE.LIBRARY_ID, MEDIA_FILE.MEDIA_ID)
            .from(MEDIA_FILE)
            .where(MEDIA_FILE.LIBRARY_ID.eq(libraryId))
            .and(MEDIA_FILE.ID.in(mediaFileIds))
            .orderBy(MEDIA_FILE.ID)
            .forUpdate()
            .fetch(
                stored ->
                    MediaFileDeletionTarget.builder()
                        .mediaFileId(stored.value1())
                        .libraryId(stored.value2())
                        .mediaId(stored.value3())
                        .build());
    recordMediaDeletionIntents(targets.stream().map(MediaFileDeletionTarget::mediaFileId).toList());
    return targets;
  }

  @Override
  public Optional<MediaFileDeletionTarget> resumeMediaFileDeletion(UUID mediaFileId) {
    var libraryId =
        dsl.select(MEDIA_FILE.LIBRARY_ID)
            .from(MEDIA_FILE)
            .where(MEDIA_FILE.ID.eq(mediaFileId))
            .fetchOptional(MEDIA_FILE.LIBRARY_ID);
    if (libraryId.isEmpty() || !lockLibraryRow(libraryId.orElseThrow())) {
      return Optional.empty();
    }
    if (hasLibraryDeletionIntent(libraryId.orElseThrow())) {
      return Optional.empty();
    }

    return dsl.select(MEDIA_FILE.ID, MEDIA_FILE.LIBRARY_ID, MEDIA_FILE.MEDIA_ID)
        .from(MEDIA_FILE)
        .join(MEDIA_FILE_DELETION_INTENT)
        .on(MEDIA_FILE_DELETION_INTENT.MEDIA_FILE_ID.eq(MEDIA_FILE.ID))
        .where(MEDIA_FILE.ID.eq(mediaFileId))
        .forUpdate()
        .fetchOptional(
            stored ->
                MediaFileDeletionTarget.builder()
                    .mediaFileId(stored.value1())
                    .libraryId(stored.value2())
                    .mediaId(stored.value3())
                    .build());
  }

  @Override
  public List<DeletionIntentEntry> findLibraryDeletionIntents(int limit) {
    return findLibraryDeletionIntents(DSL.noCondition(), limit);
  }

  @Override
  public List<DeletionIntentEntry> findLibraryDeletionIntentsAfter(
      DeletionIntentEntry cursor, int limit) {
    return findLibraryDeletionIntents(
        DSL.row(LIBRARY_DELETION_INTENT.REQUESTED_AT, LIBRARY_DELETION_INTENT.LIBRARY_ID)
            .gt(DSL.row(cursor.requestedAt(), cursor.id())),
        limit);
  }

  @Override
  public List<DeletionIntentEntry> findStandaloneMediaFileDeletionIntents(int limit) {
    return findStandaloneMediaFileDeletionIntents(DSL.noCondition(), limit);
  }

  @Override
  public List<DeletionIntentEntry> findStandaloneMediaFileDeletionIntentsAfter(
      DeletionIntentEntry cursor, int limit) {
    return findStandaloneMediaFileDeletionIntents(
        DSL.row(MEDIA_FILE_DELETION_INTENT.REQUESTED_AT, MEDIA_FILE_DELETION_INTENT.MEDIA_FILE_ID)
            .gt(DSL.row(cursor.requestedAt(), cursor.id())),
        limit);
  }

  private List<DeletionIntentEntry> findLibraryDeletionIntents(
      Condition cursorCondition, int limit) {
    return dsl.select(LIBRARY_DELETION_INTENT.LIBRARY_ID, LIBRARY_DELETION_INTENT.REQUESTED_AT)
        .from(LIBRARY_DELETION_INTENT)
        .where(cursorCondition)
        .orderBy(LIBRARY_DELETION_INTENT.REQUESTED_AT, LIBRARY_DELETION_INTENT.LIBRARY_ID)
        .limit(limit)
        .fetch(stored -> new DeletionIntentEntry(stored.value1(), stored.value2()));
  }

  private List<DeletionIntentEntry> findStandaloneMediaFileDeletionIntents(
      Condition cursorCondition, int limit) {
    return dsl.select(
            MEDIA_FILE_DELETION_INTENT.MEDIA_FILE_ID, MEDIA_FILE_DELETION_INTENT.REQUESTED_AT)
        .from(MEDIA_FILE_DELETION_INTENT)
        .join(MEDIA_FILE)
        .on(MEDIA_FILE.ID.eq(MEDIA_FILE_DELETION_INTENT.MEDIA_FILE_ID))
        .leftJoin(LIBRARY_DELETION_INTENT)
        .on(LIBRARY_DELETION_INTENT.LIBRARY_ID.eq(MEDIA_FILE.LIBRARY_ID))
        .where(LIBRARY_DELETION_INTENT.LIBRARY_ID.isNull())
        .and(cursorCondition)
        .orderBy(MEDIA_FILE_DELETION_INTENT.REQUESTED_AT, MEDIA_FILE_DELETION_INTENT.MEDIA_FILE_ID)
        .limit(limit)
        .fetch(stored -> new DeletionIntentEntry(stored.value1(), stored.value2()));
  }

  @Override
  public boolean hasLibraryDeletionIntent(UUID libraryId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(LIBRARY_DELETION_INTENT)
            .where(LIBRARY_DELETION_INTENT.LIBRARY_ID.eq(libraryId)));
  }

  @Override
  public List<UUID> findReferencingStreamIds(Collection<UUID> mediaFileIds) {
    if (mediaFileIds.isEmpty()) {
      return List.of();
    }
    return dsl.select(STREAM_SESSION.ID)
        .from(STREAM_SESSION)
        .where(STREAM_SESSION.MEDIA_FILE_ID.in(mediaFileIds))
        .orderBy(STREAM_SESSION.ID)
        .fetch(STREAM_SESSION.ID);
  }

  @Override
  public boolean hasReferencingStreams(Collection<UUID> mediaFileIds) {
    if (mediaFileIds.isEmpty()) {
      return false;
    }
    return dsl.fetchExists(
        dsl.selectOne().from(STREAM_SESSION).where(STREAM_SESSION.MEDIA_FILE_ID.in(mediaFileIds)));
  }

  private Optional<LibraryDeletionTarget> lockLibrary(UUID libraryId) {
    var library =
        dsl.select(LIBRARY.ID, LIBRARY.FILEPATH_URI, LIBRARY.STATUS)
            .from(LIBRARY)
            .where(LIBRARY.ID.eq(libraryId))
            .forUpdate()
            .fetchOptional();
    if (library.isEmpty()) {
      return Optional.empty();
    }

    var mediaFileIds =
        dsl.select(MEDIA_FILE.ID)
            .from(MEDIA_FILE)
            .where(MEDIA_FILE.LIBRARY_ID.eq(libraryId))
            .orderBy(MEDIA_FILE.ID)
            .forUpdate()
            .fetch(MEDIA_FILE.ID);
    var stored = library.orElseThrow();
    return Optional.of(
        LibraryDeletionTarget.builder()
            .libraryId(stored.value1())
            .filepathUri(stored.value2())
            .status(LibraryStatus.valueOf(stored.value3().getLiteral()))
            .mediaFileIds(mediaFileIds)
            .build());
  }

  private boolean lockDeletionIntent(UUID libraryId) {
    return dsl.select(LIBRARY_DELETION_INTENT.LIBRARY_ID)
        .from(LIBRARY_DELETION_INTENT)
        .where(LIBRARY_DELETION_INTENT.LIBRARY_ID.eq(libraryId))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  private boolean lockLibraryRow(UUID libraryId) {
    return dsl.select(LIBRARY.ID)
        .from(LIBRARY)
        .where(LIBRARY.ID.eq(libraryId))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  private void recordMediaDeletionIntents(List<UUID> mediaFileIds) {
    if (mediaFileIds.isEmpty()) {
      return;
    }
    var insert =
        dsl.insertInto(MEDIA_FILE_DELETION_INTENT, MEDIA_FILE_DELETION_INTENT.MEDIA_FILE_ID);
    for (var mediaFileId : mediaFileIds) {
      insert = insert.values(mediaFileId);
    }
    insert.onConflict(MEDIA_FILE_DELETION_INTENT.MEDIA_FILE_ID).doNothing().execute();
  }
}
